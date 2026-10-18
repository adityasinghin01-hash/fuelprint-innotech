package fuelprint.core.command;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One command (AGREEMENT section 3). The sender is never part of the command: it comes from the
 * {@link Session}. {@code payload} may hold private values (a phone number); never log or broadcast it.
 *
 * @param schemaVersion    always {@code fp/1}
 * @param commandId        UUID made by the browser (or by the server for automation), used once
 * @param commandType      like {@code approve_mission}
 * @param runId            the run it applies to, or null
 * @param expectedRevision the revision of the command's subject record the sender last saw, or null
 * @param payload          the command's own fields
 */
public record Command(String schemaVersion, String commandId, String commandType, String runId,
                      Long expectedRevision, ObjectNode payload) {

    public static final String SCHEMA_VERSION = "fp/1";

    public Command {
        payload = payload == null ? null : payload.deepCopy();
    }

    /** A copy of the payload; changing it does not change the command. */
    @Override
    public ObjectNode payload() {
        return payload == null ? null : payload.deepCopy();
    }
}
