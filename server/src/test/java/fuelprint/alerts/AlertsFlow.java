package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared helpers for the alerts tests that use the Spring context: sessions, commands, the fake
 * ops records, and a reset before each test (everyone purged, the clock moved to a fresh day, so the
 * rate limit and the daily cap start from zero).
 */
abstract class AlertsFlow {

    static final Session GUEST_1 = Session.human("guest-session-1", "guest-21c9", Set.of(Role.GUEST));
    static final Session GUEST_2 = Session.human("guest-session-2", "guest-77aa", Set.of(Role.GUEST));
    static final Session VISITOR_1 = Session.human("visitor-session-1", "visitor-7f3a", Set.of(Role.VISITOR));
    static final Session PRESENTER = Session.human("presenter-session", "presenter@laptop-3", Set.of(Role.PRESENTER));
    static final Session ANALYST = Session.human("analyst-session", "analyst@laptop-1", Set.of(Role.ANALYST));
    static final Session BOARD = Session.human("board-session", "board@laptop-5", Set.of(Role.BOARD));

    static final String NUMBER_A = "+919812345678";
    static final String NUMBER_B = "+918765432109";
    static final String NUMBER_C = "+917012345670";
    static final String NUMBER_D = "+916123456789";

    @Autowired CommandBus bus;
    @Autowired RecordStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired AlertsTestConfig.MutableClock clock;
    @Autowired AlertsTestConfig.FakeAutomationSettings automationSettings;
    @Autowired AlertsTestConfig.FakeWordingService wordingService;
    @Autowired AlertsTestConfig.RecordingGateway gateway;

    ListAppender<ILoggingEvent> logs;
    private Logger fuelprintLogger;

    @BeforeEach
    void resetAlerts() {
        fuelprintLogger = (Logger) LoggerFactory.getLogger("fuelprint");
        logs = new ListAppender<>();
        logs.start();
        fuelprintLogger.addAppender(logs);
        fuelprintLogger.setLevel(Level.DEBUG);

        // A fresh local day at 10:00 IST, nobody signed up, everything automatic, no fake wording, nothing sent yet.
        clock.set(AlertTime.startOfLocalDay(clock.instant()).plus(Duration.ofDays(1)).plus(Duration.ofHours(10)));
        Receipt purge = bus.send(command("purge_subscriptions", JsonNodeFactory.instance.objectNode(), null), PRESENTER);
        assertThat(purge.status()).isEqualTo(Receipt.Status.APPLIED);
        automationSettings.set(AutomationSettings.AUTO_ALERTS, true);
        wordingService.reset();
        gateway.sent.clear();
        logs.list.clear();
    }

    @AfterEach
    void stopLogs() {
        fuelprintLogger.detachAppender(logs);
    }

    // ---- commands ----

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static Command command(String type, ObjectNode payload, Long expectedRevision) {
        return new Command(Command.SCHEMA_VERSION, uuid(), type, null, expectedRevision, payload);
    }

    static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    static ArrayNode array(String... values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    Receipt subscribe(Session session, String phone, boolean consent, String... areas) {
        ObjectNode payload = object().put("phone_number", phone);
        payload.set("area_ids", array(areas));
        payload.put("consent", consent);
        payload.put("consent_text_version", "FP-CONSENT-1");
        return bus.send(command("subscribe_alerts", payload, null), session);
    }

    /** Signs up and returns the new sign-up id. */
    String signUp(Session session, String phone, String... areas) {
        Receipt receipt = subscribe(session, phone, true, areas);
        assertThat(receipt.status()).as(receipt.reasonText()).isEqualTo(Receipt.Status.APPLIED);
        return receipt.recordRefs().get(0).id();
    }

    Receipt unsubscribe(Session session, String subscriptionId) {
        return bus.send(command("unsubscribe_alerts", object().put("subscription_id", subscriptionId), null), session);
    }

    /** Saves an OPEN investigation (as ops would) and waits until the automatic reaction has finished. */
    String openInvestigation(List<String> warnedAreas) {
        return saveInvestigation(uuid(), "OPEN", warnedAreas, 1);
    }

    String saveInvestigation(String id, String state, List<String> warnedAreas, long revisionToExpect) {
        ObjectNode fields = object().put("trigger_id", uuid()).put("state", state);
        ArrayNode warned = fields.putArray("warned_area_ids");
        warnedAreas.forEach(warned::add);
        fields.set("analysis_ids", JsonNodeFactory.instance.arrayNode());
        ObjectNode payload = object().put("id", id).put("run_id", RUN_ID);
        payload.set("fields", fields);
        Receipt receipt = bus.send(command("test_save_investigation", payload, null), ANALYST);
        assertThat(receipt.status()).as(receipt.reasonText()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(receipt.recordRefs().get(0).revision()).isEqualTo(revisionToExpect);
        settle();
        return id;
    }

    /** Saves a board decision (as ops would) and waits for the automatic reaction. */
    String boardDecision(String decision, List<String> noticeAreas) {
        String id = uuid();
        ObjectNode fields = object().put("case_id", uuid()).put("decision", decision).put("rationale", "Synthetic rationale.");
        ObjectNode notice = fields.putObject("notice");
        ArrayNode areas = notice.putArray("area_ids");
        noticeAreas.forEach(areas::add);
        notice.put("area_text", "Demo notice text");
        ObjectNode payload = object().put("id", id).put("run_id", RUN_ID);
        payload.set("fields", fields);
        Receipt receipt = bus.send(command("test_save_board_decision", payload, null), BOARD);
        assertThat(receipt.status()).as(receipt.reasonText()).isEqualTo(Receipt.Status.APPLIED);
        settle();
        return id;
    }

    /**
     * Waits until the automatic reaction to a command has finished. {@code awaitIdle} only waits for what
     * was queued before the call, and the listener queues its follow-up while the first command is
     * still publishing, so the first call may return too early; the second one waits for the follow-up.
     */
    void settle() {
        for (int i = 0; i < 3; i++) {
            bus.awaitIdle();
        }
    }

    static final String RUN_ID = "11111111-2222-4333-8444-555555555555";

    // ---- reading what was saved ----

    /** Every notification record, oldest first. */
    List<StoredRecord> notifications() {
        return store.list("notification", null);
    }

    /** The notifications made after {@code before} others existed. */
    List<StoredRecord> notificationsAfter(int before) {
        List<StoredRecord> all = notifications();
        return new ArrayList<>(all.subList(before, all.size()));
    }

    static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    /** The sign-ups that are ACTIVE right now (earlier tests leave REMOVED and PURGED ones behind). */
    List<StoredRecord> activeSubscriptions() {
        return store.list("alert_subscription", null).stream()
                .filter(record -> "ACTIVE".equals(record.body().path("state").asText())).toList();
    }

    int contactRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alert_contact", Integer.class);
    }
}
