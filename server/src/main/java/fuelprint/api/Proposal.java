package fuelprint.api;

import java.util.List;

/**
 * The planner's answer (AGREEMENT section 6, {@code proposal}). The saved record adds the common fields.
 *
 * @param analysisId      the analysis it plans for
 * @param actions         every action considered, feasible or not
 * @param result          BEST_ACTION or NO_USEFUL_MEASUREMENT
 * @param bestActionId    the best action, or null
 * @param cheaperActionId a cheaper useful action, or null
 */
public record Proposal(String analysisId, List<ProposalAction> actions, ProposalResult result,
                       String bestActionId, String cheaperActionId) {

    public Proposal {
        actions = List.copyOf(actions);
    }
}
