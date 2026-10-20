package fuelprint.ai;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** AI commands use the ordinary transactional bus. No handler trains, calls a provider or joins a command. */
final class AiHandlers {
    private AiHandlers() { }
    static String text(JsonNode node, String key) { return node.path(key).isTextual() ? node.path(key).textValue() : null; }
    static boolean fields(JsonNode payload, Set<String> allowed) {
        if (payload == null || !payload.isObject()) return false;
        var keys = payload.fieldNames();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) return false;
        return true;
    }

    @Component @Profile("!stub")
    @ConditionalOnProperty(name="fp.ai.enabled", havingValue="true", matchIfMissing=true)
    static final class WriteWording implements CommandHandler {
        private final BackendAiServices service;
        private final ObjectMapper mapper;
        WriteWording(BackendAiServices service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
        public String commandType() { return "write_wording"; }
        public Set<Role> allowedRoles() { return Set.of(Role.AUTOMATION); }
        public Set<String> privatePayloadFields() { return Set.of("public_text", "text", "phone_number", "truth"); }
        public RecordKey revisionSubject(Command command) {
            JsonNode target = command.payload() == null ? null : command.payload().path("target");
            if (target == null || !Ids.isUuid(text(target,"id"))) return null;
            return new RecordKey(target.path("type").asText(), target.path("id").asText());
        }
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            JsonNode p = command.payload();
            if (!fields(p, Set.of("target", "purpose"))) return Rejection.invalid("Only a target reference and purpose are accepted; text comes from its saved revision.");
            WordingPurpose purpose;
            try { purpose = WordingPurpose.valueOf(p.path("purpose").asText()); }
            catch (IllegalArgumentException e) { return Rejection.invalid("Choose a supported wording purpose."); }
            String expectedType = switch (purpose) {
                case EXPLANATION -> "analysis";
                case FINDING_SUMMARY -> "finding";
                case SMS_SMOKE_WARNING -> "investigation";
                case SMS_BOARD_UPDATE -> "board_decision";
            };
            JsonNode ref = p.path("target");
            if (!ref.isObject() || ref.size() != 3 || !Ids.isUuid(text(ref,"id"))) return Rejection.invalid("An exact public target reference is required.");
            StoredRecord target = LearningRules.exact(store, ref, expectedType, command.runId());
            if (target == null) return Rejection.invalid("The exact target revision must exist in the requested run and match the purpose.");
            for (StoredRecord prior : store.list("wording", target.runId())) {
                JsonNode body = prior.body(), previous = body.path("target");
                if (purpose.name().equals(body.path("purpose").asText()) && target.type().equals(previous.path("type").asText())
                        && target.id().equals(previous.path("id").asText()) && target.revision() == previous.path("revision").asLong(-1))
                    return HandlerResult.nothing();
            }
            String publicText = publicText(target, purpose);
            if (publicText == null) return Rejection.invalid("The target lacks public text or a supported decision; no wording was saved.");
            Wording wording = service.write(new WordingRequest(new Ref(target.type(), target.id(), target.revision()), purpose, publicText));
            ObjectNode body = mapper.valueToTree(wording);
            return HandlerResult.builder().save(RecordChange.create("wording", Ids.newId(), target.runId(), body), "WORDING_SAVED", null).build();
        }

        private static String publicText(StoredRecord target, WordingPurpose purpose) {
            JsonNode body = target.body();
            return switch (purpose) {
                case EXPLANATION -> text(body,"explanation_text");
                case FINDING_SUMMARY -> {
                    String outcome = text(body,"outcome");
                    if (!Set.of("SUPPORTED_WITHIN_SCOPE","REJECTED_FOR_USE","INCONCLUSIVE").contains(outcome == null ? "" : outcome)) yield null;
                    StringBuilder summary = new StringBuilder(outcome).append(". ");
                    for (JsonNode limitation : body.path("limitations")) if (limitation.isTextual()) summary.append(limitation.textValue()).append(' ');
                    yield summary.toString().strip();
                }
                case SMS_SMOKE_WARNING -> "FuelPrint DEMO (simulated): smoke may be heading toward your area. This is a demo, not a real alert.";
                case SMS_BOARD_UPDATE -> switch (body.path("decision").asText()) {
                    case "CANDIDATE_SUPPORTED_SIMULATED" -> "FuelPrint DEMO (simulated): the board acted on the simulated finding. Demo only.";
                    case "MORE_INVESTIGATION" -> "FuelPrint DEMO (simulated): the board says the investigation is still going. Demo only.";
                    case "NO_SUPPORTED_SOURCE" -> "FuelPrint DEMO (simulated): no source was confirmed. Monitoring continues. Demo only.";
                    case "SAMPLE_INVALID" -> "FuelPrint DEMO (simulated): the sample could not be used, so the investigation continues. Demo only.";
                    default -> null;
                };
            };
        }
    }

    @Component @Profile("!stub")
    @ConditionalOnProperty(name="fp.ai.enabled", havingValue="true", matchIfMissing=true)
    static final class Retrain implements CommandHandler {
        private final ObjectProvider<AutomationSettings> settings;
        Retrain(ObjectProvider<AutomationSettings> settings) { this.settings = settings; }
        public String commandType() { return "retrain_model"; }
        public Set<Role> allowedRoles() { return Set.of(Role.PRESENTER, Role.ANALYST, Role.AUTOMATION); }
        public Set<String> privatePayloadFields() { return Set.of("reason"); }
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            JsonNode p = command.payload();
            if (!fields(p, Set.of("model_kind","reason","cause")) || !"LEARNED_RANKER".equals(text(p,"model_kind"))
                    || text(p,"reason") == null || text(p,"reason").isBlank())
                return Rejection.invalid("LEARNED_RANKER and a reason are required.");
            JsonNode cause = p.path("cause");
            if (session.isAutomation()) {
                var current = settings.getIfAvailable();
                if (current == null || !current.isOn(AutomationSettings.AUTO_RETRAIN))
                    return new Rejection("AUTOMATION_DISABLED", "Automatic retraining is disabled.");
                StoredRecord c = LearningRules.exact(store, cause, "case", command.runId());
                if (c == null || !"SEALED".equals(c.body().path("state").asText()))
                    return Rejection.invalid("Automatic retraining needs an exact sealed-case cause.");
            } else if (!cause.isMissingNode()) return Rejection.invalid("A manual request cannot supply an automatic cause.");
            String marker = cause.isMissingNode() ? "manual" : cause.toString();
            // Persist the assessment in the ordinary refusal receipt, not a fabricated model_version.
            return new Rejection("TRAINING_UNAVAILABLE", "Training is unavailable; no model was created or changed. cause="
                    + marker + "; lessons=" + LearningRules.lessons(store));
        }
    }

    @Component @Profile("!stub")
    @ConditionalOnProperty(name="fp.ai.enabled", havingValue="true", matchIfMissing=true)
    static final class RecordAnomaly implements CommandHandler {
        private final BackendAiServices service;
        RecordAnomaly(BackendAiServices service) { this.service = service; }
        public String commandType() { return "record_anomaly"; }
        public Set<Role> allowedRoles() { return Set.of(Role.AUTOMATION); }
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            var availability=service.availability("NORMAL_AIR");
            return availability.status()==LearnedStatus.MODEL_MISSING
                    ? new Rejection("MODEL_MISSING", "No trained normal-air model is active; no anomaly was saved.")
                    : new Rejection("MODEL_UNAVAILABLE", "Normal-air inference is unavailable ("+availability.reason()+"); no expected band or anomaly was saved.");
        }
    }
}
