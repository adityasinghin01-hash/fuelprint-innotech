package fuelprint.api;

/**
 * One possible measurement in a proposal (AGREEMENT section 6, {@code proposal.actions[]}).
 *
 * @param actionId        like {@code A-AER-0-100}
 * @param kind            like {@code DISCRIMINATE}
 * @param vehicle         {@code DRONE} or {@code VAN}
 * @param target          where to measure, ENU metres
 * @param score           planner score, or null
 * @param completeMinutes minutes to complete, or null when not feasible
 * @param feasible        whether it can be done
 * @param rejectionReason why not, or null
 */
public record ProposalAction(String actionId, String kind, String vehicle, Enu target, Double score,
                             Double completeMinutes, boolean feasible, String rejectionReason) {
}
