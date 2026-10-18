package fuelprint.api;

/** What happened to one SMS. */
public enum SendStatus {
    /** The gateway accepted it. */
    SENT,
    /** LOG_ONLY mode: written to the log, not sent. */
    LOGGED_ONLY,
    /** The gateway failed after its retries. */
    FAILED
}
