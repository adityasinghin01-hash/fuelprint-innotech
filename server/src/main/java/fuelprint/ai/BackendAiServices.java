package fuelprint.ai;

import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.api.*;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Real backend availability and safe templates. No training, network provider or trained inference is installed. */
@Component @Profile("!stub")
@ConditionalOnProperty(name="fp.ai.enabled", havingValue="true", matchIfMissing=true)
final class BackendAiServices implements LearnedRanker, NormalAirModel, WordingService {
    private static final String PREFIX = "FuelPrint DEMO (simulated):";
    private static final List<String> BANNED = List.of("guilty", "illegal", "proved", "coal", "probability", "chance", "%");
    private static final Pattern PRIVATE = Pattern.compile(
            "(?i)(hidden[_ -]*truth|actual[_ -]*emitter|emitting[_ -]*factory|phone[_ -]*number)");
    private static final Pattern SOURCE = Pattern.compile("(?i)S0[1-6]");
    private final StoreView store;

    BackendAiServices(StoreView store) { this.store = store; }

    @Override public LearnedOrder order(AnalysisInput input, Analysis physics) {
        if (physics == null) return LearnedOrder.none(LearnedStatus.FAILED, null, null);
        if (physics.outcome() == Outcome.INVALID || physics.outcome() == Outcome.NO_SIGNAL)
            return LearnedOrder.none(LearnedStatus.NOT_APPLICABLE, null, null);
        Map<String,Integer> counts = history(input);
        Availability availability = availability("LEARNED_RANKER");
        return LearnedOrder.none(availability.status(), availability.modelId(), counts);
    }

    record Availability(LearnedStatus status, String modelId, String reason) { }
    Availability availability(String kind) {
        List<StoredRecord> active = store.list("model_version", null).stream().filter(r -> kind.equals(r.body().path("model_kind").asText())
                && "ACTIVE".equals(r.body().path("state").asText())).toList();
        if (active.isEmpty()) return new Availability(LearnedStatus.MODEL_MISSING, null, "MODEL_MISSING");
        if (active.size() != 1) return new Availability(LearnedStatus.FAILED, null, "AMBIGUOUS_ACTIVE_MODELS");
        StoredRecord model = active.get(0);
        JsonNode inspected = ModelArtifacts.inspect(model);
        // No artifact can currently supply inference, even if its stored bytes match its hash.
        return new Availability(LearnedStatus.FAILED, model.id(), inspected.path("reason").asText());
    }

    private Map<String,Integer> history(AnalysisInput input) {
        if (input == null) return null;
        List<StoredRecord> runs = store.listAll("run");
        Set<String> earlier = new HashSet<>();
        boolean found = false;
        // StoreView orders runs by their first append, avoiding ambiguous tied wall timestamps.
        for (StoredRecord run : runs) {
            if (run.id().equals(input.runId())) { found = true; break; }
            earlier.add(run.id());
        }
        if (!found) return null;
        Map<String,Integer> counts = new TreeMap<>();
        for (int i = 1; i <= 6; i++) counts.put("S0" + i, 0);
        for (String run : earlier) {
            Set<String> survived = new HashSet<>();
            for (StoredRecord analysis : store.list("analysis", run)) {
                for (JsonNode candidate : analysis.body().path("candidates")) {
                    if (!"SURVIVES".equals(candidate.path("status").asText())) continue;
                    for (JsonNode source : candidate.path("sources"))
                        if (source.isTextual() && counts.containsKey(source.textValue())) survived.add(source.textValue());
                }
            }
            survived.forEach(source -> counts.compute(source, (key, value) -> value + 1));
        }
        return counts;
    }

    @Override public Optional<ExpectedBand> expected(String sensor, String channel, double time, WindNow wind) {
        return Optional.empty(); // No trained normal-air artifact/runtime: a band is unavailable.
    }

    @Override public Wording write(WordingRequest request) {
        Ref target = request == null ? null : request.target();
        WordingPurpose purpose = request == null || request.purpose() == null ? WordingPurpose.EXPLANATION : request.purpose();
        boolean sms = purpose.name().startsWith("SMS_");
        String input = request == null ? null : request.publicText();
        boolean validRequest = request != null && request.purpose() != null && target != null;
        boolean safe = validRequest && safe(input, sms);
        String text = safe ? input : sms
                ? PREFIX + " investigation update unavailable. Please consult the recorded decision. Demo only."
                : "Plain wording unavailable. Consult the recorded evidence, decision and limitations.";
        List<WordingCheck> checks = new ArrayList<>();
        checks.add(new WordingCheck("public_input_safe", safe));
        checks.add(new WordingCheck("banned_words", BANNED.stream().noneMatch(word -> text.toLowerCase(Locale.ROOT).contains(word))));
        checks.add(new WordingCheck("no_private_input", !PRIVATE.matcher(text).find() && !ContactText.contains(text)));
        // The available path copies safe input exactly; it cannot invent numbers or alter a status.
        checks.add(new WordingCheck("numbers_match_input", safe ? text.equals(input) : text.chars().noneMatch(Character::isDigit)));
        checks.add(new WordingCheck("status_preserved", safe ? text.equals(input) : true));
        checks.add(new WordingCheck("length", !sms || text.length() <= 160));
        checks.add(new WordingCheck("no_sms_factory", !sms || !SOURCE.matcher(text).find()));
        return new Wording(target, purpose, text, WordingSource.TEMPLATE, null, "FP-WORD-1", checks,
                !validRequest ? "INVALID_REQUEST" : safe ? "EXTERNAL_PROVIDER_DISABLED" : "UNSAFE_PUBLIC_TEXT");
    }

    private static boolean safe(String text, boolean sms) {
        if (text == null || text.isBlank() || text.length() > 16384 || PRIVATE.matcher(text).find() || ContactText.contains(text)) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (BANNED.stream().anyMatch(lower::contains)) return false;
        if (text.chars().anyMatch(c -> c == 0x7f || c < 0x20 && c != '\n' && c != '\t')) return false;
        return !sms || text.length() <= 160 && text.startsWith(PREFIX) && !SOURCE.matcher(text).find()
                && text.chars().allMatch(c -> c >= 0x20 && c <= 0x7e);
    }
}
