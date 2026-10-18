package fuelprint.core.command;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.core.WallTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The commands table: one row per decided command with the exact receipt it got. Rows are never changed.
 * Only the {@link CommandBus} writes here.
 */
@Component
public class CommandLog {

    /** A stored command: who sent it, its request digest and its receipt. */
    record StoredCommand(String commandId, String commandType, String actor, String requestDigest, Receipt receipt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CommandLog(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    Optional<StoredCommand> find(String commandId) {
        return jdbc.query("SELECT command_id, command_type, actor, request_digest, receipt_json FROM commands "
                        + "WHERE command_id = ?",
                (rs, n) -> new StoredCommand(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        readReceipt(rs.getString(5))),
                commandId).stream().findFirst();
    }

    void insert(Command command, String actor, boolean automatic, RequestDigest.Result digest, Receipt receipt) {
        jdbc.update("INSERT INTO commands (command_id, command_type, actor, automatic, run_id, expected_revision, "
                        + "payload, request_digest, status, reason_code, receipt_json, received_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                command.commandId(), command.commandType(), actor, automatic ? 1 : 0,
                command.runId(), command.expectedRevision(), write(digest.storedPayload()), digest.digest(),
                receipt.status().name(), receipt.reasonCode(), write(receipt), WallTime.now(clock));
    }

    /** A recorded command from fake replay; its payload is not kept and it can never be matched by a resend. */
    void insertRecorded(RecordedCommand recorded, Receipt receipt) {
        jdbc.update("INSERT INTO commands (command_id, command_type, actor, automatic, run_id, expected_revision, "
                        + "payload, request_digest, status, reason_code, receipt_json, received_utc) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, '{}', ?, ?, ?, ?, ?)",
                recorded.commandId(), recorded.commandType(), recorded.actor(), receipt.automatic() ? 1 : 0,
                recorded.runId(), "recorded:" + recorded.commandId(), receipt.status().name(), receipt.reasonCode(),
                write(receipt), WallTime.now(clock));
    }

    /** One automation log entry: the receipt plus when and what. */
    public record LogEntry(String commandId, String commandType, String runId, String receivedUtc, Receipt receipt) {
    }

    /**
     * The automation log, newest first: decided commands of the {@code automation} actor (at most {@code limit}),
     * optionally for one run (commands with no run are always included).
     */
    public List<LogEntry> automationEntries(String runId, int limit) {
        String where = runId == null ? "automatic = 1" : "automatic = 1 AND (run_id = ? OR run_id IS NULL)";
        Object[] args = runId == null ? new Object[] {limit} : new Object[] {runId, limit};
        return jdbc.query("SELECT command_id, command_type, run_id, received_utc, receipt_json FROM commands WHERE "
                        + where + " ORDER BY row_id DESC LIMIT ?",
                (rs, n) -> new LogEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        readReceipt(rs.getString(5))), args);
    }

    /** The stored receipt of a command, if the bus has decided it. */
    public Optional<Receipt> receipt(String commandId) {
        return find(commandId).map(StoredCommand::receipt);
    }

    /**
     * The automation log (AGREEMENT section 12): receipts of commands sent by {@code automation}, oldest first,
     * at most {@code limit}.
     */
    public List<Receipt> automationLog(int limit) {
        return jdbc.query("SELECT receipt_json FROM commands WHERE automatic = 1 ORDER BY row_id LIMIT ?",
                (rs, n) -> readReceipt(rs.getString(1)), limit);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write JSON", e);
        }
    }

    private Receipt readReceipt(String json) {
        try {
            return mapper.readValue(json, Receipt.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored receipt is not valid JSON", e);
        }
    }
}
