package fuelprint.ai;

import fuelprint.api.Analysis;
import fuelprint.api.Candidate;
import fuelprint.api.CandidateStatus;
import fuelprint.api.Outcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Presentation of public physics facts only; never a learned model or a science decision. */
public final class DemoRanking {
    private DemoRanking() {}

    public static Analysis orderForDemo(Analysis input) {
        Objects.requireNonNull(input, "analysis");
        if (input.outcome() == Outcome.INVALID || input.outcome() == Outcome.NO_SIGNAL) return input;
        List<Candidate> registered = new ArrayList<>();
        List<Candidate> other = new ArrayList<>();
        for (Candidate candidate : input.candidates()) {
            if (!candidate.isUnknown() && !candidate.sources().isEmpty()
                    && candidate.status() == CandidateStatus.SURVIVES) registered.add(candidate);
            else other.add(candidate);
        }
        registered.sort(Comparator.comparingDouble(DemoRanking::displayFit)
                .thenComparing(Candidate::explanationId, Comparator.nullsLast(Comparator.naturalOrder())));
        registered.addAll(other);
        return new Analysis(input.inputWindowIds(), input.outcome(), input.qualification(), registered,
                input.exclusions(), input.limitations(), input.explanationText(), input.algorithmVersion(),
                input.learnedOrder(), input.learnedStatus(), input.learnedModelVersionId(), input.historyCounts());
    }

    private static double displayFit(Candidate candidate) {
        return candidate.fit() != null && Double.isFinite(candidate.fit())
                ? candidate.fit() : Double.POSITIVE_INFINITY;
    }
}
