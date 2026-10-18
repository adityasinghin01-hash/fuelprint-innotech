package fuelprint.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One analysis (AGREEMENT section 6, {@code analysis}, version 2): the physics part from {@link Ranker}
 * plus the learned part from {@link LearnedRanker}. The saved record adds the common fields.
 *
 * <p>Rules enforced here: the UNKNOWN row is always present exactly once; {@link #withLearned} can only
 * add an order of surviving registered explanations and never changes the outcome, a status or the
 * UNKNOWN row (AGREEMENT ground rule 9).
 *
 * @param inputWindowIds        the windows used
 * @param outcome               the outcome; nobody overrides it
 * @param qualification         MODEL_NOT_QUALIFIED or SYNTHETIC_SCOPE_ONLY
 * @param candidates            every explanation with its fit and status, UNKNOWN included
 * @param exclusions            windows not used, with reasons
 * @param limitations           plain-words limits of this analysis
 * @param explanationText       the deterministic plain-words explanation
 * @param algorithmVersion      like {@code FP-COMPAT-3}
 * @param learnedOrder          the learned order (empty until the learned ranker ran)
 * @param learnedStatus         null until the learned ranker ran
 * @param learnedModelVersionId the learned model_version used, or null
 * @param historyCounts         factory label to earlier runs where it survived, or null
 */
public record Analysis(List<String> inputWindowIds, Outcome outcome, Qualification qualification,
                       List<Candidate> candidates, List<Exclusion> exclusions, List<String> limitations,
                       String explanationText, String algorithmVersion, List<LearnedRank> learnedOrder,
                       LearnedStatus learnedStatus, String learnedModelVersionId,
                       Map<String, Integer> historyCounts) {

    public Analysis {
        inputWindowIds = List.copyOf(inputWindowIds);
        candidates = List.copyOf(candidates);
        exclusions = List.copyOf(exclusions);
        limitations = List.copyOf(limitations);
        learnedOrder = List.copyOf(learnedOrder);
        historyCounts = historyCounts == null ? null : Collections.unmodifiableMap(new TreeMap<>(historyCounts));
        if (outcome == null || qualification == null) {
            throw new IllegalArgumentException("outcome and qualification are required");
        }
        long unknownRows = candidates.stream().filter(Candidate::isUnknown).count();
        if (unknownRows != 1) {
            throw new IllegalArgumentException("The UNKNOWN row must be present exactly once");
        }
    }

    /** The physics part only, before the learned ranker ran. */
    public static Analysis physics(List<String> inputWindowIds, Outcome outcome, Qualification qualification,
                                   List<Candidate> candidates, List<Exclusion> exclusions, List<String> limitations,
                                   String explanationText, String algorithmVersion) {
        return new Analysis(inputWindowIds, outcome, qualification, candidates, exclusions, limitations,
                explanationText, algorithmVersion, List.of(), null, null, null);
    }

    /**
     * This analysis with the learned part added. Outcome, candidates and every status stay exactly as they are.
     *
     * @throws IllegalArgumentException if the order ranks UNKNOWN, a rejected or missing explanation, or
     *                                  orders anything when the outcome is INVALID or NO_SIGNAL
     */
    public Analysis withLearned(LearnedOrder learned) {
        boolean nothingToOrder = outcome == Outcome.INVALID || outcome == Outcome.NO_SIGNAL;
        if (nothingToOrder && learned.learnedStatus() != LearnedStatus.NOT_APPLICABLE) {
            throw new IllegalArgumentException("Outcome " + outcome + " needs learned_status NOT_APPLICABLE");
        }
        Set<String> rankable = candidates.stream()
                .filter(c -> !c.isUnknown() && c.status() == CandidateStatus.SURVIVES)
                .map(Candidate::explanationId)
                .collect(Collectors.toSet());
        for (LearnedRank rank : learned.learnedOrder()) {
            if (!rankable.contains(rank.explanationId())) {
                throw new IllegalArgumentException("Only surviving registered explanations can be ranked, not "
                        + rank.explanationId());
            }
        }
        return new Analysis(inputWindowIds, outcome, qualification, candidates, exclusions, limitations,
                explanationText, algorithmVersion, learned.learnedOrder(), learned.learnedStatus(),
                learned.learnedModelVersionId(), learned.historyCounts());
    }
}
