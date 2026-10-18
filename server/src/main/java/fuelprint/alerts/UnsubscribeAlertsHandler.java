package fuelprint.alerts;

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
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code unsubscribe_alerts}. A guest or visitor can remove only the sign-up their own session made; a
 * presenter can remove any. The sign-up must be ACTIVE. Its number is deleted from the contact table
 * in the same transaction.
 */
@Component
@Profile("!stub")
class UnsubscribeAlertsHandler implements CommandHandler {

    static final String TYPE = "unsubscribe_alerts";

    private final ContactStore contacts;

    UnsubscribeAlertsHandler(ContactStore contacts) {
        this.contacts = contacts;
    }

    @Override
    public String commandType() {
        return TYPE;
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.GUEST, Role.VISITOR, Role.PRESENTER);
    }

    @Override
    public RecordKey revisionSubject(Command command) {
        JsonNode id = command.payload().get("subscription_id");
        return id != null && id.isTextual() ? new RecordKey(AlertRecords.SUBSCRIPTION, id.asText()) : null;
    }

    @Override
    public HandlerOutcome handle(Command command, Session session, StoreView store) {
        JsonNode idNode = command.payload().get("subscription_id");
        if (idNode == null || !idNode.isTextual()) {
            return Rejection.invalid("subscription_id is required.");
        }
        String id = idNode.asText();
        StoredRecord subscription = store.get(AlertRecords.SUBSCRIPTION, id).orElse(null);
        if (subscription == null) {
            return new Rejection("NOT_FOUND", "There is no such sign-up.");
        }
        if (!AlertRecords.ACTIVE.equals(subscription.body().path("state").asText())) {
            return new Rejection("NOT_ACTIVE", "This sign-up is already removed.");
        }
        if (!session.roles().contains(Role.PRESENTER) && !ownedBy(subscription, session)) {
            return Rejection.notAllowed("You can only remove your own sign-up.");
        }
        contacts.delete(id);
        ObjectNode fields = subscription.body();
        for (String common : RecordChange.COMMON_FIELDS) {
            fields.remove(common);
        }
        fields.put("state", AlertRecords.REMOVED);
        return HandlerResult.builder()
                .save(RecordChange.next(subscription, fields), "ALERT_UNSUBSCRIBED", null)
                .build();
    }

    /** Own sign-up: the session that made it (kept with the contact, never in a record). */
    private boolean ownedBy(StoredRecord subscription, Session session) {
        String owner = contacts.ownerOf(subscription.id()).orElse(null);
        return owner != null && owner.equals(session.sessionId());
    }
}
