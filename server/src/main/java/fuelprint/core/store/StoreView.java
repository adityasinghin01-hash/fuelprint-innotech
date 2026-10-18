package fuelprint.core.store;

import java.util.List;
import java.util.Optional;

/** Read-only view of saved records. Command handlers get this; only the command bus writes. */
public interface StoreView {

    /** The latest revision of one record, if it exists. */
    Optional<StoredRecord> get(String type, String id);

    /** One exact revision of a record, if it exists. */
    Optional<StoredRecord> get(String type, String id, long revision);

    /**
     * The latest revision of every record of {@code type} in run {@code runId}, oldest record first.
     * {@code runId == null} lists the records not tied to a run.
     */
    List<StoredRecord> list(String type, String runId);

    /** The latest revision number of a record, or 0 if it does not exist. */
    long latestRevision(String type, String id);
}
