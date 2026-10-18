package fuelprint.alerts;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;

/** Record type names and small readers for {@code alert_subscription} and {@code notification} records. */
final class AlertRecords {

    static final String SUBSCRIPTION = "alert_subscription";
    static final String NOTIFICATION = "notification";
    static final String INVESTIGATION = "investigation";
    static final String BOARD_DECISION = "board_decision";
    static final String WORDING = "wording";

    static final String ACTIVE = "ACTIVE";
    static final String REMOVED = "REMOVED";
    static final String PURGED = "PURGED";

    private AlertRecords() {
    }

    /** The values of a JSON array of strings (in order, no duplicates), or null if it is not one. */
    static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return null;
            }
            values.add(item.asText());
        }
        return new ArrayList<>(values);
    }

    static Instant instant(String utc) {
        try {
            return utc == null ? null : Instant.parse(utc);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** True if this sign-up is ACTIVE and its day has not ended. */
    static boolean isActive(StoredRecord subscription, Instant now) {
        if (!ACTIVE.equals(subscription.body().path("state").asText())) {
            return false;
        }
        Instant expires = instant(subscription.body().path("expires_utc").asText(null));
        return expires != null && expires.isAfter(now);
    }

    /** True if this sign-up is ACTIVE but its day has ended (its number is due to be deleted). */
    static boolean isExpired(StoredRecord subscription, Instant now) {
        if (!ACTIVE.equals(subscription.body().path("state").asText())) {
            return false;
        }
        Instant expires = instant(subscription.body().path("expires_utc").asText(null));
        return expires == null || !expires.isAfter(now);
    }

    /** The ACTIVE, not expired sign-ups that chose at least one of {@code areaIds}. */
    static List<StoredRecord> activeFor(StoreView store, Collection<String> areaIds, Instant now) {
        List<StoredRecord> found = new ArrayList<>();
        for (StoredRecord subscription : store.list(SUBSCRIPTION, null)) {
            if (!isActive(subscription, now)) {
                continue;
            }
            List<String> chosen = strings(subscription.body().get("area_ids"));
            if (chosen != null && chosen.stream().anyMatch(areaIds::contains)) {
                found.add(subscription);
            }
        }
        return found;
    }
}
