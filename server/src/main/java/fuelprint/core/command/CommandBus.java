package fuelprint.core.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import fuelprint.api.RecordSaved;
import fuelprint.core.Ids;
import fuelprint.core.store.EventLog;
import fuelprint.core.store.EventRow;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.RecordRef;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StaleRevisionException;
import fuelprint.core.store.StoredRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way to change the world (AGREEMENT sections 1 and 3, AGENTS.md backend rules).
 *
 * <p>Every command runs on one single-threaded executor, so commands never overlap. For each command:
 * <ol>
 *   <li>Malformed command (bad id, type, run id or revision): REJECTED {@code INVALID}, not stored.</li>
 *   <li>Idempotency: a {@code command_id} already decided returns its stored receipt and changes nothing,
 *       if the same actor sent the same request; a different request is REJECTED {@code INVALID}.
 *       This comes before the revision check so that resending an applied command returns its first
 *       receipt instead of {@code STALE_REVISION} (AGREEMENT section 3).</li>
 *   <li>Unknown {@code command_type}: REJECTED {@code INVALID}.</li>
 *   <li>Role from the session: REJECTED {@code NOT_ALLOWED} unless the session holds an allowed role.
 *       The automation actor is refused for the five human-only commands.</li>
 *   <li>{@code expected_revision} against the handler's subject record: REJECTED {@code STALE_REVISION}.</li>
 *   <li>The handler decides. Its records, one event per record, and the receipt are saved in ONE
 *       transaction. If anything throws, nothing at all is saved and the (unstored) receipt is REJECTED
 *       {@code SERVER_ERROR}, so resending is safe.</li>
 *   <li>Only after the commit, one {@link RecordSaved} Spring event per saved record, then a
 *       {@link CommandCommitted} for the command, are published.</li>
 * </ol>
 * Every rejection from step 3 on is stored with its receipt (that is what the automation log shows),
 * but saves no record and no event. Requests with no session (anonymous) never write anything.
 */
@Component
public class CommandBus {

    private static final Logger log = LoggerFactory.getLogger(CommandBus.class);
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final long SEND_TIMEOUT_S = 60;

    private final HandlerRegistry registry;
    private final RecordStore store;
    private final EventLog events;
    private final CommandLog commandLog;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher publisher;
    private final RequestDigest digests = new RequestDigest();
    private final ExecutorService executor;
    private volatile Thread commandThread;

