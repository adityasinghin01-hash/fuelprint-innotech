package fuelprint.science.detect;

/** Where the fixed alarm rule TEACH-DETECT-1 stands. */
public enum DetectionStatus {
    /** No valid 30 s background no older than 180 s: the rule cannot judge anything yet. */
    BACKGROUND_REQUIRED,
    /** A background exists and nothing is rising. */
    MONITORING,
    /** Valid enhanced seconds are counting but have not reached 15 in a row. */
    ENHANCING,
    /** The rule fired on this call. */
    TRIGGERED
}
