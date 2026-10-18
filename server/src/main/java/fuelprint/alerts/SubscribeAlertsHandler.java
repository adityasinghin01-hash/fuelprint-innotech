package fuelprint.alerts;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code subscribe_alerts} (guest or visitor). Saves an {@code alert_subscription} record with the masked
 * number only, and puts the full number into the contact table, in the same transaction.
 * Rules: a valid Indian mobile number, {@code consent} true, consent text FP-CONSENT-1, known areas, at
 * most one active sign-up per number. The sign-up ends at the end of the local (IST) day.
 */
@Component
@Profile("!stub")
class SubscribeAlertsHandler implements CommandHandler {

    static final String TYPE = "subscribe_alerts";
    static final String CONSENT_TEXT_VERSION = "FP-CONSENT-1";

    private final ContactStore contacts;
    private final Clock clock;

    SubscribeAlertsHandler(ContactStore contacts, Clock clock) {
        this.contacts = contacts;
        this.clock = clock;
    }

    @Override
    public String commandType() {
        return TYPE;
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.GUEST, Role.VISITOR);
    }

    @Override
    public Set<String> privatePayloadFields() {
        return Set.of("phone_number");
    }

    @Override
    public HandlerOutcome handle(Command command, Session session, StoreView store) {
        ObjectNode payload = command.payload();
        JsonNode consent = payload.get("consent");
        if (consent == null || !consent.isBoolean() || !consent.asBoolean()) {
            return new Rejection("CONSENT_REQUIRED", "Tick the consent line to sign up for demo SMS alerts.");
        }
        if (!CONSENT_TEXT_VERSION.equals(payload.path("consent_text_version").asText(null))) {
            return new Rejection("CONSENT_TEXT_VERSION", "The consent text is out of date. Reload the page and try again.");
        }
        JsonNode phoneNode = payload.get("phone_number");
        String phone = phoneNode != null && phoneNode.isTextual() ? PhoneNumbers.normalise(phoneNode.asText()) : null;
        if (phone == null) {
            return new Rejection("INVALID_PHONE_NUMBER",
                    "Enter an Indian mobile number: +91 and 10 digits starting with 6, 7, 8 or 9.");
        }
        List<String> areas = AlertRecords.strings(payload.get("area_ids"));
        if (areas == null || areas.isEmpty()) {
            return new Rejection("INVALID_AREA", "Pick at least one area.");
        }
        if (!areas.stream().allMatch(AlertGeography::isArea)) {
            return new Rejection("INVALID_AREA", "One of the areas is not one of the four demo areas.");
        }

        Instant now = clock.instant();
        // The new sign-up is the first record of the receipt (screens read the first record_ref).
        HandlerResult.Builder result = HandlerResult.builder();
        String id = Ids.newId();
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("phone_masked", PhoneNumbers.mask(phone));
        ArrayNode areaIds = fields.putArray("area_ids");
        areas.forEach(areaIds::add);
        fields.put("consent_text_version", CONSENT_TEXT_VERSION);
        fields.put("consent_utc", AlertTime.utc(now));
        fields.put("state", AlertRecords.ACTIVE);
        fields.put("expires_utc", AlertTime.utc(AlertTime.endOfLocalDay(now)));
        result.save(RecordChange.create(AlertRecords.SUBSCRIPTION, id, null, fields), "ALERT_SUBSCRIBED", null);

        String heldBy = contacts.subscriptionOf(phone).orElse(null);
        if (heldBy != null) {
            StoredRecord earlier = store.get(AlertRecords.SUBSCRIPTION, heldBy).orElse(null);
            if (earlier != null && AlertRecords.isActive(earlier, now)) {
                return new Rejection("DUPLICATE_NUMBER", "This number already has an active sign-up today.");
            }
            // A sign-up from an earlier day that was never purged: its number is released now.
            contacts.delete(heldBy);
            if (earlier != null && AlertRecords.ACTIVE.equals(earlier.body().path("state").asText())) {
                result.save(RecordChange.next(earlier, PurgeSubscriptionsHandler.purgedFields(earlier)),
                        "ALERT_SUBSCRIPTION_PURGED", null);
            }
        }
        contacts.insert(id, phone, session.sessionId(), AlertTime.utc(now));
        return result.build();
    }
}
