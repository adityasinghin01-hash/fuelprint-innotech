package fuelprint.core.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import fuelprint.core.WallTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * The events table: one row per saved record revision, with a strictly increasing {@code cursor}.
 * Like records, events are never updated or deleted. Appends run inside the command bus transaction.
 */
@Component
public class EventLog {

    private static final Pattern EVENT_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EventLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Appends the event for one saved record revision and returns it with its new cursor.
     *
     * @throws IllegalArgumentException if the event type is not upper snake case
     */
    public EventRow append(String eventType, StoredRecord saved, Double simTimeS, String actor, String commandId) {
        if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("Event type must be upper snake case: " + eventType);
        }
        if (simTimeS != null && !Double.isFinite(simTimeS)) {
            throw new IllegalArgumentException("sim_time_s must be finite or null");
        }
        String wallUtc = WallTime.now(clock);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO events (event_type, record_type, record_id, revision, run_id, sim_time_s, wall_utc, "
                            + "actor, command_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, eventType);
            statement.setString(2, saved.type());
            statement.setString(3, saved.id());
            statement.setLong(4, saved.revision());
            statement.setString(5, saved.runId());
            statement.setObject(6, simTimeS);
            statement.setString(7, wallUtc);
            statement.setString(8, actor);
            statement.setString(9, commandId);
            return statement;
        }, keys);
        long cursor = keys.getKey().longValue();
        return new EventRow(cursor, eventType, saved.type(), saved.id(), saved.revision(), saved.runId(), simTimeS,
                wallUtc, actor, commandId);
    }

    /** Events with a cursor greater than {@code after}, in cursor order, at most {@code limit}. */
    public List<EventRow> after(long after, int limit) {
        return jdbc.query("SELECT * FROM events WHERE cursor > ? ORDER BY cursor LIMIT ?", EventLog::mapRow, after, limit);
    }

    /** All events of one record, in cursor order. */
    public List<EventRow> forRecord(String recordType, String recordId) {
        return jdbc.query("SELECT * FROM events WHERE record_type = ? AND record_id = ? ORDER BY cursor",
                EventLog::mapRow, recordType, recordId);
    }

    /** The highest cursor saved so far, or 0 if there are no events. */
    public long lastCursor() {
        Long last = jdbc.queryForObject("SELECT MAX(cursor) FROM events", Long.class);
        return last == null ? 0 : last;
    }

    private static EventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        double sim = rs.getDouble("sim_time_s");
        Double simTimeS = rs.wasNull() ? null : sim;
        return new EventRow(rs.getLong("cursor"), rs.getString("event_type"), rs.getString("record_type"),
                rs.getString("record_id"), rs.getLong("revision"), rs.getString("run_id"), simTimeS,
                rs.getString("wall_utc"), rs.getString("actor"), rs.getString("command_id"));
    }
}
