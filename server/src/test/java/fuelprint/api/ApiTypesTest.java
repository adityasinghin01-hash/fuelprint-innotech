package fuelprint.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * The api records follow AGREEMENT section 6: each version 2 fixture in {@code apps/shared/fixtures/}
 * reads into its Java type with no unknown field and writes back to the same JSON.
 */
class ApiTypesTest {

    /** Same JSON settings as the server (application.properties). */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    static final List<String> COMMON = List.of("id", "run_id", "revision", "created_utc", "created_by");

    static Path fixtures() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("apps/shared/fixtures");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("apps/shared/fixtures not found");
    }

    static JsonNode fixture(String name) throws IOException {
        return MAPPER.readTree(fixtures().resolve(name).toFile());
    }

    static ObjectNode withoutCommonFields(JsonNode record) {
        ObjectNode copy = record.deepCopy();
        COMMON.forEach(copy::remove);
        return copy;
    }

    /** Reads {@code json} as {@code type}, writes it back and checks nothing was lost or renamed. */
    static <T> T roundTrip(JsonNode json, Class<T> type) throws IOException {
        T value = MAPPER.treeToValue(json, type);
        JsonNode back = MAPPER.valueToTree(value);
        assertThat(normalised(back)).as(type.getSimpleName()).isEqualTo(normalised(json));
        return value;
    }

    /** Numbers compared by value (0 equals 0.0). */
    static JsonNode normalised(JsonNode node) {
        if (node.isNumber()) {
            return JsonNodeFactory.instance.numberNode(new BigDecimal(node.asText()).stripTrailingZeros());
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), normalised(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(normalised(item)));
            return out;
        }
        return node;
    }

    @Test
    void analysisFixtureMatchesTheAnalysisRecord() throws IOException {
        Analysis analysis = roundTrip(withoutCommonFields(fixture("analysis.json")), Analysis.class);
        assertThat(analysis.outcome()).isEqualTo(Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE);
        assertThat(analysis.learnedOrder()).extracting(LearnedRank::rank).containsExactly(1, 2, 3);
        assertThat(analysis.candidates()).filteredOn(Candidate::isUnknown).singleElement()
                .satisfies(c -> assertThat(c.fit()).isNull());
    }

    @Test
    void proposalTriggerReadingAndWordingFixturesMatch() throws IOException {
        Proposal proposal = roundTrip(withoutCommonFields(fixture("proposal.json")), Proposal.class);
        assertThat(proposal.actions().get(2).completeMinutes()).isNull();
        Trigger trigger = roundTrip(withoutCommonFields(fixture("trigger.json")), Trigger.class);
        assertThat(trigger.intervals()).containsExactly(new Interval(397.0, 412.0));
        assertThat(trigger.rule()).isEqualTo(Trigger.TEACH_DETECT_1);
        roundTrip(withoutCommonFields(fixture("reading.json")), Reading.class);
        Wording wording = roundTrip(withoutCommonFields(fixture("wording.json")), Wording.class);
        assertThat(wording.target().type()).isEqualTo("analysis");
    }

    @Test
    void attemptChannelsAndTickWindMatch() throws IOException {
        JsonNode channel = fixture("attempt.json").path("channels").get(0);
        ChannelVerdict verdict = roundTrip(channel, ChannelVerdict.class);
        assertThat(verdict.volumeL()).isEqualTo(0.25);
        VehicleState vehicle = roundTrip(fixture("tick.json").path("payload").path("vehicles").get(0), VehicleState.class);
        assertThat(vehicle.pilot()).isEqualTo(Pilot.VISITOR);
        assertThat(vehicle.pose().yawDeg()).isEqualTo(90);
        assertThat(vehicle.leaseHolder()).isEqualTo("visitor-7f3a");
        WindNow wind = roundTrip(fixture("tick.json").path("payload").path("wind"), WindNow.class);
        assertThat(wind).isEqualTo(new WindNow(270, 3.0));
    }

    @Test
    void windowFixtureMatchesExceptTheMissingSensorField() throws IOException {
        ObjectNode window = withoutCommonFields(fixture("window.json"));
        window.put("id", fixture("window.json").path("id").asText());
        window.putNull("sensor_or_vehicle_id"); // not in AGREEMENT section 6; see the P1 report
        roundTrip(window, Window.class);
    }

    @Test
    void coreHookTypesMatchCoreApi() {
        assertThat(RunEnded.Reason.values()).extracting(Enum::name)
                .containsExactly("COMPLETED", "ABORTED", "RESET", "INTERRUPTED");
        JsonNode saved = MAPPER.valueToTree(new RecordSaved("mission", "m1", 2, null, "automation", true));
        assertThat(saved.fieldNames()).toIterable()
                .containsExactly("record_type", "record_id", "revision", "run_id", "actor", "automatic");
        JsonNode run = MAPPER.valueToTree(new RunContext("r1", "SC1-NIGHT-PLUME", "BLIND", 7));
        assertThat(run.fieldNames()).toIterable().containsExactly("run_id", "preset_id", "mode", "seed");
        assertThat(java.util.List.of(AutomationSettings.AUTO_PROPOSE, AutomationSettings.AUTO_AUTOPILOT,
                AutomationSettings.AUTO_CAPTURE, AutomationSettings.AUTO_SEAL, AutomationSettings.AUTO_ALERTS,
                AutomationSettings.AUTO_RETRAIN)).allMatch(name -> fixtureHas("automation_settings.json", name));
    }

    static boolean fixtureHas(String file, String field) {
        try {
            return fixture(file).has(field);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anomalyBandFieldsUseTheContractNames() {
        JsonNode band = MAPPER.valueToTree(new ExpectedBand(2.1, 9.6, "m1"));
        assertThat(band.fieldNames()).toIterable().containsExactly("expected_low", "expected_high", "model_version_id");
    }

    // --- AGREEMENT ground rule 9: the learned ranker only orders survivors ---

    static Analysis physics(Outcome outcome) {
        return Analysis.physics(List.of("w1"), outcome, Qualification.SYNTHETIC_SCOPE_ONLY, List.of(
                        new Candidate("S02", List.of("S02"), 0.94, CandidateStatus.SURVIVES, null),
                        new Candidate("S01", List.of("S01"), 1.07, CandidateStatus.SURVIVES, null),
                        new Candidate("S03", List.of("S03"), 9.8, CandidateStatus.REJECTED, "RESIDUAL_ABOVE_CUTOFF"),
                        new Candidate("UNKNOWN", List.of(), null, CandidateStatus.SURVIVES, "UNREGISTERED_SOURCES_NOT_EXCLUDED")),
                List.of(), List.of("Synthetic model only"), "text", "FP-COMPAT-3");
    }

    static LearnedOrder order(String... ids) {
        List<LearnedRank> ranks = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            ranks.add(new LearnedRank(ids[i], i + 1, 2.0 - i));
        }
        return new LearnedOrder(ranks, LearnedStatus.OK, "model-1", Map.of("S02", 3, "S01", 1));
    }

    @Test
    void theLearnedOrderChangesNoOutcomeAndNoStatus() {
        Analysis before = physics(Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE);
        Analysis after = before.withLearned(order("S01", "S02"));

        assertThat(after.outcome()).isEqualTo(before.outcome());
        assertThat(after.candidates()).isEqualTo(before.candidates());
        assertThat(after.learnedOrder()).extracting(LearnedRank::explanationId).containsExactly("S01", "S02");
        assertThat(after.learnedStatus()).isEqualTo(LearnedStatus.OK);
        assertThat(after.historyCounts()).containsEntry("S02", 3);
    }

    @Test
    void theLearnedOrderCannotRankUnknownOrARejectedOrMissingExplanation() {
        Analysis analysis = physics(Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE);
        assertThatThrownBy(() -> analysis.withLearned(order("S02", "UNKNOWN"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analysis.withLearned(order("S03"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analysis.withLearned(order("S05"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidAndNoSignalOutcomesTakeNoOrder() {
        for (Outcome outcome : List.of(Outcome.INVALID, Outcome.NO_SIGNAL)) {
            Analysis analysis = physics(outcome);
            assertThatThrownBy(() -> analysis.withLearned(order("S02"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> analysis.withLearned(LearnedOrder.none(LearnedStatus.MODEL_MISSING, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
            Analysis ok = analysis.withLearned(LearnedOrder.none(LearnedStatus.NOT_APPLICABLE, null, null));
            assertThat(ok.learnedOrder()).isEmpty();
        }
    }

    @Test
    void learnedOrderRanksAreOneTwoThreeWithNoRepeatsAndOnlyOkHasAnOrder() {
        assertThatThrownBy(() -> new LearnedOrder(List.of(new LearnedRank("S02", 2, 1.0)), LearnedStatus.OK, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LearnedOrder(List.of(new LearnedRank("S02", 1, 1.0), new LearnedRank("S02", 2, 0.5)),
                LearnedStatus.OK, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LearnedOrder(List.of(new LearnedRank("S02", 1, 1.0)), LearnedStatus.FAILED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theUnknownRowIsAlwaysPresentExactlyOnce() {
        assertThatThrownBy(() -> Analysis.physics(List.of(), Outcome.INVALID, Qualification.MODEL_NOT_QUALIFIED,
                List.of(new Candidate("S02", List.of("S02"), 1.0, CandidateStatus.SURVIVES, null)),
                List.of(), List.of(), "t", "v")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiTypesAreImmutable() {
        List<String> quality = new java.util.ArrayList<>(List.of("OK"));
        Reading reading = new Reading("s", "SO2_ppb", null, null, "ppb", 1.0, quality, Origin.SYNTHETIC_TEACHING);
        quality.add("CHANGED");
        assertThat(reading.quality()).containsExactly("OK");
        assertThatThrownBy(() -> reading.quality().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(reading.value()).as("unknown stays null, never zero").isNull();
    }
}
