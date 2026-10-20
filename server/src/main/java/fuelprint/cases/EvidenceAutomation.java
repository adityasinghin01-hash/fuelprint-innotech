package fuelprint.cases;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.RecordSaved;
import fuelprint.api.ServerMode;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.CommandLog;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * After-commit finding to seal to verify. This listener never saves a record and never waits for a result: it only
 * queues a normal command as the automation actor through {@link AutomationBus}.
 *
 * <p>No loop is possible by construction: it reacts only to a saved {@code finding} (to seal) and to a saved
 * original {@code export} (to verify). A verification report, a case revision and a tamper copy start nothing.
 * Duplicates are stopped three ways: an in-flight set, the saved records themselves (an export or report already
 * exists), and the durable automatic receipts (a refused step carries the cause marker and is not retried).
 */
@Component @Profile("!stub")
final class EvidenceAutomation {
    private final AutomationBus automation;
    private final StoreView store;
    private final ObjectProvider<AutomationSettings> settings;
    private final ServerMode mode;
    private final CommandLog commands;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    EvidenceAutomation(AutomationBus automation, StoreView store, ObjectProvider<AutomationSettings> settings, ServerMode mode, CommandLog commands) {
        this.automation = automation; this.store = store; this.settings = settings; this.mode = mode; this.commands = commands;
    }

    @EventListener void saved(RecordSaved event) {
        if (!mode.automationActive() || event.runId() == null) return;
        if (!event.recordType().equals("finding") && !event.recordType().equals("export")) return;
        AutomationSettings current = settings.getIfAvailable();
        if (current == null || !current.isOn(AutomationSettings.AUTO_SEAL)) return;
        StoredRecord record = store.get(event.recordType(), event.recordId(), event.revision()).orElse(null);
        if (record == null || !event.runId().equals(record.runId())) return;
        if (event.recordType().equals("finding")) seal(event, record); else verify(event, record);
    }

    private void seal(RecordSaved event, StoredRecord finding) {
        StoredRecord caseRecord = store.get("case", finding.body().path("case_id").asText()).orElse(null);
        if (caseRecord == null || !event.runId().equals(caseRecord.runId())) return;
        JsonNode pin = caseRecord.body().path("finding_ref");
        // An older finding event, or a case that moved on, must not seal anything.
        if (!"FINDING".equals(caseRecord.body().path("state").asText()) || !finding.id().equals(pin.path("id").asText())
                || pin.path("revision").asLong(-1) != finding.revision()) return;
        ObjectNode cause = CasesRecords.ref(finding);
        String key = event.runId() + "|seal|" + caseRecord.id() + "|" + caseRecord.revision() + "|" + cause;
        if (store.list("export", event.runId()).stream().anyMatch(e -> caseRecord.id().equals(e.body().path("case_id").asText())
                && caseRecord.revision() == e.body().path("case_revision").asLong(-1) && "SEALED".equals(e.body().path("state").asText()))) return;
        if (alreadyAttempted("seal_export", event.runId(), cause) || !pending.add(key)) return;
        ObjectNode payload = CasesRecords.object().put("case_id", caseRecord.id()).put("case_revision", caseRecord.revision());
        payload.set("cause", cause);
        automation.submit("seal_export", payload, event.runId(), caseRecord.revision()).whenComplete((receipt, error) -> pending.remove(key));
    }

    private void verify(RecordSaved event, StoredRecord export) {
        JsonNode body = export.body();
        // Only an original sealed export. A tamper demo copy is never verified or sealed automatically.
        if (!"SEALED".equals(body.path("state").asText()) || !"ORIGINAL".equals(body.path("kind").asText()) || !body.path("copy_of").isNull()) return;
        ObjectNode cause = CasesRecords.ref(export);
        String key = event.runId() + "|verify|" + cause;
        if (store.list("verification_report", event.runId()).stream().anyMatch(r -> export.id().equals(r.body().path("export_id").asText()))) return;
        if (alreadyAttempted("verify_export", event.runId(), cause) || !pending.add(key)) return;
        ObjectNode payload = CasesRecords.object().put("export_id", export.id());
        payload.set("cause", cause);
        automation.submit("verify_export", payload, event.runId(), null).whenComplete((receipt, error) -> pending.remove(key));
    }

    /** A refused automatic step leaves a receipt carrying the cause marker; it is never retried. */
    private boolean alreadyAttempted(String commandType, String runId, ObjectNode cause) {
        String marker = ExportHandlers.marker(cause);
        return commands.automationEntries(runId, Integer.MAX_VALUE).stream().anyMatch(e -> e.commandType().equals(commandType)
                && !e.receipt().applied() && e.receipt().reasonText() != null && e.receipt().reasonText().contains(marker));
    }
}
