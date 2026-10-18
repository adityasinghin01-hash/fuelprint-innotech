package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Command;
import fuelprint.core.command.ReasonCode;
import fuelprint.core.command.Receipt;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** subscribe_alerts and unsubscribe_alerts. */
class SubscriptionTest extends AlertsSpringTest {

    @Autowired AutomationBus automation;

    private StoredRecord subscription(String id) {
        return store.get("alert_subscription", id).orElseThrow();
    }

    // ---- subscribe ----

    @Test
    void aGuestSignsUpAndOnlyTheMaskedNumberIsInTheRecord() {
        Receipt receipt = subscribe(GUEST_1, NUMBER_A, true, "AREA-E", "AREA-N");

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(receipt.actor()).isEqualTo("guest-21c9");
        assertThat(receipt.automatic()).isFalse();
        StoredRecord record = subscription(receipt.recordRefs().get(0).id());
        JsonNode body = record.body();
        assertThat(body.path("phone_masked").asText()).isEqualTo("+91 98xxxxxx78");
        assertThat(texts(body.path("area_ids"))).containsExactly("AREA-E", "AREA-N");
        assertThat(body.path("consent_text_version").asText()).isEqualTo("FP-CONSENT-1");
        assertThat(body.path("consent_utc").asText()).isEqualTo(AlertTime.utc(clock.instant()));
        assertThat(body.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("run_id").isNull()).isTrue();
        assertThat(body.toString()).doesNotContain("9812345678").doesNotContain("phone_number");
        // the full number is only in the contact table
        assertThat(jdbc.queryForObject("SELECT phone_e164 FROM alert_contact WHERE subscription_id = ?", String.class,
                record.id())).isEqualTo(NUMBER_A);
    }

    @Test
    void theSignUpEndsAtTheEndOfTheLocalDay() {
        // the test clock always reads 10:00 IST of some day; that local day ends 14 hours later (18:30 UTC)
        String id = signUp(GUEST_1, NUMBER_A, "AREA-E");
        String expires = subscription(id).body().path("expires_utc").asText();
        assertThat(expires).isEqualTo(AlertTime.utc(clock.instant().plus(Duration.ofHours(14))));
        assertThat(expires).endsWith("T18:30:00.000Z");
    }

    @Test
    void aVisitorMaySignUpToo() {
        assertThat(subscribe(VISITOR_1, NUMBER_B, true, "AREA-W").status()).isEqualTo(Receipt.Status.APPLIED);
    }

    @Test
    void numbersTypedWithSpacesAreAccepted() {
        Receipt receipt = subscribe(GUEST_1, "+91 98123 45678", true, "AREA-E");
        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(subscription(receipt.recordRefs().get(0).id()).body().path("phone_masked").asText())
                .isEqualTo("+91 98xxxxxx78");
    }

