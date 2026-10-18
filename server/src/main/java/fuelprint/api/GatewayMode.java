package fuelprint.api;

/** How the SMS gateway runs. LOG_ONLY is the default and is used in tests and fake mode. */
public enum GatewayMode {
    /** Sends through the stall phone's SMS gateway. */
    REAL,
    /** Writes the message to the log and the screen; nothing is sent. */
    LOG_ONLY
}
