package fuelprint.core.store;

import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A new revision a handler wants saved. The store assigns {@code revision}, {@code created_utc} and
 * {@code created_by}; {@code fields} holds only the record's own fields (AGREEMENT section 6).
 *
 * @param type            record type, lower snake case, like {@code mission}
 * @param id              record UUID (server-made)
 * @param runId           run UUID, or null for records not tied to a run
 * @param basedOnRevision the latest revision the handler read (0 for a new record); the save fails if it changed
 * @param fields          the record's own fields; must not contain the common fields
 */
public record RecordChange(String type, String id, String runId, long basedOnRevision, ObjectNode fields) {

    /** Field names the store fills in itself. */
    public static final Set<String> COMMON_FIELDS = Set.of("id", "run_id", "revision", "created_utc", "created_by");

    public RecordChange {
        fields = fields == null ? null : fields.deepCopy();
    }

    /** A brand-new record (revision 1). */
    public static RecordChange create(String type, String id, String runId, ObjectNode fields) {
        return new RecordChange(type, id, runId, 0, fields);
    }

    /** The next revision of {@code current}, keeping its type, id and run. */
    public static RecordChange next(StoredRecord current, ObjectNode fields) {
        return new RecordChange(current.type(), current.id(), current.runId(), current.revision(), fields);
    }

    @Override
    public ObjectNode fields() {
        return fields == null ? null : fields.deepCopy();
    }

    public RecordKey key() {
        return new RecordKey(type, id);
    }
}
