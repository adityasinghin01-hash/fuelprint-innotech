package fuelprint.core.command;

import java.util.Set;

import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;

/**
 * Runs one {@code command_type}. Register an implementation as a Spring bean; the {@link HandlerRegistry}
 * finds it. A handler only reads ({@link StoreView}) and returns what to save; the {@link CommandBus}
 * checks the role, the revision and idempotency first and saves the result in one transaction.
 */
public interface CommandHandler {

    /** The command type this handler runs, lower snake case, like {@code approve_mission}. */
    String commandType();

    /**
     * The roles allowed to send this command (AGREEMENT section 5). Include {@link Role#AUTOMATION} only
     * where section 5 lists {@code automation}; the five human-only commands may never include it.
     */
    Set<Role> allowedRoles();

    /**
     * The record that {@code expected_revision} refers to, or null if this command takes no expected
     * revision. A command that names a subject and sends {@code expected_revision} is REJECTED
     * {@code STALE_REVISION} unless it equals the subject's latest revision (0 if it does not exist yet).
     * A command that sends {@code expected_revision} to a handler without a subject is REJECTED INVALID.
     * Return null if the payload is malformed; {@link #handle} then rejects it.
     */
    default RecordKey revisionSubject(Command command) {
        return null;
    }

    /**
     * Payload fields holding private values (like {@code phone_number}). The commands table keeps only a
     * keyed digest of them, never the values (AGREEMENT ground rule 10).
     */
    default Set<String> privatePayloadFields() {
        return Set.of();
    }

    /**
     * Decides the command. Runs on the single command thread inside the bus transaction. Never write to
     * the database here and never block on another command. Throwing an exception saves nothing.
     */
    HandlerOutcome handle(Command command, Session session, StoreView store);
}
