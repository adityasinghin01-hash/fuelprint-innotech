package fuelprint.api;

/**
 * Spring event published once per saved record revision, only after its transaction committed
 * (CORE-API section 1). Listen with {@code @EventListener}; to change something, call {@code AutomationBus.submit}.
 *
 * @param recordType type of the saved record
 * @param recordId   its id
 * @param revision   the revision just saved
 * @param runId      its run, or null for records not tied to a run
 * @param actor      who saved it; {@code automation} for automatic steps
 * @param automatic  true only when the actor is {@code automation}
 */
public record RecordSaved(String recordType, String recordId, long revision, String runId, String actor,
                          boolean automatic) {
}
