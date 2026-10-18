package fuelprint.alerts;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.RecordSaved;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The automatic alert steps (AGREEMENT section 12). Listens for saved records and, when
 * {@code auto_alerts} is on, submits {@code send_alert} as the automation actor. It never saves a record
 * itself.
 * <ul>
 *   <li>An {@code investigation} that is OPEN and has {@code warned_area_ids}: one SMOKE_WARNING for those
 *       areas (areas already alerted for this investigation are not sent again).</li>
 *   <li>A {@code board_decision}: one BOARD_UPDATE for {@code notice.area_ids}.</li>
 * </ul>
 * A command the bus rejects is logged and not retried.
 */
@Component
@Profile("!stub")
class AlertAutomation {

    private static final Logger log = LoggerFactory.getLogger(AlertAutomation.class);

    private final AutomationBus automation;
    private final StoreView store;
    private final ObjectProvider<AutomationSettings> settings;
    private final Set<String> submitted = ConcurrentHashMap.newKeySet();

    AlertAutomation(AutomationBus automation, StoreView store, ObjectProvider<AutomationSettings> settings) {
        this.automation = automation;
        this.store = store;
        this.settings = settings;
    }

    @EventListener
    void onRecordSaved(RecordSaved saved) {
        try {
            if (AlertRecords.INVESTIGATION.equals(saved.recordType())) {
                onInvestigation(saved);
            } else if (AlertRecords.BOARD_DECISION.equals(saved.recordType())) {
                onBoardDecision(saved);
            }
        } catch (RuntimeException e) {
            log.error("The alert automation failed for a {} ({})", saved.recordType(), e.getClass().getSimpleName());
        }
    }

    private void onInvestigation(RecordSaved saved) {
        StoredRecord investigation = store.get(saved.recordType(), saved.recordId(), saved.revision()).orElse(null);
        if (investigation == null || !"OPEN".equals(investigation.body().path("state").asText())) {
            return;
        }
        List<String> warned = AlertRecords.strings(investigation.body().get("warned_area_ids"));
        if (warned == null || warned.isEmpty()) {
            return;
        }
        if (!autoAlertsOn()) {
            log.info("auto_alerts is off: no smoke warning sent for investigation {}", saved.recordId());
            return;
        }
        // Areas that already have an alert for this investigation (a later revision, or a restart) are skipped.
        List<String> fresh = warned.stream()
                .filter(AlertGeography::isArea)
                .filter(area -> !alreadyAlerted(saved.recordId(), area))
                .filter(area -> submitted.add(saved.recordId() + "|" + area))
                .toList();
        if (fresh.isEmpty()) {
            return;
        }
        submit(AlertTexts.SMOKE_WARNING, fresh, saved);
    }

    private void onBoardDecision(RecordSaved saved) {
        StoredRecord decision = store.get(saved.recordType(), saved.recordId(), saved.revision()).orElse(null);
        if (decision == null) {
            return;
        }
        List<String> areas = AlertRecords.strings(decision.body().path("notice").get("area_ids"));
        if (areas == null || areas.isEmpty() || !areas.stream().allMatch(AlertGeography::isArea)) {
            log.info("Board decision {} names no alert area: no board update sent", saved.recordId());
            return;
        }
        if (!autoAlertsOn()) {
            log.info("auto_alerts is off: no board update sent for decision {}", saved.recordId());
            return;
        }
        if (!submitted.add(saved.recordId() + "|" + saved.revision())) {
            return;
        }
        submit(AlertTexts.BOARD_UPDATE, areas, saved);
    }

    private void submit(String kind, List<String> areas, RecordSaved cause) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("kind", kind);
        ArrayNode areaIds = payload.putArray("area_ids");
        areas.forEach(areaIds::add);
        payload.putObject("cause").put("type", cause.recordType()).put("id", cause.recordId())
                .put("revision", cause.revision());
        automation.submit(SendAlertHandler.TYPE, cause.runId(), payload).thenAccept(receipt -> {
            if (!receipt.applied()) {
                log.warn("send_alert {} was refused: {} (not retried)", kind, receipt.reasonCode());
            }
        });
    }

    private boolean alreadyAlerted(String investigationId, String area) {
        for (StoredRecord notification : store.list(AlertRecords.NOTIFICATION, null)) {
            JsonNode body = notification.body();
            if (!AlertTexts.SMOKE_WARNING.equals(body.path("kind").asText())
                    || !investigationId.equals(body.path("cause").path("id").asText())) {
                continue;
            }
            List<String> areas = AlertRecords.strings(body.get("area_ids"));
            if (areas != null && areas.contains(area)) {
                return true;
            }
        }
        return false;
    }

    private boolean autoAlertsOn() {
        AutomationSettings current = settings.getIfAvailable();
        return current == null || current.isOn(AutomationSettings.AUTO_ALERTS);
    }
}