    @Test
    void badNumbersAreRejectedWithNothingSaved() {
        int before = store.list("alert_subscription", null).size();
        for (String bad : new String[] {"12345", "9812345678", "+14155550123", "+915812345678", "+91981234567",
                "+9198123456789", "abc", "", " "}) {
            Receipt receipt = subscribe(GUEST_1, bad, true, "AREA-E");
            assertThat(receipt.status()).as(bad).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).as(bad).isEqualTo("INVALID_PHONE_NUMBER");
            assertThat(receipt.recordRefs()).isEmpty();
        }
        assertThat(store.list("alert_subscription", null)).hasSize(before);
        assertThat(contactRows()).isZero();
    }

    @Test
    void aMissingOrNonTextNumberIsRejected() {
        ObjectNode payload = object().put("consent", true).put("consent_text_version", "FP-CONSENT-1");
        payload.set("area_ids", array("AREA-E"));
        assertThat(bus.send(command("subscribe_alerts", payload, null), GUEST_1).reasonCode())
                .isEqualTo("INVALID_PHONE_NUMBER");
        payload.put("phone_number", 9812345678L);
        assertThat(bus.send(command("subscribe_alerts", payload, null), GUEST_1).reasonCode())
                .isEqualTo("INVALID_PHONE_NUMBER");
    }

    @Test
    void consentMustBeTrueAndTheTextVersionMustMatch() {
        Receipt no = subscribe(GUEST_1, NUMBER_A, false, "AREA-E");
        assertThat(no.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(no.reasonCode()).isEqualTo("CONSENT_REQUIRED");

        // consent missing altogether, and consent as a text "true"
        ObjectNode payload = object().put("phone_number", NUMBER_A).put("consent_text_version", "FP-CONSENT-1");
        payload.set("area_ids", array("AREA-E"));
        assertThat(bus.send(command("subscribe_alerts", payload, null), GUEST_1).reasonCode()).isEqualTo("CONSENT_REQUIRED");
        payload.put("consent", "true");
        assertThat(bus.send(command("subscribe_alerts", payload, null), GUEST_1).reasonCode()).isEqualTo("CONSENT_REQUIRED");

        payload.put("consent", true).put("consent_text_version", "FP-CONSENT-0");
        assertThat(bus.send(command("subscribe_alerts", payload, null), GUEST_1).reasonCode()).isEqualTo("CONSENT_TEXT_VERSION");
        payload.remove("consent_text_version");
        assertThat(bus.send(command("subscribe_alerts", payload, null), GUEST_1).reasonCode()).isEqualTo("CONSENT_TEXT_VERSION");
        assertThat(activeSubscriptions()).isEmpty();
        assertThat(contactRows()).isZero();
    }

    @Test
    void areasMustBeOneOrMoreOfTheFour() {
        for (String[] bad : new String[][] {{}, {"AREA-X"}, {"AREA-E", "AREA-X"}, {"area-e"}}) {
            Receipt receipt = subscribe(GUEST_1, NUMBER_A, true, bad);
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo("INVALID_AREA");
        }
        assertThat(contactRows()).isZero();
    }

    @Test
    void aSecondActiveSignUpWithTheSameNumberIsRejected() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");

        Receipt same = subscribe(GUEST_1, NUMBER_A, true, "AREA-W");
        Receipt otherSession = subscribe(GUEST_2, NUMBER_A, true, "AREA-N");
        Receipt typedDifferently = subscribe(GUEST_2, "+91 98123-45678", true, "AREA-N");

        for (Receipt receipt : new Receipt[] {same, otherSession, typedDifferently}) {
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo("DUPLICATE_NUMBER");
        }
        assertThat(activeSubscriptions()).hasSize(1);
        assertThat(contactRows()).isEqualTo(1);
    }

    @Test
    void theSameNumberMayCome_backAfterRemovalAndAfterTheDayEnded() {
        String first = signUp(GUEST_1, NUMBER_A, "AREA-E");
        assertThat(unsubscribe(GUEST_1, first).status()).isEqualTo(Receipt.Status.APPLIED);
        String second = signUp(GUEST_1, NUMBER_A, "AREA-E");
        assertThat(second).isNotEqualTo(first);

        // the day ends without a purge: the old ACTIVE sign-up is expired, so the number is free again
        clock.advance(Duration.ofHours(16));
        String third = signUp(GUEST_2, NUMBER_A, "AREA-N");

        assertThat(subscription(second).body().path("state").asText()).isEqualTo("PURGED");
        assertThat(subscription(third).body().path("state").asText()).isEqualTo("ACTIVE");
        assertThat(contactRows()).isEqualTo(1);
    }

    @Test
    void sendingTheSameCommandTwiceMakesOneSignUp() {
        ObjectNode payload = object().put("phone_number", NUMBER_A).put("consent", true)
                .put("consent_text_version", "FP-CONSENT-1");
        payload.set("area_ids", array("AREA-E"));
        Command command = command("subscribe_alerts", payload, null);

        Receipt first = bus.send(command, GUEST_1);
        Receipt second = bus.send(command, GUEST_1);

        assertThat(first.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(second).isEqualTo(first);
        assertThat(activeSubscriptions()).hasSize(1);
        assertThat(contactRows()).isEqualTo(1);
    }

    @Test
    void onlyGuestsAndVisitorsMaySubscribe() {
        for (var session : new fuelprint.core.command.Session[] {PRESENTER, ANALYST, BOARD}) {
            Receipt receipt = subscribe(session, NUMBER_A, true, "AREA-E");
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        }
        ObjectNode payload = object().put("phone_number", NUMBER_A).put("consent", true)
                .put("consent_text_version", "FP-CONSENT-1");
        payload.set("area_ids", array("AREA-E"));
        Receipt byAutomation = automation.submit("subscribe_alerts", payload).join();
        assertThat(byAutomation.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(byAutomation.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(contactRows()).isZero();
    }

    // ---- unsubscribe ----

    @Test
    void aGuestRemovesTheirOwnSignUpAndTheNumberIsDeleted() {
        String id = signUp(GUEST_1, NUMBER_A, "AREA-E");

        Receipt receipt = unsubscribe(GUEST_1, id);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(subscription(id).body().path("state").asText()).isEqualTo("REMOVED");
        assertThat(subscription(id).revision()).isEqualTo(2);
        assertThat(subscription(id).body().path("phone_masked").asText()).isEqualTo("+91 98xxxxxx78");
        assertThat(contactRows()).isZero();
    }

    @Test
    void aGuestCannotRemoveSomeoneElsesSignUp() {
        String id = signUp(GUEST_1, NUMBER_A, "AREA-E");

        Receipt other = unsubscribe(GUEST_2, id);
        Receipt visitor = unsubscribe(VISITOR_1, id);

        for (Receipt receipt : new Receipt[] {other, visitor}) {
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        }
        assertThat(subscription(id).body().path("state").asText()).isEqualTo("ACTIVE");
        assertThat(subscription(id).revision()).isEqualTo(1);
        assertThat(contactRows()).isEqualTo(1);
    }

    @Test
    void aPresenterCanRemoveAnySignUp() {
        String a = signUp(GUEST_1, NUMBER_A, "AREA-E");
        String b = signUp(VISITOR_1, NUMBER_B, "AREA-W");

        assertThat(unsubscribe(PRESENTER, a).status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(unsubscribe(PRESENTER, b).status()).isEqualTo(Receipt.Status.APPLIED);

        assertThat(subscription(a).body().path("state").asText()).isEqualTo("REMOVED");
        assertThat(subscription(b).body().path("state").asText()).isEqualTo("REMOVED");
        assertThat(contactRows()).isZero();
    }

    @Test
    void otherRolesAndAutomationCannotUnsubscribe() {
        String id = signUp(GUEST_1, NUMBER_A, "AREA-E");

        assertThat(unsubscribe(ANALYST, id).reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(unsubscribe(BOARD, id).reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        Receipt byAutomation = automation.submit("unsubscribe_alerts", object().put("subscription_id", id)).join();
        assertThat(byAutomation.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(byAutomation.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(subscription(id).body().path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void removingTwiceOrAnUnknownSignUpIsRefused() {
        String id = signUp(GUEST_1, NUMBER_A, "AREA-E");
        unsubscribe(GUEST_1, id);

        Receipt again = unsubscribe(GUEST_1, id);
        assertThat(again.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(again.reasonCode()).isEqualTo("NOT_ACTIVE");

        Receipt unknown = unsubscribe(PRESENTER, uuid());
        assertThat(unknown.reasonCode()).isEqualTo("NOT_FOUND");

        Receipt missing = bus.send(command("unsubscribe_alerts", object(), null), PRESENTER);
        assertThat(missing.reasonCode()).isEqualTo(ReasonCode.INVALID);
    }

    @Test
    void aStaleRevisionIsRejectedAndNothingChanges() {
        String id = signUp(GUEST_1, NUMBER_A, "AREA-E");
        ObjectNode payload = object().put("subscription_id", id);

        Receipt stale = bus.send(command("unsubscribe_alerts", payload, 7L), GUEST_1);
        assertThat(stale.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(stale.reasonCode()).isEqualTo(ReasonCode.STALE_REVISION);
        assertThat(subscription(id).body().path("state").asText()).isEqualTo("ACTIVE");
        assertThat(contactRows()).isEqualTo(1);

        Receipt current = bus.send(command("unsubscribe_alerts", payload, 1L), GUEST_1);
        assertThat(current.status()).isEqualTo(Receipt.Status.APPLIED);
    }

    @Test
    void sendingTheSameUnsubscribeTwiceReturnsTheFirstReceipt() {
        String id = signUp(GUEST_1, NUMBER_A, "AREA-E");
        Command command = command("unsubscribe_alerts", object().put("subscription_id", id), 1L);

        Receipt first = bus.send(command, GUEST_1);
        Receipt second = bus.send(command, GUEST_1);

        assertThat(first.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(second).isEqualTo(first);
        assertThat(subscription(id).revision()).isEqualTo(2);
    }
}
