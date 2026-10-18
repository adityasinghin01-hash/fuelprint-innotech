package fuelprint.core.command;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.db.DataDirectory;
import fuelprint.core.store.RecordRef;
import fuelprint.core.store.RecordStore;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test listener: when a {@code test_chain} command commits, records what it can observe and submits a
 * follow-up through the {@link AutomationBus}, the way real P3 listeners will.
 */
@Component
public class ChainListener {

    /** What the listener saw, keyed by the chain note id. */
    public record Observation(boolean transactionActive, boolean firstCommitted, boolean onCommandThread,
                              CompletableFuture<Receipt> followup) {
    }

    static final Map<String, Observation> SEEN = new ConcurrentHashMap<>();
    /** Every committed event this listener received, keyed by command id: was the record already visible? */
    static final Map<String, Boolean> VISIBLE_AT_PUBLISH = new ConcurrentHashMap<>();

    /** Note texts that make the listener try something forbidden on the command thread. */
    static final String PROBE_JOIN = "probe-join";
    static final String PROBE_SEND = "probe-send";
    /** What the forbidden attempt threw, keyed by note id (a placeholder string if it did not throw). */
    static final Map<String, Object> PROBES = new ConcurrentHashMap<>();

    private final AutomationBus automation;
    private final DataDirectory dataDirectory;
    private final CommandBus bus;
    private final RecordStore store;

    public ChainListener(AutomationBus automation, DataDirectory dataDirectory, CommandBus bus, RecordStore store) {
        this.automation = automation;
        this.dataDirectory = dataDirectory;
        this.bus = bus;
        this.store = store;
    }

    @EventListener
    public void on(CommandCommitted committed) {
        for (RecordRef ref : committed.receipt().recordRefs()) {
            VISIBLE_AT_PUBLISH.merge(committed.commandId(),
                    TestHandlers.committedElsewhere(dataDirectory, ref.type(), ref.id()), Boolean::logicalAnd);
        }
        if (TestHandlers.AutoNote.TYPE.equals(committed.commandType()) && committed.receipt().applied()) {
            probe(committed.receipt().recordRefs().get(0));
            return;
        }
        if (!TestHandlers.Chain.TYPE.equals(committed.commandType()) || !committed.receipt().applied()) {
            return;
        }
        RecordRef first = committed.receipt().recordRefs().get(0);
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("first_note_id", first.id())
                .put("followup_id", java.util.UUID.randomUUID().toString());
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        boolean visible = TestHandlers.committedElsewhere(dataDirectory, first.type(), first.id());
        CompletableFuture<Receipt> followup = automation.submit(TestHandlers.Followup.TYPE, payload);
        SEEN.put(first.id(), new Observation(active, visible, bus.onCommandThread(), followup));
    }

    private void probe(RecordRef ref) {
        String text = store.get(ref.type(), ref.id()).orElseThrow().body().path("text").asText();
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("note_id", java.util.UUID.randomUUID().toString()).put("text", "inner");
        try {
            if (PROBE_JOIN.equals(text)) {
                automation.submit(TestHandlers.AutoNote.TYPE, payload).join();
            } else if (PROBE_SEND.equals(text)) {
                bus.send(new Command(Command.SCHEMA_VERSION, java.util.UUID.randomUUID().toString(),
                        TestHandlers.AutoNote.TYPE, null, null, payload), Session.automation());
            } else {
                return;
            }
            PROBES.put(ref.id(), "did not throw");
        } catch (RuntimeException e) {
            PROBES.put(ref.id(), e);
        }
    }
}
