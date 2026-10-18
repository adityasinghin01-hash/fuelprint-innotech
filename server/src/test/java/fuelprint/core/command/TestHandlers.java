package fuelprint.core.command;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.db.DataDirectory;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.stereotype.Component;

/**
 * Test-only command handlers that exercise the bus. They are not real FuelPrint commands.
 * All save records of test types; the bus does the saving.
 */
public final class TestHandlers {

    public static final String NOTE = "test_note";

    private TestHandlers() {
    }

    /** Creates or updates a {@code test_note} record named by {@code payload.note_id}. */
    abstract static class NoteHandler implements CommandHandler {
        @Override
        public RecordKey revisionSubject(Command command) {
            var id = command.payload().path("note_id");
            return id.isTextual() ? new RecordKey(NOTE, id.asText()) : null;
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            var id = command.payload().path("note_id");
            if (!id.isTextual()) {
                return Rejection.invalid("note_id is required");
            }
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("text", command.payload().path("text").asText(null));
            RecordChange change = store.get(NOTE, id.asText())
                    .map(current -> RecordChange.next(current, fields))
                    .orElseGet(() -> RecordChange.create(NOTE, id.asText(), command.runId(), fields));
            return HandlerResult.builder().save(change, "TEST_NOTE_SAVED", null).build();
        }
    }

    /** Allowed for analysts and for automation. */
    @Component
    public static class AutoNote extends NoteHandler {
        public static final String TYPE = "test_auto_note";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.ANALYST, Role.AUTOMATION);
        }
    }

    /** Human-only: supervisors, never automation. */
    @Component
    public static class HumanNote extends NoteHandler {
        public static final String TYPE = "test_human_note";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.SUPERVISOR);
        }
    }

    /** Adds one to a counter record; records how many ran at the same time. */
    @Component
    public static class Counter implements CommandHandler {
        public static final String TYPE = "test_counter";
        public static final String RECORD = "test_counter";
        static final AtomicInteger IN_FLIGHT = new AtomicInteger();
        static final AtomicInteger MAX_IN_FLIGHT = new AtomicInteger();
        static final Set<String> THREADS = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.ANALYST);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            int now = IN_FLIGHT.incrementAndGet();
            MAX_IN_FLIGHT.accumulateAndGet(now, Math::max);
            THREADS.add(Thread.currentThread().getName());
            try {
                Thread.sleep(2); // widen any race window
                String id = command.payload().path("counter_id").asText();
                StoredRecord current = store.get(RECORD, id).orElse(null);
                long value = current == null ? 0 : current.body().path("value").asLong();
                ObjectNode fields = JsonNodeFactory.instance.objectNode().put("value", value + 1);
                RecordChange change = current == null
                        ? RecordChange.create(RECORD, id, null, fields)
                        : RecordChange.next(current, fields);
                return HandlerResult.builder().save(change, "TEST_COUNTER_SAVED", null).build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    /** Throws before returning anything. */
    @Component
    public static class Throws implements CommandHandler {
        public static final String TYPE = "test_throws";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.ANALYST);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            throw new IllegalStateException("test handler failed on purpose");
        }
    }

    /** Returns two records; saving the second one fails after the first was written. */
    @Component
    public static class HalfSave implements CommandHandler {
        public static final String TYPE = "test_half_save";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.ANALYST);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String goodId = command.payload().path("good_id").asText();
            String badId = command.payload().path("bad_id").asText();
            ObjectNode fields = JsonNodeFactory.instance.objectNode().put("text", "first");
            return HandlerResult.builder()
                    .save(RecordChange.create(NOTE, goodId, null, fields), "TEST_NOTE_SAVED", null)
                    .save(RecordChange.create("Bad Type", badId, null, fields), "TEST_NOTE_SAVED", null)
                    .build();
        }
    }

    /** Saves a note; {@link ChainListener} then submits {@link Followup} through the AutomationBus. */
    @Component
    public static class Chain extends NoteHandler {
        public static final String TYPE = "test_chain";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.ANALYST);
        }
    }

    /** Automation-only. Checks through a separate connection whether the first note is committed. */
    @Component
    public static class Followup implements CommandHandler {
        public static final String TYPE = "test_followup";
        public static final String RECORD = "test_followup";
        private final DataDirectory dataDirectory;

        public Followup(DataDirectory dataDirectory) {
            this.dataDirectory = dataDirectory;
        }

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.AUTOMATION);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String firstId = command.payload().path("first_note_id").asText();
            ObjectNode fields = JsonNodeFactory.instance.objectNode()
                    .put("first_note_id", firstId)
                    .put("first_committed", committedElsewhere(dataDirectory, NOTE, firstId));
            String id = command.payload().path("followup_id").asText();
            return HandlerResult.builder()
                    .save(RecordChange.create(RECORD, id, null, fields), "TEST_FOLLOWUP_SAVED", null)
                    .build();
        }
    }

    /** Takes a private phone number; keeps only a masked one in the record. */
    @Component
    public static class PrivateField implements CommandHandler {
        public static final String TYPE = "test_private";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.GUEST);
        }

        @Override
        public Set<String> privatePayloadFields() {
            return Set.of("phone_number");
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String phone = command.payload().path("phone_number").asText();
            String masked = phone.substring(0, 5) + "xxxxxx" + phone.substring(phone.length() - 2);
            ObjectNode fields = JsonNodeFactory.instance.objectNode().put("phone_masked", masked);
            String id = command.payload().path("subscription_id").asText();
            return HandlerResult.builder()
                    .save(RecordChange.create("test_subscription", id, null, fields), "TEST_SUBSCRIBED", null)
                    .build();
        }
    }

    /** True if a separate, brand-new SQLite connection can see the record (that is, it is committed). */
    static boolean committedElsewhere(DataDirectory dataDirectory, String type, String id) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dataDirectory.databaseFile());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM records WHERE type = ? AND id = ?")) {
            statement.setString(1, type);
            statement.setString(2, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
