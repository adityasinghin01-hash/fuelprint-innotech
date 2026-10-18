package fuelprint.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.store.EventLog;
import fuelprint.core.store.EventRow;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CommandBusTest extends CoreSpringTest {

    static final Session ANALYST = Session.human("s-test", "analyst@laptop-1", Set.of(Role.ANALYST));
    static final Session SUPERVISOR = Session.human("s-test", "supervisor@laptop-1", Set.of(Role.SUPERVISOR));
    static final Session VISITOR = Session.human("s-test", "visitor-7f3a", Set.of(Role.VISITOR));

    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired RecordStore store;
    @Autowired EventLog events;
    @Autowired CommandLog commandLog;
    @Autowired JdbcTemplate jdbc;

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static ObjectNode note(String noteId, String text) {
        return JsonNodeFactory.instance.objectNode().put("note_id", noteId).put("text", text);
    }

    static Command command(String type, ObjectNode payload, Long expectedRevision) {
        return new Command(Command.SCHEMA_VERSION, uuid(), type, null, expectedRevision, payload);
    }

    int eventCount(String commandId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE command_id = ?", Integer.class, commandId);
    }

    int recordCount(String commandId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM records WHERE command_id = ?", Integer.class, commandId);
    }

    // 1
    @Test
    void sameCommandTwiceIsAppliedOnceAndReturnsTheIdenticalReceipt() {
        String noteId = uuid();
        Command create = command(TestHandlers.AutoNote.TYPE, note(noteId, "hello"), 0L);

        Receipt first = bus.send(create, ANALYST);
        Receipt second = bus.send(create, ANALYST);

        assertThat(first.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(second).isEqualTo(first);
        assertThat(store.revisions(TestHandlers.NOTE, noteId)).hasSize(1);
        assertThat(events.forRecord(TestHandlers.NOTE, noteId)).hasSize(1);
        // The resend carries expected_revision 0 although the note is now at 1: it still gets the first receipt.
        assertThat(store.latestRevision(TestHandlers.NOTE, noteId)).isEqualTo(1);
    }

    // 2
    @Test
    void sameCommandIdWithADifferentPayloadIsRejectedAndChangesNothing() {
        String noteId = uuid();
        Command original = command(TestHandlers.AutoNote.TYPE, note(noteId, "original"), 0L);
        Receipt first = bus.send(original, ANALYST);
        Command changed = new Command(Command.SCHEMA_VERSION, original.commandId(), original.commandType(), null,
                0L, note(noteId, "changed"));

        Receipt second = bus.send(changed, ANALYST);

        assertThat(second.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(second.reasonCode()).isEqualTo(ReasonCode.INVALID);
        assertThat(store.get(TestHandlers.NOTE, noteId).orElseThrow().body().path("text").asText()).isEqualTo("original");
        assertThat(commandLog.receipt(original.commandId())).contains(first);
    }

    @Test
    void sameCommandIdFromAnotherSenderIsRefused() {
        Command create = command(TestHandlers.AutoNote.TYPE, note(uuid(), "mine"), 0L);
        Receipt first = bus.send(create, ANALYST);
        Session other = Session.human("s-test", "analyst@laptop-9", Set.of(Role.ANALYST));

        Receipt second = bus.send(create, other);

        assertThat(second.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(second.receiptId()).isNotEqualTo(first.receiptId());
    }

    // 3
    @Test
    void staleExpectedRevisionIsRejectedAndNothingIsSaved() {
        String noteId = uuid();
        bus.send(command(TestHandlers.AutoNote.TYPE, note(noteId, "v1"), 0L), ANALYST);
        Command stale = command(TestHandlers.AutoNote.TYPE, note(noteId, "v2 from an old screen"), 0L);

        Receipt receipt = bus.send(stale, ANALYST);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.STALE_REVISION);
        assertThat(receipt.recordRefs()).isEmpty();
        assertThat(store.latestRevision(TestHandlers.NOTE, noteId)).isEqualTo(1);
        assertThat(eventCount(stale.commandId())).isZero();
        assertThat(recordCount(stale.commandId())).isZero();
        // The rejection itself is the stored receipt: resending returns it.
        assertThat(bus.send(stale, ANALYST)).isEqualTo(receipt);
    }

    @Test
    void currentExpectedRevisionIsApplied() {
        String noteId = uuid();
        bus.send(command(TestHandlers.AutoNote.TYPE, note(noteId, "v1"), 0L), ANALYST);

        Receipt receipt = bus.send(command(TestHandlers.AutoNote.TYPE, note(noteId, "v2"), 1L), ANALYST);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(receipt.recordRefs()).extracting(r -> r.revision()).containsExactly(2L);
    }

    // 4
    @Test
    void wrongRoleIsRejectedNotAllowed() {
        String noteId = uuid();
        for (Session wrong : List.of(VISITOR, ANALYST, Session.anonymous())) {
            Command command = command(TestHandlers.HumanNote.TYPE, note(noteId, "x"), null);
            Receipt receipt = bus.send(command, wrong);
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
            assertThat(eventCount(command.commandId())).isZero();
        }
        assertThat(store.get(TestHandlers.NOTE, noteId)).isEmpty();

        assertThat(bus.send(command(TestHandlers.HumanNote.TYPE, note(noteId, "x"), null), SUPERVISOR).status())
                .isEqualTo(Receipt.Status.APPLIED);
    }

    // 5
    @Test
    void automationSendingAHumanOnlyCommandIsRejectedAndNothingIsSaved() throws Exception {
        String noteId = uuid();

        Receipt receipt = automation.submit(TestHandlers.HumanNote.TYPE, note(noteId, "auto")).get(10, TimeUnit.SECONDS);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(receipt.actor()).isEqualTo("automation");
        assertThat(receipt.automatic()).isTrue();
        assertThat(store.get(TestHandlers.NOTE, noteId)).isEmpty();
        assertThat(eventCount(receipt.commandId())).isZero();
        // The refusal is visible in the automation log.
        assertThat(commandLog.automationLog(10_000)).contains(receipt);
    }

    @Test
    void automationIsRefusedForTheFiveHumanOnlyCommandsEvenBeforeHandlerLookup() throws Exception {
        for (String type : HandlerRegistry.HUMAN_ONLY_COMMANDS) {
            Receipt receipt = automation.submit(type, JsonNodeFactory.instance.objectNode()).get(10, TimeUnit.SECONDS);
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            // No real handlers exist in P1, so these are unknown (INVALID); P3 handlers give NOT_ALLOWED.
            assertThat(receipt.reasonCode()).isIn(ReasonCode.INVALID, ReasonCode.NOT_ALLOWED);
            assertThat(receipt.applied()).isFalse();
        }
    }

    // 6
    @Test
    void automationSendingAnAllowedCommandIsAppliedAsAutomation() throws Exception {
        String noteId = uuid();

        Receipt receipt = automation.submit(TestHandlers.AutoNote.TYPE, note(noteId, "auto"), null, 0L)
                .get(10, TimeUnit.SECONDS);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(receipt.actor()).isEqualTo("automation");
        assertThat(receipt.automatic()).isTrue();
        assertThat(UUID.fromString(receipt.commandId()).version()).isEqualTo(4);
        List<EventRow> saved = events.forRecord(TestHandlers.NOTE, noteId);
        assertThat(saved).singleElement().satisfies(event -> {
            assertThat(event.actor()).isEqualTo("automation");
            assertThat(event.commandId()).isEqualTo(receipt.commandId());
        });
        assertThat(store.get(TestHandlers.NOTE, noteId).orElseThrow().createdBy()).isEqualTo("automation");
        assertThat(commandLog.automationLog(10_000)).contains(receipt);
    }

    @Test
    void humanReceiptsAreNotAutomatic() {
        Receipt receipt = bus.send(command(TestHandlers.AutoNote.TYPE, note(uuid(), "x"), null), ANALYST);
        assertThat(receipt.actor()).isEqualTo("analyst@laptop-1");
        assertThat(receipt.automatic()).isFalse();
        assertThat(commandLog.automationLog(10_000)).doesNotContain(receipt);
    }

    // 7
    @Test
    void automationSubmittedFromAListenerRunsAfterTheFirstCommitNotInsideIt() throws Exception {
        String noteId = uuid();
        Command chain = command(TestHandlers.Chain.TYPE, note(noteId, "start"), null);

        Receipt first = bus.send(chain, ANALYST);
        assertThat(first.status()).isEqualTo(Receipt.Status.APPLIED);
        bus.awaitIdle(); // the receipt is returned before listeners run

        ChainListener.Observation seen = ChainListener.SEEN.get(noteId);
        assertThat(seen).as("listener ran").isNotNull();
        assertThat(seen.transactionActive()).as("listener runs outside any transaction").isFalse();
        assertThat(seen.firstCommitted()).as("first command already committed when the listener ran").isTrue();
        assertThat(seen.onCommandThread()).isTrue();

        Receipt followup = seen.followup().get(10, TimeUnit.SECONDS);
        assertThat(followup.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(followup.automatic()).isTrue();
        StoredRecord saved = store.get(TestHandlers.Followup.RECORD, followup.recordRefs().get(0).id()).orElseThrow();
        assertThat(saved.body().path("first_committed").asBoolean())
                .as("the follow-up saw the first command committed from a separate connection").isTrue();
        long firstCursor = events.forRecord(TestHandlers.NOTE, noteId).get(0).cursor();
        long followupCursor = events.forRecord(TestHandlers.Followup.RECORD, saved.id()).get(0).cursor();
        assertThat(followupCursor).isGreaterThan(firstCursor);
    }

    @Test
    void theCommittedEventIsPublishedOnlyAfterCommit() {
        Command command = command(TestHandlers.AutoNote.TYPE, note(uuid(), "x"), null);
        bus.send(command, ANALYST);
        bus.awaitIdle();
        assertThat(ChainListener.VISIBLE_AT_PUBLISH.get(command.commandId()))
                .as("records were visible to another connection when the event was published").isTrue();
    }

    @Test
    void waitingForASubmittedCommandOnTheCommandThreadIsRefused() {
        String noteId = uuid();
        bus.send(command(TestHandlers.AutoNote.TYPE, note(noteId, ChainListener.PROBE_JOIN), null), ANALYST);
        bus.awaitIdle();
        assertThat(ChainListener.PROBES.get(noteId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendFromTheCommandThreadIsRefused() {
        String noteId = uuid();
        bus.send(command(TestHandlers.AutoNote.TYPE, note(noteId, ChainListener.PROBE_SEND), null), ANALYST);
        bus.awaitIdle();
        assertThat(ChainListener.PROBES.get(noteId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oneRecordSavedEventPerSavedRecordIsPublishedAfterCommit() throws Exception {
        String noteId = uuid();
        Receipt human = bus.send(command(TestHandlers.AutoNote.TYPE, note(noteId, "v1"), null), ANALYST);
        Receipt auto = automation.submit(TestHandlers.AutoNote.TYPE, null, note(noteId, "v2")).get(10, TimeUnit.SECONDS);
        bus.awaitIdle();

        List<ChainListener.SavedSeen> seen = ChainListener.SAVED.stream()
                .filter(s -> s.event().recordId().equals(noteId)).toList();
        assertThat(seen).extracting(s -> s.event().revision()).containsExactly(1L, 2L);
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.visible()).as("committed before publishing").isTrue();
            assertThat(s.transactionActive()).isFalse();
            assertThat(s.event().recordType()).isEqualTo(TestHandlers.NOTE);
            assertThat(s.event().runId()).isNull();
        });
        assertThat(seen.get(0).event().actor()).isEqualTo(human.actor()).isEqualTo("analyst@laptop-1");
        assertThat(seen.get(0).event().automatic()).isFalse();
        assertThat(seen.get(1).event().actor()).isEqualTo(auto.actor()).isEqualTo("automation");
        assertThat(seen.get(1).event().automatic()).isTrue();
    }

    @Test
    void rejectedAndFailedCommandsPublishNoRecordSaved() {
        String noteId = uuid();
        bus.send(command(TestHandlers.HumanNote.TYPE, note(noteId, "x"), null), VISITOR);
        bus.send(command(TestHandlers.AutoNote.TYPE, note(noteId, "x"), 5L), ANALYST);
        bus.send(command(TestHandlers.Throws.TYPE, JsonNodeFactory.instance.objectNode(), null), ANALYST);
        bus.awaitIdle();
        assertThat(ChainListener.SAVED).noneMatch(s -> s.event().recordId().equals(noteId));
    }

    // 8
    @Test
    void handlerThrowingSavesNothingNotEvenAReceipt() {
        Command command = command(TestHandlers.Throws.TYPE, JsonNodeFactory.instance.objectNode(), null);

        Receipt receipt = bus.send(command, ANALYST);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.SERVER_ERROR);
        assertThat(commandLog.receipt(command.commandId())).isEmpty();
        assertThat(eventCount(command.commandId())).isZero();
        assertThat(recordCount(command.commandId())).isZero();
    }

    @Test
    void failureHalfWayThroughSavingRollsBackEverything() {
        String goodId = uuid();
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("good_id", goodId).put("bad_id", uuid());
        Command command = command(TestHandlers.HalfSave.TYPE, payload, null);

        Receipt receipt = bus.send(command, ANALYST);

        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.SERVER_ERROR);
        assertThat(store.get(TestHandlers.NOTE, goodId)).as("first record rolled back").isEmpty();
        assertThat(events.forRecord(TestHandlers.NOTE, goodId)).isEmpty();
        assertThat(commandLog.receipt(command.commandId())).isEmpty();
    }

    // 14
    @Test
    void fiftyParallelCommandsAreSerialisedWithNoLostOrDuplicatedRevisions() throws Exception {
        String counterId = uuid();
        int n = 50;
        ExecutorService senders = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Receipt>> futures = new ArrayList<>();
        TestHandlers.Counter.MAX_IN_FLIGHT.set(0);
        try {
            for (int i = 0; i < n; i++) {
                futures.add(senders.submit(() -> {
                    start.await();
                    ObjectNode payload = JsonNodeFactory.instance.objectNode().put("counter_id", counterId);
                    return bus.send(command(TestHandlers.Counter.TYPE, payload, null), ANALYST);
                }));
            }
            start.countDown();
            Set<Long> revisions = new HashSet<>();
            for (Future<Receipt> future : futures) {
                Receipt receipt = future.get(60, TimeUnit.SECONDS);
                assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
                revisions.add(receipt.recordRefs().get(0).revision());
            }
            assertThat(revisions).hasSize(n).allMatch(r -> r >= 1 && r <= n);
        } finally {
            senders.shutdownNow();
        }
        List<StoredRecord> all = store.revisions(TestHandlers.Counter.RECORD, counterId);
        assertThat(all).extracting(StoredRecord::revision)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, n).boxed().toList());
        assertThat(all).extracting(r -> r.body().path("value").asLong())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, n).boxed().toList());
        assertThat(events.forRecord(TestHandlers.Counter.RECORD, counterId)).hasSize(n);
        assertThat(TestHandlers.Counter.MAX_IN_FLIGHT.get()).as("never two handlers at once").isEqualTo(1);
        assertThat(TestHandlers.Counter.THREADS).containsExactly("fp-command-bus");
    }

    @Test
    void unknownCommandTypeIsRejectedInvalid() {
        Command command = command("no_such_command", JsonNodeFactory.instance.objectNode(), null);
        Receipt receipt = bus.send(command, ANALYST);
        assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.INVALID);
    }

    @Test
    void malformedCommandsAreRejectedInvalidAndNotStored() {
        List<Command> bad = List.of(
                new Command(Command.SCHEMA_VERSION, "not-a-uuid", TestHandlers.AutoNote.TYPE, null, null,
                        JsonNodeFactory.instance.objectNode()),
                new Command("fp/0", uuid(), TestHandlers.AutoNote.TYPE, null, null, JsonNodeFactory.instance.objectNode()),
                new Command(Command.SCHEMA_VERSION, uuid(), "Bad Type", null, null, JsonNodeFactory.instance.objectNode()),
                new Command(Command.SCHEMA_VERSION, uuid(), TestHandlers.AutoNote.TYPE, "run-1", null,
                        JsonNodeFactory.instance.objectNode()),
                new Command(Command.SCHEMA_VERSION, uuid(), TestHandlers.AutoNote.TYPE, null, -1L,
                        JsonNodeFactory.instance.objectNode()),
                new Command(Command.SCHEMA_VERSION, uuid(), TestHandlers.AutoNote.TYPE, null, null, null));
        for (Command command : bad) {
            Receipt receipt = bus.send(command, ANALYST);
            assertThat(receipt.reasonCode()).as(command.toString()).isEqualTo(ReasonCode.INVALID);
            if (command.commandId() != null && fuelprint.core.Ids.isUuid(command.commandId())) {
                assertThat(commandLog.receipt(command.commandId())).isEmpty();
            }
        }
    }

    @Test
    void expectedRevisionForACommandWithoutSubjectIsRejectedInvalid() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("counter_id", uuid());
        Receipt receipt = bus.send(command(TestHandlers.Counter.TYPE, payload, 3L), ANALYST);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.INVALID);
    }

    @Test
    void handlerRejectionIsStoredWithoutRecordsOrEvents() {
        Command command = command(TestHandlers.AutoNote.TYPE, JsonNodeFactory.instance.objectNode().put("text", "no id"), null);
        Receipt receipt = bus.send(command, ANALYST);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.INVALID);
        assertThat(commandLog.receipt(command.commandId())).contains(receipt);
        assertThat(eventCount(command.commandId())).isZero();
    }

    @Test
    void privatePayloadFieldsAreNeverStoredButIdempotencyStillWorks() {
        String phone = "+919812345678";
        String subscriptionId = uuid();
        Session guest = Session.human("s-test", "guest-1a2b", Set.of(Role.GUEST));
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("phone_number", phone).put("subscription_id", subscriptionId);
        Command command = command(TestHandlers.PrivateField.TYPE, payload, null);

        Receipt first = bus.send(command, guest);
        Receipt again = bus.send(command, guest);
        Command otherNumber = new Command(Command.SCHEMA_VERSION, command.commandId(), command.commandType(), null,
                null, payload.deepCopy().put("phone_number", "+919800000001"));
        Receipt changed = bus.send(otherNumber, guest);

        assertThat(first.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(again).isEqualTo(first);
        assertThat(changed.reasonCode()).isEqualTo(ReasonCode.INVALID);
        String digits = "9812345678";
        Integer leaks = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM commands WHERE payload LIKE ? OR receipt_json LIKE ? OR request_digest LIKE ?)"
                        + " + (SELECT COUNT(*) FROM records WHERE body LIKE ?)"
                        + " + (SELECT COUNT(*) FROM events WHERE event_type LIKE ? OR actor LIKE ?)",
                Integer.class, "%" + digits + "%", "%" + digits + "%", "%" + digits + "%", "%" + digits + "%",
                "%" + digits + "%", "%" + digits + "%");
        assertThat(leaks).isZero();
        String storedPayload = jdbc.queryForObject("SELECT payload FROM commands WHERE command_id = ?", String.class,
                command.commandId());
        assertThat(storedPayload).contains(RequestDigest.PRIVATE_MARK).contains(subscriptionId);
    }

    @Test
    void storedReceiptJsonMatchesTheContractFieldNames() throws Exception {
        Command command = command(TestHandlers.AutoNote.TYPE, note(uuid(), "x"), null);
        bus.send(command, ANALYST);
        String json = jdbc.queryForObject("SELECT receipt_json FROM commands WHERE command_id = ?", String.class,
                command.commandId());
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("receipt_id", "command_id", "status",
                "reason_code", "reason_text", "record_refs", "actor", "automatic");
        assertThat(node.path("record_refs").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("type", "id", "revision");
    }

    @Test
    void theDatabaseRefusesAnAutomaticFlagThatDoesNotMatchTheActor() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO commands (command_id, command_type, actor, automatic, payload, "
                + "request_digest, status, receipt_json, received_utc) VALUES (?, 't', 'someone', 1, '{}', 'x', "
                + "'APPLIED', '{}', 'now')", uuid())).isInstanceOf(Exception.class);
    }
}
