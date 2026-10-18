package fuelprint.core.store;

/**
 * One saved event (AGREEMENT section 6, {@code event}). There is one event per saved record revision.
 * {@code cursor} goes up by one for every event and is never reused.
 *
 * @param cursor     position in the live stream
 * @param eventType  what happened, like {@code MISSION_APPROVED}
 * @param recordType type of the saved record
 * @param recordId   id of the saved record
 * @param revision   revision of the saved record
 * @param runId      run of the record, null for records not tied to a run
 * @param simTimeS   simulation time of the change, null when no run clock applies
 * @param wallUtc    wall time the event was saved
 * @param actor      who caused it; {@code automation} for automatic steps
 * @param commandId  the command that caused it, null if none
 */
public record EventRow(long cursor, String eventType, String recordType, String recordId, long revision,
                       String runId, Double simTimeS, String wallUtc, String actor, String commandId) {
}
