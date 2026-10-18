package fuelprint.core.store;

/** Points at one saved record revision, as in a receipt's {@code record_refs} (AGREEMENT section 3). */
public record RecordRef(String type, String id, long revision) {
}
