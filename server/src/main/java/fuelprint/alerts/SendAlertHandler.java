package fuelprint.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.api.AutomationSettings;
import fuelprint.api.GatewayMode;
import fuelprint.api.Ref;
import fuelprint.api.SendResult;
import fuelprint.api.SendStatus;
import fuelprint.api.SmsGateway;
import fuelprint.api.WordingPurpose;
import fuelprint.core.Ids;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Rejection;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code send_alert} (automation or presenter). Sends one SMS text to the active sign-ups of the chosen
 * areas and saves a {@code notification} record per outcome (AGREEMENT sections 5, 6 and 13).
 *
 * <p>Limits: at most one SMOKE_WARNING per area per 10 wall minutes, and at most {@code daily_cap} SMS per
 * local day. A send that is over a limit, has nobody to send to, or has no usable gateway is saved as a
 * SUPPRESSED notification with the reason; it is never dropped. The daily cap is all or nothing: if
 * the send would go over it, nobody gets it.
 *
 * <p>The send happens inside this handler, because the notification must hold the real counts of sent and
 * failed. The time is bounded: {@value #SEND_BUDGET_S} seconds in total, then the rest count as failed.
 */
@Component
@Profile("!stub")
class SendAlertHandler implements CommandHandler {

    static final String TYPE = "send_alert";
    static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);
    static final long SEND_BUDGET_S = 30;

    static final String RATE_LIMIT = "RATE_LIMIT";
    static final String DAILY_CAP = "DAILY_CAP";
    static final String NO_SUBSCRIBERS = "NO_SUBSCRIBERS";
    static final String GATEWAY_OFF = "GATEWAY_OFF";

    private static final Set<String> KINDS = Set.of(AlertTexts.SMOKE_WARNING, AlertTexts.BOARD_UPDATE, AlertTexts.TEST);
    private static final Set<String> COUNTED_STATES = Set.of("QUEUED", "SENDING", "SENT", "PARTIAL", "FAILED");
    private static final Logger log = LoggerFactory.getLogger(SendAlertHandler.class);

    private final ContactStore contacts;
    private final SmsGateway gateway;
    private final SmsSettings settings;
    private final AlertWording wording;
    private final ObjectProvider<AutomationSettings> automationSettings;
    private final Clock clock;

    SendAlertHandler(ContactStore contacts, SmsGateway gateway, SmsSettings settings, AlertWording wording,
                     ObjectProvider<AutomationSettings> automationSettings, Clock clock) {
        this.contacts = contacts;
        this.gateway = gateway;
        this.settings = settings;
        this.wording = wording;
        this.automationSettings = automationSettings;
        this.clock = clock;
    }

    @Override
    public String commandType() {
        return TYPE;
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.PRESENTER, Role.AUTOMATION);
    }

    @Override
    public HandlerOutcome handle(Command command, Session session, StoreView store) {
        ObjectNode payload = command.payload();
        String kind = payload.path("kind").asText(null);
        if (kind == null || !KINDS.contains(kind)) {
            return Rejection.invalid("kind must be SMOKE_WARNING, BOARD_UPDATE or TEST.");
        }
        if (session.isAutomation() && !autoAlertsOn()) {
            return new Rejection("AUTO_ALERTS_OFF", "Automatic alerts are switched off (auto_alerts).");
        }
        List<String> areas = AlertRecords.strings(payload.get("area_ids"));
        if (areas == null || areas.isEmpty() || !areas.stream().allMatch(AlertGeography::isArea)) {
            return new Rejection("INVALID_AREA", "area_ids must name one or more of the four demo areas.");
        }

        Ref cause = null;
        String runId = null;
        String template;
        if (AlertTexts.TEST.equals(kind)) {
            template = AlertTexts.test();
        } else {
            boolean smoke = AlertTexts.SMOKE_WARNING.equals(kind);
            String causeType = smoke ? AlertRecords.INVESTIGATION : AlertRecords.BOARD_DECISION;
            JsonNode causeNode = payload.get("cause");
            if (causeNode == null || !causeNode.isObject() || !causeType.equals(causeNode.path("type").asText())
                    || !causeNode.path("id").isTextual() || !causeNode.path("revision").canConvertToLong()
                    || !causeNode.path("revision").isIntegralNumber()) {
                return new Rejection("INVALID_CAUSE", kind + " needs a cause: a " + causeType + " with id and revision.");
            }
            cause = new Ref(causeType, causeNode.path("id").asText(), causeNode.path("revision").asLong());
            StoredRecord causeRecord = store.get(cause.type(), cause.id(), cause.revision()).orElse(null);
            if (causeRecord == null) {
                return new Rejection("CAUSE_NOT_FOUND", "The " + causeType + " named as the cause does not exist.");
            }
            runId = causeRecord.runId();
            List<String> allowed = AlertRecords.strings(smoke
                    ? causeRecord.body().get("warned_area_ids") : causeRecord.body().path("notice").get("area_ids"));
            if (allowed == null || !allowed.containsAll(areas)) {
                return new Rejection("AREA_NOT_IN_CAUSE", "An area was chosen that the " + causeType + " does not name.");
            }
            template = smoke ? AlertTexts.smokeWarning(areas)
                    : AlertTexts.boardUpdate(causeRecord.body().path("decision").asText(null), areas);
            if (template == null) {
                return new Rejection("INVALID_CAUSE", "The board decision is not one of the four known decisions.");
            }
        }

        Instant now = clock.instant();
        List<StoredRecord> notifications = store.list(AlertRecords.NOTIFICATION, null);
        GatewayMode mode = settings.mode();
        HandlerResult.Builder result = HandlerResult.builder();

        // Limit 1: one SMOKE_WARNING per area per 10 wall minutes.
        List<String> eligible = new ArrayList<>(areas);
        if (AlertTexts.SMOKE_WARNING.equals(kind)) {
            List<String> limited = areas.stream().filter(area -> warnedRecently(notifications, area, now)).toList();
            eligible.removeAll(limited);
            if (!limited.isEmpty()) {
                save(result, suppressed(kind, limited, AlertTexts.smokeWarning(limited), cause, 0, RATE_LIMIT, mode));
            }
        }
        if (eligible.isEmpty()) {
            return result.build();
        }
        if (!eligible.equals(areas)) {
            template = AlertTexts.smokeWarning(eligible);
        }

        // Who gets it: active sign-ups of these areas that still have a number, once each.
        List<StoredRecord> subscribers = AlertRecords.activeFor(store, eligible, now);
        Map<String, String> numbers = contacts.numbersOf(subscribers.stream().map(StoredRecord::id).toList());
        List<String> recipients = subscribers.stream().map(StoredRecord::id).filter(numbers::containsKey).toList();

        if (recipients.isEmpty()) {
            return save(result, suppressed(kind, eligible, template, cause, 0, NO_SUBSCRIBERS, mode)).build();
        }
        if (mode == GatewayMode.REAL && !settings.ready()) {
            return save(result, suppressed(kind, eligible, template, cause, recipients.size(), GATEWAY_OFF, mode)).build();
        }
        if (sentToday(notifications, now) + recipients.size() > settings.dailyCap()) {
            return save(result, suppressed(kind, eligible, template, cause, recipients.size(), DAILY_CAP, mode)).build();
        }

        // Plain words (checked) or the template, then the send.
        AlertWording.Chosen text;
        if (cause == null) {
            text = AlertWording.template(template, null);
        } else {
            WordingPurpose purpose = AlertTexts.SMOKE_WARNING.equals(kind)
                    ? WordingPurpose.SMS_SMOKE_WARNING : WordingPurpose.SMS_BOARD_UPDATE;
            text = wording.choose(cause, purpose, template, store, runId);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(SEND_BUDGET_S).toNanos();
        int sent = 0;
        int failed = 0;
        for (String id : recipients) {
            if (System.nanoTime() > deadline) {
                failed++;
                continue;
            }
            SendResult sendResult = send(numbers.get(id), text.text());
            if (sendResult.status() == SendStatus.SENT || sendResult.status() == SendStatus.LOGGED_ONLY) {
                sent++;
            } else {
                failed++;
            }
        }
        String state = failed == 0 ? "SENT" : sent == 0 ? "FAILED" : "PARTIAL";
        log.info("send_alert {} to {} area(s): {} recipients, {} sent, {} failed ({}, {})", kind, eligible.size(),
                recipients.size(), sent, failed, state, mode);

        ObjectNode fields = notification(kind, eligible, text.text(), cause, recipients.size(), sent, failed, state,
                null, mode);
        fields.put("wording_id", text.wordingId());
        fields.put("text_source", text.source());
        fields.put("fallback_reason", text.fallbackReason());
        return save(result, fields).build();
    }

    private SendResult send(String number, String text) {
        try {
            return gateway.send(number, text);
        } catch (RuntimeException e) {
            log.warn("The SMS gateway threw {}; counted as failed", e.getClass().getSimpleName());
            return new SendResult(SendStatus.FAILED, settings.mode(), 1, "GATEWAY_ERROR");
        }
    }

    private boolean autoAlertsOn() {
        AutomationSettings automation = automationSettings.getIfAvailable();
        return automation == null || automation.isOn(AutomationSettings.AUTO_ALERTS);
    }

    // ---- limits (derived from the saved notification records) ----

    private static boolean warnedRecently(List<StoredRecord> notifications, String area, Instant now) {
        Instant since = now.minus(RATE_LIMIT_WINDOW);
        for (StoredRecord record : notifications) {
            JsonNode body = record.body();
            if (!AlertTexts.SMOKE_WARNING.equals(body.path("kind").asText())
                    || !COUNTED_STATES.contains(body.path("state").asText())) {
                continue;
            }
            List<String> warned = AlertRecords.strings(body.get("area_ids"));
            Instant at = AlertRecords.instant(record.createdUtc());
            if (warned != null && warned.contains(area) && at != null && at.isAfter(since)) {
                return true;
            }
        }
        return false;
    }

    /** SMS sent so far in the current local day (counted by recipient, from the notification records). */
    private static int sentToday(List<StoredRecord> notifications, Instant now) {
        Instant start = AlertTime.startOfLocalDay(now);
        Instant end = AlertTime.endOfLocalDay(now);
        int total = 0;
        for (StoredRecord record : notifications) {
            Instant at = AlertRecords.instant(record.createdUtc());
            if (at != null && !at.isBefore(start) && at.isBefore(end)) {
                total += record.body().path("sent").asInt(0);
            }
        }
        return total;
    }

    // ---- records ----

    private static ObjectNode suppressed(String kind, List<String> areas, String text, Ref cause, int recipients,
                                         String reason, GatewayMode mode) {
        ObjectNode fields = notification(kind, areas, text, cause, recipients, 0, 0, "SUPPRESSED", reason, mode);
        fields.putNull("wording_id");
        fields.put("text_source", "TEMPLATE");
        fields.putNull("fallback_reason");
        return fields;
    }

    private static ObjectNode notification(String kind, List<String> areas, String text, Ref cause, int recipients,
                                           int sent, int failed, String state, String suppressedReason,
                                           GatewayMode mode) {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("kind", kind);
        ArrayNode areaIds = fields.putArray("area_ids");
        new LinkedHashSet<>(areas).forEach(areaIds::add);
        fields.put("text", text);
        fields.putNull("wording_id");
        if (cause == null) {
            fields.putNull("cause");
        } else {
            fields.putObject("cause").put("type", cause.type()).put("id", cause.id()).put("revision", cause.revision());
        }
        fields.put("recipients", recipients);
        fields.put("sent", sent);
        fields.put("failed", failed);
        fields.put("state", state);
        fields.put("suppressed_reason", suppressedReason);
        fields.put("gateway_mode", mode.name());
        return fields;
    }

    private static HandlerResult.Builder save(HandlerResult.Builder result, ObjectNode fields) {
        return result.save(RecordChange.create(AlertRecords.NOTIFICATION, Ids.newId(), null, fields),
                "NOTIFICATION_" + fields.path("state").asText(), null);
    }
}
