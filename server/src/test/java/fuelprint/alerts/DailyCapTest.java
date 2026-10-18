package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.Receipt;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The daily cap, read from {@code sms.json} (here 3 SMS a day). */
class DailyCapTest extends AlertsConfiguredSpringTest {

    static final Path FOLDER = newFolder("fp-alerts-cap-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        write(FOLDER, "{\"mode\":\"LOG_ONLY\",\"daily_cap\":3}");
        configure(registry, FOLDER);
    }

    private Receipt sendTest(String... areas) {
        ObjectNode payload = object().put("kind", "TEST");
        payload.set("area_ids", array(areas));
        return bus.send(command("send_alert", payload, null), PRESENTER);
    }

    private JsonNode lastNotification() {
        List<StoredRecord> all = notifications();
        return all.get(all.size() - 1).body();
    }

    @Test
    void overTheDailyCapTheNotificationIsSuppressedWithTheReasonAndNothingIsSent() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E");

        assertThat(sendTest("AREA-E").status()).isEqualTo(Receipt.Status.APPLIED);   // 2 of 3
        assertThat(lastNotification().path("state").asText()).isEqualTo("SENT");
        assertThat(gateway.sent).hasSize(2);

        assertThat(sendTest("AREA-E").status()).isEqualTo(Receipt.Status.APPLIED);   // would be 4 of 3
        JsonNode body = lastNotification();
        assertThat(body.path("state").asText()).isEqualTo("SUPPRESSED");
        assertThat(body.path("suppressed_reason").asText()).isEqualTo("DAILY_CAP");
        assertThat(body.path("recipients").asInt()).isEqualTo(2);
        assertThat(body.path("sent").asInt()).isZero();
        assertThat(gateway.sent).as("nothing more was sent").hasSize(2);
    }

    @Test
    void aSmallerSendThatStillFitsGoesOutAndTheCapIsExactNotApproximate() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E");
        signUp(VISITOR_1, NUMBER_C, "AREA-W");

        sendTest("AREA-E");                                   // 2 sent
        sendTest("AREA-W");                                   // 1 more = 3 = the cap, allowed
        assertThat(lastNotification().path("state").asText()).isEqualTo("SENT");
        assertThat(gateway.sent).hasSize(3);

        sendTest("AREA-W");                                   // 4th message of the day
        assertThat(lastNotification().path("suppressed_reason").asText()).isEqualTo("DAILY_CAP");
        assertThat(gateway.sent).hasSize(3);
    }

    @Test
    void theCapStartsAgainAtLocalMidnight() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E");
        sendTest("AREA-E");
        sendTest("AREA-E");
        assertThat(lastNotification().path("suppressed_reason").asText()).isEqualTo("DAILY_CAP");

        clock.advance(Duration.ofHours(14).plusMinutes(1));   // 00:01 IST the next day
        // the sign-ups ended with the old day, so sign up again
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E");
        sendTest("AREA-E");

        assertThat(lastNotification().path("state").asText()).isEqualTo("SENT");
        assertThat(gateway.sent).hasSize(4);
    }

    @Test
    void suppressedAndFailedNotificationsDoNotUseUpTheCap() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        // no sign-ups in AREA-S: suppressed NO_SUBSCRIBERS, sent 0
        for (int i = 0; i < 5; i++) {
            sendTest("AREA-S");
        }
        sendTest("AREA-E");
        sendTest("AREA-E");
        sendTest("AREA-E");

        assertThat(lastNotification().path("state").asText()).isEqualTo("SENT");
        assertThat(gateway.sent).hasSize(3);
    }

    @Test
    void automaticSmokeWarningsObeyTheCapToo() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E");
        sendTest("AREA-E");                                   // 2 of 3 used

        openInvestigation(List.of("AREA-E"));                 // would be 4 of 3

        JsonNode body = lastNotification();
        assertThat(body.path("kind").asText()).isEqualTo("SMOKE_WARNING");
        assertThat(body.path("suppressed_reason").asText()).isEqualTo("DAILY_CAP");
        assertThat(gateway.sent).hasSize(2);
    }
}
