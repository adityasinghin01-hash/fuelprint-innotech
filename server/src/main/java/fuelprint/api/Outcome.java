package fuelprint.api;

/** Analysis outcomes (AGREEMENT section 6). Science sets them; nobody overrides them. */
public enum Outcome {
    /** Not enough usable data to analyse. */
    INVALID,
    /** Usable data, but nothing above the floor. This does NOT mean the factories are clean. */
    NO_SIGNAL,
    /** None of the registered factories explains the data. Maybe an unregistered source. */
    UNKNOWN_OR_MISMATCH,
    /** More than one explanation still fits. */
    AMBIGUOUS_OR_OPTIONAL_MIXTURE,
    /** One registered factory explains the data best, inside this synthetic model only. */
    PROVISIONAL_SINGLE
}
