package fuelprint.api;

/** Where a reading comes from (AGREEMENT ground rule 6). The two are never mixed in one calculation. */
public enum Origin {
    /** Simulated teaching data (department and finale runs). */
    SYNTHETIC_TEACHING,
    /** The optional bench sensor (finale only). */
    PHYSICAL_BENCH
}
