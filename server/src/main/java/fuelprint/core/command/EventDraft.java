package fuelprint.core.command;

/**
 * The event to save for one record in a {@link HandlerResult}.
 *
 * @param eventType  upper snake case, like {@code MISSION_APPROVED}
 * @param recordType type of a record in the same result
 * @param recordId   id of that record
 * @param simTimeS   simulation time of the change, or null
 */
public record EventDraft(String eventType, String recordType, String recordId, Double simTimeS) {
}
