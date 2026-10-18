package fuelprint.core.command;

/** Reason codes the core uses in receipts. Handlers may use their own upper snake case codes too. */
public final class ReasonCode {

    /** {@code expected_revision} is not the latest revision of the subject record. */
    public static final String STALE_REVISION = "STALE_REVISION";
    /** The session does not hold a role the command allows (or is not signed in, or is automation where refused). */
    public static final String NOT_ALLOWED = "NOT_ALLOWED";
    /** The command is malformed, unknown, or reuses a command_id with a different body. */
    public static final String INVALID = "INVALID";
    /** The server failed while running the command. Nothing was saved; sending it again is safe. */
    public static final String SERVER_ERROR = "SERVER_ERROR";

    private ReasonCode() {
    }
}
