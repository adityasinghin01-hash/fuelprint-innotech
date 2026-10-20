package fuelprint.ai;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.CommandLog;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** After-commit requests only. The available wording path is pure; no recursive submit/join deadlock. */
@Component @Profile("!stub")
@ConditionalOnProperty(name="fp.ai.enabled", havingValue="true", matchIfMissing=true)
final class AiAutomation {
    private final AutomationBus automation;
    private final StoreView store;
    private final ObjectProvider<AutomationSettings> settings;
    private final ServerMode mode;
    private final CommandLog commands;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    AiAutomation(AutomationBus automation, StoreView store, ObjectProvider<AutomationSettings> settings, ServerMode mode, CommandLog commands) {
        this.automation=automation; this.store=store; this.settings=settings; this.mode=mode; this.commands=commands;
    }
    /** Prepare old public targets when AI was absent at their creation. This never queues an alert. */
    @EventListener(ApplicationReadyEvent.class) void recoverSmsWordings() {
        if (!mode.automationActive()) return;
        for (String type : Set.of("investigation", "board_decision")) {
            for (StoredRecord latest : store.listAll(type)) {
                for (long number=latest.revision();number>0;number--) {
                    StoredRecord revision=store.get(type,latest.id(),number).orElse(null);
                    if (revision != null && revision.runId() != null) requestWording(revision,
                            type.equals("investigation") ? "SMS_SMOKE_WARNING" : "SMS_BOARD_UPDATE");
                }
            }
        }
    }
    @EventListener void saved(RecordSaved event) {
        if (!mode.automationActive() || event.runId()==null) return;
        if (!Set.of("analysis","finding","case","investigation","board_decision").contains(event.recordType())) return;
        var target=store.get(event.recordType(),event.recordId(),event.revision()).orElse(null);
        if(target==null || !event.runId().equals(target.runId())) return;
        ObjectNode ref=LearningRules.ref(target);
        String key=ref.toString();
        if (event.recordType().equals("case")) {
            var current=settings.getIfAvailable();
            if (!"SEALED".equals(target.body().path("state").asText()) || current==null || !current.isOn(AutomationSettings.AUTO_RETRAIN)) return;
            // Even an unavailable attempt has a durable receipt. Replayed events must not retry it.
            String marker="cause="+ref+"; lessons=";
            boolean assessed=commands.automationEntries(event.runId(),Integer.MAX_VALUE).stream().anyMatch(entry ->
                    entry.commandType().equals("retrain_model") && "TRAINING_UNAVAILABLE".equals(entry.receipt().reasonCode())
                            && entry.receipt().reasonText().contains(marker));
            if (assessed || !pending.add(key)) return;
            var payload=JsonNodeFactory.instance.objectNode().put("model_kind","LEARNED_RANKER").put("reason","Case sealed; assess public lessons");
            payload.set("cause",ref);
            automation.submit("retrain_model",payload,event.runId(),null).whenComplete((receipt,error)->pending.remove(key));
        } else {
            // Prepare public SMS text even with auto_alerts off: a presenter may send manually.
            // Sending itself remains in Alerts and still obeys consent/caps/automation settings.
            String purpose=switch(event.recordType()) {
                case "analysis" -> "EXPLANATION";
                case "finding" -> "FINDING_SUMMARY";
                case "investigation" -> "SMS_SMOKE_WARNING";
                case "board_decision" -> "SMS_BOARD_UPDATE";
                default -> throw new IllegalStateException("Unsupported wording target");
            };
            requestWording(target,purpose);
        }
    }

    private void requestWording(StoredRecord target,String purpose) {
        ObjectNode ref=LearningRules.ref(target);
        boolean saved=store.list("wording",target.runId()).stream().anyMatch(record -> {
            var body=record.body();var prior=body.path("target");
            return purpose.equals(body.path("purpose").asText()) && target.type().equals(prior.path("type").asText())
                    && target.id().equals(prior.path("id").asText()) && prior.path("revision").isIntegralNumber()
                    && target.revision()==prior.path("revision").asLong(-1);
        });
        String key=purpose+":"+ref;
        if (saved || !pending.add(key)) return;
        var payload=JsonNodeFactory.instance.objectNode().put("purpose",purpose);
        payload.set("target",ref);
        automation.submit("write_wording",payload,target.runId(),null).whenComplete((receipt,error)->pending.remove(key));
    }
}
