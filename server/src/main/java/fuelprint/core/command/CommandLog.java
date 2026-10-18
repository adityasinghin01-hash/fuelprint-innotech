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

    void insert(Command command, Session session, RequestDigest.Result digest, Receipt receipt) {
        jdbc.update("INSERT INTO commands (command_id, command_type, actor, automatic, run_id, expected_revision, "
                        + "payload, request_digest, status, reason_code, receipt_json, received_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                command.commandId(), command.commandType(), session.actorLabel(), session.isAutomation() ? 1 : 0,
                command.runId(), command.expectedRevision(), write(digest.storedPayload()), digest.digest(),
                receipt.status().name(), receipt.reasonCode(), write(receipt), WallTime.now(clock));
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
