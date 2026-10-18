package fuelprint.alerts;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * {@code purge_subscriptions} (presenter or automation). Deletes every number from the contact table and
 * marks every ACTIVE sign-up PURGED. Automation sends it at local midnight (everything) and at start-up
 * with {@code "expired_only": true}, which deletes only the numbers of sign-ups whose day has ended.
 * {@code expired_only} is the one optional field this command takes (AGREEMENT lists no payload).
 */
@Component
@Profile("!stub")
class PurgeSubscriptionsHandler implements CommandHandler {

    static final String TYPE = "purge_subscriptions";

    private final ContactStore contacts;
    private final Clock clock;

    PurgeSubscriptionsHandler(ContactStore contacts, Clock clock) {
        this.contacts = contacts;
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
        JsonNode flag = command.payload().get("expired_only");
        if (flag != null && !flag.isNull() && !flag.isBoolean()) {
            return Rejection.invalid("expired_only must be true or false.");
        }
        boolean expiredOnly = flag != null && flag.asBoolean();
        Instant now = clock.instant();

        HandlerResult.Builder result = HandlerResult.builder();
        for (StoredRecord subscription : store.list(AlertRecords.SUBSCRIPTION, null)) {
            boolean due = expiredOnly ? AlertRecords.isExpired(subscription, now)
                    : AlertRecords.ACTIVE.equals(subscription.body().path("state").asText());
            if (due) {
                contacts.delete(subscription.id());
                result.save(RecordChange.next(subscription, purgedFields(subscription)),
                        "ALERT_SUBSCRIPTION_PURGED", null);
            }
        }
        if (!expiredOnly) {
            contacts.deleteAll(); // every number, including any row without an active sign-up
        }
        return result.build();
    }

    /** The sign-up's own fields with the state PURGED. */
    static ObjectNode purgedFields(StoredRecord subscription) {
        ObjectNode fields = subscription.body();
        for (String common : RecordChange.COMMON_FIELDS) {
            fields.remove(common);
        }
        fields.put("state", AlertRecords.PURGED);
        return fields;
    }
}
