package fuelprint.core.store;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import fuelprint.core.Ids;
import fuelprint.core.WallTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Append-only record store (AGREEMENT section 1 rule 2). A change is always a new revision row;
 * no row is ever updated or deleted (the database refuses it too). Reads use the latest-revision view.
 *
 * <p>Writes are meant to run inside the command bus transaction. Use the {@code CommandBus}; do not
 * call {@link #save} from your own code.
 */
@Component
public class RecordStore implements StoreView {

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final String COLUMNS = "type, id, revision, run_id, created_utc, created_by, body";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    /** Reads saved bodies; a number a double cannot hold keeps every digit (see {@link ExactNumbers}). */
    private final ObjectReader bodyReader;
    private final Clock clock;
    private final RowMapper<StoredRecord> rowMapper;

    public RecordStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.bodyReader = exactReader(mapper);
        this.clock = clock;
        this.rowMapper = this::mapRow;
    }

    /**
     * A reader that behaves exactly like the mapper's default for every ordinary number (a decimal a double
     * reproduces is read as a double, as always), but keeps a BigDecimal with every digit for a number a double
     * would round (for example 20.0000000000000001) or flush to zero (for example 5e-324 or 1e-400).
     * Used for command bodies and saved records, so exact checks see what was really written.
     */
    public static ObjectReader exactReader(ObjectMapper mapper) {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).with(ExactNumbers.INSTANCE);
    }

    /** Node factory: double where a double holds the written value exactly, otherwise the exact BigDecimal. */
    static final class ExactNumbers extends JsonNodeFactory {
        private static final long serialVersionUID = 1L;
        static final ExactNumbers INSTANCE = new ExactNumbers();

        private ExactNumbers() {
            super(true);
        }

        @Override
        public ValueNode numberNode(BigDecimal v) {
            double d = v.doubleValue();
            if (Double.isFinite(d) && (d != 0 || v.signum() == 0) && new BigDecimal(Double.toString(d)).compareTo(v) == 0) {
                return DoubleNode.valueOf(d);
            }
            return DecimalNode.valueOf(v);
        }
    }

    @Override
    public Optional<StoredRecord> get(String type, String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM latest_records WHERE type = ? AND id = ?", rowMapper, type, id)
                .stream().findFirst();
    }

    @Override
    public Optional<StoredRecord> get(String type, String id, long revision) {
        return jdbc.query("SELECT " + COLUMNS + " FROM records WHERE type = ? AND id = ? AND revision = ?",
                rowMapper, type, id, revision).stream().findFirst();
    }

    @Override
    public List<StoredRecord> list(String type, String runId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM latest_records WHERE type = ? AND run_id IS ? ORDER BY first_row_id",
                rowMapper, type, runId);
    }

    @Override
    public List<StoredRecord> listAll(String type) {
        return jdbc.query("SELECT " + COLUMNS + " FROM latest_records WHERE type = ? ORDER BY first_row_id", rowMapper, type);
    }

    /**
     * The latest revision of every record in run {@code runId} (or, for null, every record not tied to a run),
     * oldest record first, leaving out the types in {@code excludedTypes}.
     */
    public List<StoredRecord> listRun(String runId, java.util.Collection<String> excludedTypes) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM latest_records WHERE run_id IS ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(runId);
        for (String excluded : excludedTypes) {
            sql.append(" AND type <> ?");
            args.add(excluded);
        }
        sql.append(" ORDER BY first_row_id");
        return jdbc.query(sql.toString(), rowMapper, args.toArray());
    }

    /** The newest {@code limit} records of {@code type} in run {@code runId}, newest first (all revision 1 rows). */
    public List<StoredRecord> newest(String type, String runId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM records WHERE type = ? AND run_id IS ? ORDER BY row_id DESC LIMIT ?",
                rowMapper, type, runId, limit);
    }

    /** Every revision of one record, oldest first. */
    public List<StoredRecord> revisions(String type, String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM records WHERE type = ? AND id = ? ORDER BY revision",
                rowMapper, type, id);
    }

    @Override
    public long latestRevision(String type, String id) {
        Long latest = jdbc.queryForObject("SELECT MAX(revision) FROM records WHERE type = ? AND id = ?",
                Long.class, type, id);
        return latest == null ? 0 : latest;
    }

    /**
     * Saves {@code change} as revision {@code basedOnRevision + 1}, only if {@code basedOnRevision} is still
     * the latest revision.
     *
     * @param actor     who made the change ({@code created_by}); {@code automation} for automatic steps
     * @param commandId the command that made it, or null
     * @throws StaleRevisionException   if the record changed since {@code basedOnRevision}
     * @throws IllegalArgumentException if the change is malformed
     */
    public StoredRecord save(RecordChange change, String actor, String commandId) {
        validate(change, actor);
        RecordKey key = change.key();
        long latest = latestRevision(key.type(), key.id());
        if (latest != change.basedOnRevision()) {
            throw new StaleRevisionException(key, change.basedOnRevision(), latest);
        }
        if (latest > 0) {
            String existingRun = get(key.type(), key.id()).map(StoredRecord::runId).orElse(null);
            if (!Objects.equals(existingRun, change.runId())) {
                throw new IllegalArgumentException("A record cannot move to another run: " + key);
            }
        }
        long revision = latest + 1;
        String createdUtc = WallTime.now(clock);

        ObjectNode body = mapper.createObjectNode();
        body.put("id", key.id());
        body.put("run_id", change.runId());
        body.put("revision", revision);
        body.put("created_utc", createdUtc);
        body.put("created_by", actor);
        body.setAll(change.fields());

        jdbc.update("INSERT INTO records (type, id, revision, run_id, created_utc, created_by, command_id, body) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                key.type(), key.id(), revision, change.runId(), createdUtc, actor, commandId, write(body));
        return new StoredRecord(key.type(), key.id(), revision, change.runId(), createdUtc, actor, body);
    }

    private static void validate(RecordChange change, String actor) {
        if (change == null) {
            throw new IllegalArgumentException("change is required");
        }
        if (change.type() == null || !TYPE.matcher(change.type()).matches()) {
            throw new IllegalArgumentException("Record type must be lower snake case: " + change.type());
        }
        if (!Ids.isUuid(change.id())) {
            throw new IllegalArgumentException("Record id must be a UUID: " + change.id());
        }
        if (change.runId() != null && !Ids.isUuid(change.runId())) {
            throw new IllegalArgumentException("run_id must be a UUID or null: " + change.runId());
        }
        if (change.basedOnRevision() < 0) {
            throw new IllegalArgumentException("basedOnRevision must be 0 or more");
        }
        if (change.fields() == null) {
            throw new IllegalArgumentException("fields are required (use an empty object)");
        }
        for (String common : RecordChange.COMMON_FIELDS) {
            if (change.fields().has(common)) {
                throw new IllegalArgumentException("The store sets '" + common + "' itself");
            }
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }

    private String write(ObjectNode body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Record body cannot be written as JSON", e);
        }
    }

    private StoredRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        ObjectNode body;
        try {
            body = (ObjectNode) bodyReader.readTree(rs.getString("body"));
        } catch (JsonProcessingException e) {
            throw new SQLException("Stored record body is not JSON", e);
        }
        return new StoredRecord(rs.getString("type"), rs.getString("id"), rs.getLong("revision"),
                rs.getString("run_id"), rs.getString("created_utc"), rs.getString("created_by"), body);
    }
}
