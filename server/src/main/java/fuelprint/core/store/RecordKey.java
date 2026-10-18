package fuelprint.core.store;

/** Identifies one record (all its revisions): record type plus record id. */
public record RecordKey(String type, String id) {
}
