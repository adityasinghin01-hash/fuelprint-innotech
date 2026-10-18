package fuelprint.api;

/** State of the learned order in an analysis (AGREEMENT section 6). */
public enum LearnedStatus {
    /** The learned ranker ordered the surviving explanations. */
    OK,
    /** No active learned model; no order. */
    MODEL_MISSING,
    /** Outcome INVALID or NO_SIGNAL: nothing to order. */
    NOT_APPLICABLE,
    /** The model failed; no order. */
    FAILED
}
