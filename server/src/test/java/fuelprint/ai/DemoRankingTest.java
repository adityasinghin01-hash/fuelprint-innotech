package fuelprint.ai;

import fuelprint.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoRankingTest {
    private Candidate row(String id, Double fit, CandidateStatus status) {
        return new Candidate(id, id.equals("UNKNOWN") || id.equals("BACKGROUND") ? List.of() : List.of(id),
                fit, status, id.equals("UNKNOWN") ? "Unregistered source remains possible" : "Saved reason " + id);
    }
    private Analysis analysis(List<Candidate> rows) {
        return Analysis.physics(List.of("window-1"), Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE,
                Qualification.SYNTHETIC_SCOPE_ONLY, rows, List.of(), List.of("Synthetic scope only"),
                "Several explanations fit", "FP-COMPAT-3/4")
                .withLearned(LearnedOrder.none(LearnedStatus.MODEL_MISSING, null, Map.of("S01", 3)));
    }
    private List<String> ids(Analysis a) { return a.candidates().stream().map(Candidate::explanationId).toList(); }

    @Test void ordersOnlyRegisteredSurvivorsAndKeepsOtherRowsInRelativeOrder() {
        var before = analysis(List.of(row("S02", 2., CandidateStatus.SURVIVES),
                row("UNKNOWN", null, CandidateStatus.SURVIVES), row("S03", .1, CandidateStatus.REJECTED),
                row("BACKGROUND", .01, CandidateStatus.SURVIVES), row("S01", 1., CandidateStatus.SURVIVES)));
        assertEquals(List.of("S01", "S02", "UNKNOWN", "S03", "BACKGROUND"), ids(DemoRanking.orderForDemo(before)));
    }
    @Test void preservesAllFieldsAndEveryCandidateInstanceWithoutMutatingInput() {
        var rows = List.of(row("S02", 2., CandidateStatus.SURVIVES), row("UNKNOWN", null, CandidateStatus.SURVIVES),
                row("S01", 1., CandidateStatus.SURVIVES));
        var before = analysis(rows);
        var after = DemoRanking.orderForDemo(before);
        assertEquals(rows, before.candidates());
        assertEquals(before, new Analysis(after.inputWindowIds(), after.outcome(), after.qualification(), rows,
                after.exclusions(), after.limitations(), after.explanationText(), after.algorithmVersion(),
                after.learnedOrder(), after.learnedStatus(), after.learnedModelVersionId(), after.historyCounts()));
        for (Candidate c : rows) assertSame(c, after.candidates().stream().filter(x -> x.explanationId().equals(c.explanationId())).findFirst().orElseThrow());
        assertEquals(LearnedStatus.MODEL_MISSING, after.learnedStatus());
        assertTrue(after.learnedOrder().isEmpty()); assertNull(after.learnedModelVersionId());
        assertThrows(UnsupportedOperationException.class, () -> after.candidates().clear());
    }
    @Test void finiteFitsComeBeforeMissingAndNonfiniteWithDeterministicIdTies() {
        var before = analysis(List.of(row("S06", Double.NaN, CandidateStatus.SURVIVES),
                row("S05", null, CandidateStatus.SURVIVES), row("S04", Double.NEGATIVE_INFINITY, CandidateStatus.SURVIVES),
                row("S02", 1., CandidateStatus.SURVIVES), row("S01", 1., CandidateStatus.SURVIVES),
                row("S03", Double.POSITIVE_INFINITY, CandidateStatus.SURVIVES), row("UNKNOWN", null, CandidateStatus.SURVIVES)));
        assertEquals(List.of("S01", "S02", "S03", "S04", "S05", "S06", "UNKNOWN"), ids(DemoRanking.orderForDemo(before)));
    }
    @Test void survivorOrderDoesNotDependOnInputPermutation() {
        var rows = new ArrayList<>(List.of(row("S03", 3., CandidateStatus.SURVIVES), row("S02", 1., CandidateStatus.SURVIVES),
                row("S01", 1., CandidateStatus.SURVIVES), row("UNKNOWN", null, CandidateStatus.SURVIVES)));
        for (int seed = 0; seed < 50; seed++) {
            Collections.shuffle(rows, new Random(seed));
            assertEquals(List.of("S01", "S02", "S03", "UNKNOWN"), ids(DemoRanking.orderForDemo(analysis(rows))));
        }
    }
    @Test void invalidAndNoSignalReturnOriginalObject() {
        for (Outcome outcome : List.of(Outcome.INVALID, Outcome.NO_SIGNAL)) {
            var before = Analysis.physics(List.of(), outcome, Qualification.MODEL_NOT_QUALIFIED,
                    List.of(row("UNKNOWN", null, CandidateStatus.SURVIVES)), List.of(), List.of("No usable signal"),
                    "Unavailable", "FP-COMPAT-3").withLearned(LearnedOrder.none(LearnedStatus.NOT_APPLICABLE, null, null));
            assertSame(before, DemoRanking.orderForDemo(before));
        }
    }
    @Test void failedLearnedMetadataIsNeverConvertedToSuccess() {
        var before = analysis(List.of(row("UNKNOWN", null, CandidateStatus.SURVIVES), row("S01", 1., CandidateStatus.SURVIVES)))
                .withLearned(LearnedOrder.none(LearnedStatus.FAILED, "existing-failed-model-ref", Map.of("S01", 5)));
        var after = DemoRanking.orderForDemo(before);
        assertEquals(LearnedStatus.FAILED, after.learnedStatus()); assertTrue(after.learnedOrder().isEmpty());
        assertEquals("existing-failed-model-ref", after.learnedModelVersionId()); assertEquals(Map.of("S01", 5), after.historyCounts());
    }
    @Test void orderingIsIdempotent() {
        var before = analysis(List.of(row("UNKNOWN", null, CandidateStatus.SURVIVES), row("S02", 2., CandidateStatus.SURVIVES), row("S01", 1., CandidateStatus.SURVIVES)));
        assertEquals(DemoRanking.orderForDemo(before), DemoRanking.orderForDemo(DemoRanking.orderForDemo(before)));
    }
}