    public CommandBus(HandlerRegistry registry, RecordStore store, EventLog events, CommandLog commandLog,
                      PlatformTransactionManager transactionManager, ApplicationEventPublisher publisher) {
        this.registry = registry;
        this.store = store;
        this.events = events;
        this.commandLog = commandLog;
        this.transactions = new TransactionTemplate(transactionManager);
        this.publisher = publisher;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fp-command-bus");
            thread.setDaemon(true);
            commandThread = thread;
            return thread;
        });
    }

    /**
     * Sends a command from a person's request and waits for its receipt.
     *
     * @throws IllegalStateException if called on the command thread (from a handler or an event listener);
     *                               use {@link AutomationBus#submit} there
     */
    public Receipt send(Command command, Session session) {
        if (onCommandThread()) {
            throw new IllegalStateException("send() would wait for itself on the command thread; use AutomationBus.submit");
        }
        try {
            return enqueue(command, session).get(SEND_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the command thread", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("The command thread did not answer", e);
        }
    }

    /**
     * Waits until every command queued before this call has finished, including its after-commit
     * listeners. {@link #send} returns as soon as the receipt exists, which is before listeners run.
     *
     * @throws IllegalStateException if called on the command thread
     */
    public void awaitIdle() {
        if (onCommandThread()) {
            throw new IllegalStateException("awaitIdle() would wait for itself on the command thread");
        }
        try {
            executor.submit(() -> { }).get(SEND_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the command thread", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("The command thread did not finish", e);
        }
    }

    /** True if the current thread is the single command thread. */
    public boolean onCommandThread() {
        return Thread.currentThread() == commandThread;
    }

    /** Queues a command on the command thread. The future completes with its receipt. */
    CompletableFuture<Receipt> enqueue(Command command, Session session) {
        CommandFuture future = new CommandFuture(this);
        try {
            executor.execute(() -> run(command, session, future));
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("The command bus is shut down", e));
        }
        return future;
    }

    private void run(Command command, Session session, CompletableFuture<Receipt> future) {
        Decision decision;
        try {
            decision = decide(command, session);
        } catch (RuntimeException | Error e) {
            // Never log the payload: it may hold private values.
            log.error("Command {} ({}) failed; nothing was saved", command.commandId(), command.commandType(), e);
            decision = new Decision(unsaved(command.commandId(), session, ReasonCode.SERVER_ERROR,
                    "The server failed while running this command. Nothing was saved; you can send it again."), null);
        }
        // Receipt first, then the broadcast event (AGREEMENT section 3).
        future.complete(decision.receipt());
        publishAfterCommit(decision);
    }

    /**
     * After commit: one {@link RecordSaved} per saved record in cursor order (CORE-API section 1), then
     * {@link EventsCommitted} (the live socket), then the {@link CommandCommitted} for the whole command.
     * A failing listener never undoes the commit.
     */
    private void publishAfterCommit(Decision decision) {
        if (decision.committed() == null) {
            return;
        }
        publishEvents(decision.saved(), decision.committed().commandId());
        publish(decision.committed(), decision.committed().commandId());
    }

    private void publishEvents(List<CommittedEvent> saved, String commandId) {
        for (CommittedEvent committed : saved) {
            EventRow event = committed.event();
            publish(new RecordSaved(event.recordType(), event.recordId(), event.revision(), event.runId(),
                    event.actor(), committed.automatic()), commandId);
        }
        if (!saved.isEmpty()) {
            publish(new EventsCommitted(saved), commandId);
        }
    }

    private void publish(Object event, String commandId) {
        try {
            publisher.publishEvent(event);
        } catch (RuntimeException e) {
            log.error("A listener failed after command {} committed", commandId, e);
        }
    }

    /** The receipt and, if something was stored, what to publish after commit. */
    private record Decision(Receipt receipt, CommandCommitted committed, List<CommittedEvent> saved) {
        Decision(Receipt receipt, CommandCommitted committed) {
            this(receipt, committed, List.of());
        }
    }

    /** Thrown inside the transaction to roll it back and store a rejection instead. */
    private static final class Refused extends RuntimeException {
        private final Rejection rejection;

        Refused(Rejection rejection) {
            super(rejection.reasonCode(), null, false, false);
            this.rejection = rejection;
        }
    }

    private Decision decide(Command command, Session session) {
        String problem = formatProblem(command);
        if (problem != null) {
            return new Decision(unsaved(command.commandId(), session, ReasonCode.INVALID, problem), null);
        }
        CommandHandler handler = registry.find(command.commandType()).orElse(null);
        Set<String> privateFields = handler == null ? Set.of() : handler.privatePayloadFields();
        RequestDigest.Result digest = digests.of(command, privateFields, handler == null);
        String actor = handler == null ? session.actorLabel() : session.actorFor(handler.allowedRoles());

        var existing = commandLog.find(command.commandId());
        if (existing.isPresent()) {
            return new Decision(replay(existing.get(), digest, command, session, actor), null);
        }
        if (handler == null) {
            return storeRejection(command, session, actor, digest, Rejection.invalid(
                    "The server does not know the command '" + command.commandType() + "'."));
        }
        Rejection roleProblem = roleProblem(command, session, handler);
        if (roleProblem != null) {
            return storeRejection(command, session, actor, digest, roleProblem);
        }
        try {
            return transactions.execute(status -> apply(command, session, actor, handler, digest));
        } catch (Refused refused) {
            return storeRejection(command, session, actor, digest, refused.rejection);
        } catch (StaleRevisionException stale) {
            return storeRejection(command, session, actor, digest, Rejection.staleRevision(staleText(stale.key(),
                    stale.expected(), stale.actual())));
        }
    }

    private static String formatProblem(Command command) {
        if (!Ids.isUuid(command.commandId())) {
            return "command_id must be a UUID.";
        }
        if (!Command.SCHEMA_VERSION.equals(command.schemaVersion())) {
            return "schema_version must be " + Command.SCHEMA_VERSION + ".";
        }
        if (command.commandType() == null || !TYPE.matcher(command.commandType()).matches()) {
            return "command_type is missing or malformed.";
        }
        if (command.runId() != null && !Ids.isUuid(command.runId())) {
            return "run_id must be a UUID or null.";
        }
        if (command.expectedRevision() != null && command.expectedRevision() < 0) {
            return "expected_revision must be 0 or more.";
        }
        if (command.payload() == null) {
            return "payload must be a JSON object.";
        }
        return null;
    }

    private Receipt replay(CommandLog.StoredCommand stored, RequestDigest.Result digest, Command command,
                           Session session, String actor) {
        if (!stored.requestDigest().equals(digest.digest())) {
            String text = digests.fromEarlierStart(stored.requestDigest())
                    ? "This command_id was decided before the server restarted and cannot be checked again. "
                    + "Send it as a new command."
                    : "This command_id was already used for a different command. Send it with a new command_id.";
            return unsaved(command.commandId(), session, ReasonCode.INVALID, text);
        }
        if (!stored.actor().equals(actor)) {
            return unsaved(command.commandId(), session, ReasonCode.NOT_ALLOWED,
                    "This command_id was sent by someone else.");
        }
        return stored.receipt();
    }

    private static Rejection roleProblem(Command command, Session session, CommandHandler handler) {
        String type = command.commandType();
        if (session.isAutomation() && HandlerRegistry.HUMAN_ONLY_COMMANDS.contains(type)) {
            return Rejection.notAllowed("Only a person can send " + type + "; the server's automation may not.");
        }
        Set<Role> allowed = handler.allowedRoles();
        if (session.hasAnyRole(allowed)) {
            return null;
        }
        String needed = allowed.stream().map(Role::wire).sorted().collect(Collectors.joining(", "));
        if (session.isAutomation()) {
            return Rejection.notAllowed("The server's automation may not send " + type + ". It needs: " + needed + ".");
        }
        if (!session.isSignedIn()) {
            return Rejection.notAllowed("Sign in at a station first. " + type + " needs: " + needed + ".");
        }
        String held = session.roles().stream().map(Role::wire).sorted().collect(Collectors.joining(", "));
        return Rejection.notAllowed("Your session (" + held + ") cannot send " + type + ". It needs: " + needed + ".");
    }

    private Decision apply(Command command, Session session, String actor, CommandHandler handler,
                           RequestDigest.Result digest) {
        RecordKey subject = handler.revisionSubject(command);
        Long expected = command.expectedRevision();
        if (expected != null) {
            if (subject == null) {
                throw new Refused(Rejection.invalid(command.commandType() + " does not take expected_revision."));
            }
            long latest = store.latestRevision(subject.type(), subject.id());
            if (latest != expected) {
                throw new Refused(Rejection.staleRevision(staleText(subject, expected, latest)));
            }
        }

        HandlerOutcome outcome = handler.handle(command, session, store);
        if (outcome == null) {
            throw new IllegalStateException("Handler for " + command.commandType() + " returned nothing");
        }
        if (outcome instanceof Rejection rejection) {
            throw new Refused(rejection);
        }
        HandlerResult result = (HandlerResult) outcome;
        Map<RecordKey, EventDraft> eventByRecord = matchEvents(result);

        List<CommittedEvent> saved = saveAll(result, eventByRecord, actor, session.isAutomation(),
                command.commandId(), false);
        List<RecordRef> refs = saved.stream()
                .map(c -> new RecordRef(c.event().recordType(), c.event().recordId(), c.event().revision())).toList();
        Receipt receipt = new Receipt(Ids.newId(), command.commandId(), Receipt.Status.APPLIED, null, null, refs,
                actor, session.isAutomation());
        commandLog.insert(command, actor, session.isAutomation(), digest, receipt);
        return new Decision(receipt, new CommandCommitted(command.commandId(), command.commandType(),
                command.runId(), receipt, saved.stream().map(CommittedEvent::event).toList()), saved);
    }

    /** Saves every record of {@code result} and its event; inside the caller's transaction. */
    private List<CommittedEvent> saveAll(HandlerResult result, Map<RecordKey, EventDraft> eventByRecord, String actor,
                                         boolean automatic, String commandId, boolean appendToLatest) {
        List<CommittedEvent> saved = new ArrayList<>();
        for (RecordChange change : result.records()) {
            RecordChange effective = appendToLatest
                    ? new RecordChange(change.type(), change.id(), change.runId(),
                    store.latestRevision(change.type(), change.id()), change.fields())
                    : change;
            StoredRecord record = store.save(effective, actor, commandId);
            EventDraft draft = eventByRecord.get(change.key());
            EventRow event = events.append(draft.eventType(), record, draft.simTimeS(), actor, commandId);
            saved.add(new CommittedEvent(event, record.body(), automatic));
        }
        return saved;
    }

    /**
     * Core only (the world loop, recovery, defaults and fake replay; an ArchUnit test keeps other packages out):
     * saves records and their events without a command, as {@code actor}, in one transaction on the command
     * thread, then publishes {@link RecordSaved} and {@link EventsCommitted} after commit.
     *
     * @param appendToLatest save each record as the next revision of whatever is latest (fake replay), instead
     *                       of requiring {@code basedOnRevision} to match
     */
    public CompletableFuture<List<CommittedEvent>> saveWithoutCommand(String actor, HandlerResult result,
                                                                      boolean appendToLatest) {
        boolean automatic = Session.AUTOMATION_ACTOR.equals(actor);
        CompletableFuture<List<CommittedEvent>> future = new CompletableFuture<>();
        Runnable task = () -> {
            List<CommittedEvent> saved;
            try {
                Map<RecordKey, EventDraft> eventByRecord = matchEvents(result);
                saved = transactions.execute(status ->
                        saveAll(result, eventByRecord, actor, automatic, null, appendToLatest));
            } catch (RuntimeException e) {
                log.error("Saving {} records without a command failed; nothing was saved", result.records().size(), e);
                future.completeExceptionally(e);
                return;
            }
            future.complete(saved);
            publishEvents(saved, null);
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("The command bus is shut down", e));
        }
        return future;
    }

    /**
     * Core only (fake replay): stores a recorded command exactly as it was decided, with its records, events and
     * receipt, in one transaction on the command thread. Nothing is checked; the handlers do not run.
     */
    public CompletableFuture<Receipt> saveRecordedCommand(RecordedCommand recorded) {
        CompletableFuture<Receipt> future = new CompletableFuture<>();
        Runnable task = () -> {
            boolean automatic = Session.AUTOMATION_ACTOR.equals(recorded.actor());
            Decision decision;
            try {
                decision = transactions.execute(status -> {
                    Map<RecordKey, EventDraft> eventByRecord = matchEvents(recorded.result());
                    List<CommittedEvent> saved = saveAll(recorded.result(), eventByRecord, recorded.actor(),
                            automatic, recorded.commandId(), true);
                    List<RecordRef> refs = saved.stream().map(c -> new RecordRef(c.event().recordType(),
                            c.event().recordId(), c.event().revision())).toList();
                    Receipt receipt = new Receipt(Ids.newId(), recorded.commandId(), recorded.status(),
                            recorded.reasonCode(), recorded.reasonText(), refs, recorded.actor(), automatic);
                    commandLog.insertRecorded(recorded, receipt);
                    return new Decision(receipt, new CommandCommitted(recorded.commandId(), recorded.commandType(),
                            recorded.runId(), receipt, saved.stream().map(CommittedEvent::event).toList()), saved);
                });
            } catch (RuntimeException e) {
                log.error("Recorded command {} could not be stored; nothing was saved", recorded.commandId(), e);
                future.completeExceptionally(e);
                return;
            }
            future.complete(decision.receipt());
            publishAfterCommit(decision);
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("The command bus is shut down", e));
        }
        return future;
    }

    /** Checks that every record is saved once, has exactly one event, and every event belongs to a record. */
    private static Map<RecordKey, EventDraft> matchEvents(HandlerResult result) {
        Set<RecordKey> keys = new HashSet<>();
        for (RecordChange change : result.records()) {
            if (!keys.add(change.key())) {
                throw new IllegalStateException("A result may save each record only once: " + change.key());
            }
        }
        Map<RecordKey, EventDraft> byRecord = new HashMap<>();
        for (EventDraft draft : result.events()) {
            RecordKey key = new RecordKey(draft.recordType(), draft.recordId());
            if (!keys.contains(key)) {
                throw new IllegalStateException("Event " + draft.eventType() + " refers to no record in the result");
            }
            if (byRecord.putIfAbsent(key, draft) != null) {
                throw new IllegalStateException("Two events for one record: " + key);
            }
        }
        if (byRecord.size() != keys.size()) {
            throw new IllegalStateException("Every saved record needs exactly one event");
        }
        return byRecord;
    }

    private Decision storeRejection(Command command, Session session, String actor, RequestDigest.Result digest,
                                    Rejection rejection) {
        if (!session.isSignedIn()) {
            // No session at all: never write anything for it (it can never be applied anyway).
            return new Decision(unsaved(command.commandId(), session, rejection.reasonCode(), rejection.reasonText()), null);
        }
        Receipt receipt = new Receipt(Ids.newId(), command.commandId(), Receipt.Status.REJECTED,
                rejection.reasonCode(), rejection.reasonText(), List.of(), actor, session.isAutomation());
        transactions.executeWithoutResult(status ->
                commandLog.insert(command, actor, session.isAutomation(), digest, receipt));
        return new Decision(receipt, new CommandCommitted(command.commandId(), command.commandType(),
                command.runId(), receipt, List.of()));
    }

    /** A REJECTED receipt that is not stored (the command could not be identified or nothing was decided). */
    static Receipt unsaved(String commandId, Session session, String reasonCode, String reasonText) {
        return new Receipt(Ids.newId(), commandId, Receipt.Status.REJECTED, reasonCode, reasonText, List.of(),
                session.actorLabel(), session.isAutomation());
    }

    private static String staleText(RecordKey key, long expected, long latest) {
        return "The " + key.type() + " changed: you saw revision " + expected + ", it is now revision " + latest
                + ". Reload and try again.";
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    /**
     * A future that refuses to be waited on from the command thread, where waiting would block forever
     * (the command it waits for runs on that same thread, later).
     */
    private static final class CommandFuture extends CompletableFuture<Receipt> {
        private final CommandBus bus;

        CommandFuture(CommandBus bus) {
            this.bus = bus;
        }

        private void refuseOnCommandThread() {
            if (bus.onCommandThread() && !isDone()) {
                throw new IllegalStateException(
                        "Do not wait for a submitted command on the command thread; it runs after the current one");
            }
        }

        @Override
        public Receipt get() throws InterruptedException, ExecutionException {
            refuseOnCommandThread();
            return super.get();
        }

        @Override
        public Receipt get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            refuseOnCommandThread();
            return super.get(timeout, unit);
        }

        @Override
        public Receipt join() {
            refuseOnCommandThread();
            return super.join();
        }
    }
}
