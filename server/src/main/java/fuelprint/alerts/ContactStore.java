package fuelprint.alerts;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The alerts contact table ({@code alert_contact}): the ONLY place a full phone number exists
 * (AGREEMENT ground rule 10). Nothing here ever logs a number or puts one in an exception message.
 *
 * <p>Writes happen inside the command bus transaction of the command that changes a sign-up, so a number
 * is never stored without its record, and a rolled-back command leaves no number behind.
 */
@Component
@Profile("!stub")
class ContactStore {

    private final JdbcTemplate jdbc;

    ContactStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String subscriptionId, String phoneE164, String ownerSessionId, String createdUtc) {
        jdbc.update("INSERT INTO alert_contact (subscription_id, phone_e164, owner_session_id, created_utc) "
                + "VALUES (?, ?, ?, ?)", subscriptionId, phoneE164, ownerSessionId, createdUtc);
    }

    /** The session that made this sign-up, while its contact row exists. */
    Optional<String> ownerOf(String subscriptionId) {
        return jdbc.queryForList("SELECT owner_session_id FROM alert_contact WHERE subscription_id = ?",
                String.class, subscriptionId).stream().findFirst();
    }

    /** The sign-up that holds this number, if any. */
    Optional<String> subscriptionOf(String phoneE164) {
        return jdbc.queryForList("SELECT subscription_id FROM alert_contact WHERE phone_e164 = ?", String.class,
                phoneE164).stream().findFirst();
    }

    /** Numbers by sign-up id, for the sign-ups that still have one. */
    Map<String, String> numbersOf(Collection<String> subscriptionIds) {
        Map<String, String> numbers = new HashMap<>();
        for (String id : subscriptionIds) {
            jdbc.queryForList("SELECT phone_e164 FROM alert_contact WHERE subscription_id = ?", String.class, id)
                    .stream().findFirst().ifPresent(number -> numbers.put(id, number));
        }
        return numbers;
    }

    boolean has(String subscriptionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_contact WHERE subscription_id = ?",
                Integer.class, subscriptionId);
        return count != null && count > 0;
    }

    void delete(String subscriptionId) {
        jdbc.update("DELETE FROM alert_contact WHERE subscription_id = ?", subscriptionId);
    }

    /** Deletes every number. Returns how many rows were removed. */
    int deleteAll() {
        return jdbc.update("DELETE FROM alert_contact");
    }

    int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_contact", Integer.class);
        return count == null ? 0 : count;
    }

    /** Ids of every sign-up that still has a number. */
    java.util.List<String> allSubscriptionIds() {
        return jdbc.queryForList("SELECT subscription_id FROM alert_contact", String.class);
    }
}
