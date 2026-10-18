package fuelprint.core.command;

/**
 * A command exactly as it was decided in a recording (fake replay mode), with what it saved.
 *
 * @param commandId   the command id (remapped per replay loop)
 * @param commandType like {@code open_investigation}
 * @param runId       its run, or null
 * @param actor       who sent it, like {@code automation} or {@code supervisor@laptop-1}
 * @param status      APPLIED or REJECTED
 * @param reasonCode  why it was rejected, or null
 * @param reasonText  plain words, or null
 * @param result      the records and events it saved (empty when rejected)
 */
public record RecordedCommand(String commandId, String commandType, String runId, String actor,
                              Receipt.Status status, String reasonCode, String reasonText, HandlerResult result) {
}
