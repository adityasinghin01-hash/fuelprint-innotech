package fuelprint.api;

/**
 * Spring event after a run ended; field revokes leases and interrupts missions on it.
 *
 * @param runId  the run that ended
 * @param reason why it ended
 */
public record RunEnded(String runId, Reason reason) {

    /** Why a run ended. */
    public enum Reason {
        /** The scenario finished normally. */
        COMPLETED,
        /** The presenter stopped it. */
        ABORTED,
        /** A reset replaced it with a new run. */
        RESET,
        /** The server stopped while it was RUNNING (marked on the next start-up). */
        INTERRUPTED
    }
}
