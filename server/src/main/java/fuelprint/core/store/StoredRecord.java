package fuelprint.core.store;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One saved record revision. {@code body} is the complete record JSON (AGREEMENT section 6): the common
 * fields {@code id}, {@code run_id}, {@code revision}, {@code created_utc}, {@code created_by} followed by
 * the record's own fields.
 */
public record StoredRecord(String type, String id, long revision, String runId, String createdUtc,
                           String createdBy, ObjectNode body) {

    /** A copy of the record JSON; changing it does not change the stored record. */
    @Override
    public ObjectNode body() {
        return body.deepCopy();
    }

    public RecordRef ref() {
        return new RecordRef(type, id, revision);
    }

    public RecordKey key() {
        return new RecordKey(type, id);
    }
}
