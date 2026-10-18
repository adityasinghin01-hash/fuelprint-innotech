package fuelprint.api;

/**
 * One place in the learned order. Screens show {@code rank}, never {@code orderScore}.
 *
 * @param explanationId a surviving registered explanation
 * @param rank          1, 2, 3 ...
 * @param orderScore    an ordering number, not a probability
 */
public record LearnedRank(String explanationId, int rank, double orderScore) {
}
