package fuelprint.api;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What {@link LearnedRanker} returns (AGREEMENT section 9). Ranks are 1..n in order with no gaps and no
 * repeated explanation; any status other than OK has an empty order.
 *
 * @param learnedOrder          the order of the surviving registered explanations
 * @param learnedStatus         OK, MODEL_MISSING, NOT_APPLICABLE or FAILED
 * @param learnedModelVersionId the model_version used, or null
 * @param historyCounts         factory label to the number of earlier runs where it survived, or null
 */
public record LearnedOrder(List<LearnedRank> learnedOrder, LearnedStatus learnedStatus,
                           String learnedModelVersionId, Map<String, Integer> historyCounts) {

    public LearnedOrder {
        learnedOrder = List.copyOf(learnedOrder);
        if (learnedStatus == null) {
            throw new IllegalArgumentException("learned_status is required");
        }
        if (learnedStatus != LearnedStatus.OK && !learnedOrder.isEmpty()) {
            throw new IllegalArgumentException("Only status OK may carry an order");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < learnedOrder.size(); i++) {
            LearnedRank rank = learnedOrder.get(i);
            if (rank.rank() != i + 1) {
                throw new IllegalArgumentException("Ranks must be 1, 2, 3 ... in order");
            }
            if (!seen.add(rank.explanationId())) {
                throw new IllegalArgumentException("Explanation ranked twice: " + rank.explanationId());
            }
        }
        historyCounts = historyCounts == null ? null : Collections.unmodifiableMap(new TreeMap<>(historyCounts));
    }

    /** No order, with a non-OK status (for example MODEL_MISSING or NOT_APPLICABLE). */
    public static LearnedOrder none(LearnedStatus status, String modelVersionId, Map<String, Integer> historyCounts) {
        return new LearnedOrder(List.of(), status, modelVersionId, historyCounts);
    }
}
