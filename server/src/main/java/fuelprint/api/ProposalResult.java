package fuelprint.api;

/** Whether the planner found a useful measurement. */
public enum ProposalResult {
    BEST_ACTION,
    NO_USEFUL_MEASUREMENT,
    /** The planner used its 2 s budget; no best or cheaper action and no recommendation. */
    PENDING
}
