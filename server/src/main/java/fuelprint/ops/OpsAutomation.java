package fuelprint.ops;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.RecordSaved;
import fuelprint.api.ServerMode;
import fuelprint.api.WorldClock;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Receipt;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** After-commit intake/evidence orchestration. Writes only through normal automation commands. */
@Component
@Profile("!stub")
final class OpsAutomation {

    private static final Logger log = LoggerFactory.getLogger(OpsAutomation.class);
    private final AutomationBus automation;
    private final StoreView store;
    private final ServerMode mode;
    private final WorldClock world;
    private final ObjectMapper mapper;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    OpsAutomation(AutomationBus automation, StoreView store, ServerMode mode, WorldClock world, ObjectMapper mapper) {
        this.automation = automation;
        this.store = store;
        this.mode = mode;
        this.world = world;
        this.mapper = mapper;
    }

    @EventListener
    void saved(RecordSaved event) {
        if (!mode.automationActive() || event.runId() == null
                || !Set.of("trigger", "investigation", "screening_record").contains(event.recordType())) return;
        StoredRecord record = store.get(event.recordType(), event.recordId(), event.revision()).orElse(null);
        if (record == null || !event.runId().equals(record.runId())) return;
        if (event.recordType().equals("trigger")) {
            if (OpsRecords.triggerConsumed(store, event.runId(), record.id())) return;
            String key = event.runId() + "|trigger|" + record.id() + "|" + record.revision();
            if (!queued.add(key)) return;
            automation.submit("open_investigation", OpsRecords.object().put("trigger_id", record.id()),
                    event.runId(), record.revision()).thenAccept(receipt -> observe(receipt, key));
            return;
        }

        StoredRecord investigation;
        if (event.recordType().equals("investigation")) {
            // Analysis saves leave intake unchanged; compare the exact event revision with its predecessor.
            StoredRecord previous = event.revision() > 1
                    ? store.get("investigation", record.id(), event.revision() - 1).orElse(null) : null;
            if (previous != null && previous.body().path("intake_cause").equals(record.body().path("intake_cause"))) return;
            if (!record.body().hasNonNull("intake_cause")) return;
            investigation = store.get("investigation", record.id()).orElse(null);
        } else {
            investigation = OpsRecords.investigationForEvidence(store, record);
        }
        if (investigation == null || "CLOSED".equals(investigation.body().path("state").asText())) return;
        ObjectNode basis = OpsRecords.basis(store, investigation, mapper.valueToTree(world.wind()));
        if (OpsRecords.sameBasis(basis, investigation.body().path("analysis_basis"))) return;
        String key = event.runId() + "|analysis|" + investigation.id() + "|" + basis;
        if (!queued.add(key)) return;
        ObjectNode payload = OpsRecords.object().put("investigation_id", investigation.id());
        payload.set("cause", OpsRecords.ref(record));
        // Consume the latest committed input atomically, so a concurrent intake revision cannot lose its job.
        // The handler still records both this exact cause and the investigation revision actually consumed.
        automation.submit("run_analysis", payload, event.runId(), null).thenAccept(receipt -> observe(receipt, key));
    }

    private void observe(Receipt receipt, String key) {
        // Only pending submissions need memory; committed records carry durable deduplication.
        queued.remove(key);
        if (!receipt.applied()) {
            log.info("Operations automation {} was refused: {} (not retried)", receipt.commandId(), receipt.reasonCode());
        }
    }
}
