package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.ReasonCode;
import fuelprint.core.command.Receipt;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** purge_subscriptions, the start-up purge of expired sign-ups, and the midnight timer. */
class PurgeTest extends AlertsSpringTest {

    @Autowired AutomationBus automation;
    @Autowired PurgeScheduler scheduler;

    private StoredRecord subscription(String id) {
        return store.get("alert_subscription", id).orElseThrow();
    }

    private String state(String id) {
        return subscription(id).body().path("state").asText();
    }

    @Test
    void aPresenterPurgeDeletesEveryNumberAndMarksActiveSignUpsPurged() {
        String a = signUp(GUEST_1, NUMBER_A, "AREA-E");
        String b = signUp(VISITOR_1, NUMBER_B, "AREA-W");
        String removed = signUp(GUEST_2, NUMBER_C, "AREA-N");
        unsubscribe(GUEST_2, removed);

        Receipt receipt = bus.send(command("purge_subscriptions", object(), null), PRESENTER);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(receipt.recordRefs()).hasSize(2);
        assertThat(state(a)).isEqualTo("PURGED");
        assertThat(state(b)).isEqualTo("PURGED");
        assertThat(state(removed)).as("a removed sign-up stays REMOVED").isEqualTo("REMOVED");
        assertThat(subscription(a).revision()).isEqualTo(2);
        assertThat(subscription(a).body().path("phone_masked").asText()).isEqualTo("+91 98xxxxxx78");
        assertThat(contactRows()).isZero();
    }

    @Test
    void purgeAlsoDeletesNumbersThatHaveNoActiveSignUp() {
        jdbc.update("INSERT INTO alert_contact (subscription_id, phone_e164, owner_session_id, created_utc) VALUES (?, ?, ?, ?)",
                uuid(), NUMBER_D, "orphan", "2026-10-22T00:00:00.000Z");
        assertThat(contactRows()).isEqualTo(1);

        assertThat(bus.send(command("purge_subscriptions", object(), null), PRESENTER).status())
                .isEqualTo(Receipt.Status.APPLIED);

        assertThat(contactRows()).isZero();
    }

    @Test
    void purgingTwiceIsHarmless() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        bus.send(command("purge_subscriptions", object(), null), PRESENTER);

        Receipt second = bus.send(command("purge_subscriptions", object(), null), PRESENTER);

        assertThat(second.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(second.recordRefs()).isEmpty();
    }

    @Test
    void automationMayPurge() {
        String a = signUp(GUEST_1, NUMBER_A, "AREA-E");

        Receipt receipt = automation.submit("purge_subscriptions", object()).join();

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(receipt.actor()).isEqualTo("automation");
        assertThat(receipt.automatic()).isTrue();
        assertThat(state(a)).isEqualTo("PURGED");
        assertThat(subscription(a).createdBy()).isEqualTo("automation");
        assertThat(contactRows()).isZero();
    }

    @Test
    void guestsVisitorsAndOtherStationsCannotPurge() {
        String a = signUp(GUEST_1, NUMBER_A, "AREA-E");

        for (var session : new fuelprint.core.command.Session[] {GUEST_1, VISITOR_1, ANALYST, BOARD}) {
            Receipt receipt = bus.send(command("purge_subscriptions", object(), null), session);
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        }
        assertThat(state(a)).isEqualTo("ACTIVE");
        assertThat(contactRows()).isEqualTo(1);
    }

    @Test
    void aBadFlagIsRefusedAndChangesNothing() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");

        Receipt receipt = bus.send(command("purge_subscriptions", object().put("expired_only", "yes"), null), PRESENTER);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.INVALID);
        assertThat(contactRows()).isEqualTo(1);
    }

    @Test
    void expiredOnlyPurgesJustTheSignUpsWhoseDayHasEnded() {
        String old = signUp(GUEST_1, NUMBER_A, "AREA-E");
        clock.advance(Duration.ofHours(15));                        // 01:00 IST the next day: the first day has ended
        String fresh = signUp(GUEST_2, NUMBER_B, "AREA-W");

        ObjectNode payload = object().put("expired_only", true);
        Receipt receipt = bus.send(command("purge_subscriptions", payload, null), PRESENTER);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(state(old)).isEqualTo("PURGED");
        assertThat(state(fresh)).isEqualTo("ACTIVE");
        assertThat(contactRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phone_e164 FROM alert_contact", String.class)).isEqualTo(NUMBER_B);
    }

    @Test
    void atStartUpExpiredSignUpsArePurgedByAutomation() {
        String old = signUp(GUEST_1, NUMBER_A, "AREA-E");
        clock.advance(Duration.ofHours(15));
        int commandsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commands WHERE command_type = 'purge_subscriptions' AND automatic = 1", Integer.class);

        assertThat(scheduler.purgeExpiredAtStartup()).isTrue();
        settle();

        assertThat(state(old)).isEqualTo("PURGED");
        assertThat(subscription(old).createdBy()).isEqualTo("automation");
        assertThat(contactRows()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM commands WHERE command_type = 'purge_subscriptions' AND automatic = 1", Integer.class))
                .isEqualTo(commandsBefore + 1);
    }

    @Test
    void atStartUpNothingIsSubmittedWhenNothingHasExpired() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        int commandsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM commands WHERE command_type = 'purge_subscriptions'",
                Integer.class);

        assertThat(scheduler.purgeExpiredAtStartup()).isFalse();
        settle();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commands WHERE command_type = 'purge_subscriptions'",
                Integer.class)).isEqualTo(commandsBefore);
        assertThat(contactRows()).isEqualTo(1);
    }

    @Test
    void theMidnightPurgeRemovesEveryNumber() {
        String a = signUp(GUEST_1, NUMBER_A, "AREA-E");
        String b = signUp(VISITOR_1, NUMBER_B, "AREA-S");

        scheduler.purgeAtMidnight();
        settle();

        assertThat(state(a)).isEqualTo("PURGED");
        assertThat(state(b)).isEqualTo("PURGED");
        assertThat(subscription(a).createdBy()).isEqualTo("automation");
        assertThat(contactRows()).isZero();
    }

    @Test
    void theTimerFiresAtLocalMidnight() {
        String a = signUp(GUEST_1, NUMBER_A, "AREA-E");
        // A clock that reads one second before the next Indian midnight when the timer is created.
        Instant realNow = Instant.now();
        Instant almostMidnight = AlertTime.endOfLocalDay(realNow).minusSeconds(1);
        Clock shifted = Clock.offset(Clock.systemUTC(), Duration.between(realNow, almostMidnight));
        PurgeScheduler timer = new PurgeScheduler(automation, store, shifted, true);
        try {
            timer.onReady();       // nothing has expired by this clock, so only the midnight timer is set

            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(contactRows()).isZero());
            settle();
            assertThat(state(a)).isEqualTo("PURGED");
            assertThat(subscription(a).createdBy()).isEqualTo("automation");
        } finally {
            timer.stop();
        }
    }

    @Test
    void theClockZoneIsNotUsedForTheLocalDay() {
        assertThat(Clock.fixed(Instant.parse("2026-10-22T18:29:59Z"), ZoneOffset.UTC).instant())
                .isBefore(AlertTime.endOfLocalDay(Instant.parse("2026-10-22T10:00:00Z")));
    }
}
