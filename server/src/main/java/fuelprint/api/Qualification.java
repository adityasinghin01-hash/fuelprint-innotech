package fuelprint.api;

/** How far an analysis may be trusted. Always one of these two in our builds. */
public enum Qualification {
    MODEL_NOT_QUALIFIED,
    SYNTHETIC_SCOPE_ONLY
}
