package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.Receipt;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** send_alert with the REAL gateway pointed at a local mock of the Android app. */
class RealGatewayFlowTest extends AlertsConfiguredSpringTest {

    static final Path FOLDER = newFolder("fp-alerts-real-");
    static final MockGatewayServer SERVER = new MockGatewayServer();
    static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        write(FOLDER, "{\"mode\":\"REAL\",\"gateway_url\":\"" + SERVER.url()
                + "\",\"username\":\"stall\",\"password\":\"pw-123\",\"daily_cap\":80}");
        configure(registry, FOLDER);
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @BeforeEach
    void resetServer() {
        SERVER.reset();
    }

    private JsonNode lastNotification() {
        List<StoredRecord> all = notifications();
        return all.get(all.size() - 1).body();
    }

    private Receipt sendTest(String... areas) {
        ObjectNode payload = object().put("kind", "TEST");
        payload.set("area_ids", array(areas));
        return bus.send(command("send_alert", payload, null), PRESENTER);
    }

    private static String phoneOf(MockGatewayServer.Seen seen) {
        try {
            return MAPPER.readTree(seen.body()).path("phoneNumbers").get(0).asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyRecipientGetsOneRequestAndTheNotificationSaysReal() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E");

        openInvestigation(List.of("AREA-E"));

        assertThat(SERVER.requests).hasSize(2);
        assertThat(SERVER.requests).extracting(RealGatewayFlowTest::phoneOf).containsExactlyInAnyOrder(NUMBER_A, NUMBER_B);
        assertThat(SERVER.requests).allSatisfy(seen -> {
            assertThat(seen.path()).isEqualTo("/message");
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.authorization()).startsWith("Basic ");
        });
        JsonNode body = lastNotification();
        assertThat(body.path("state").asText()).isEqualTo("SENT");
        assertThat(body.path("gateway_mode").asText()).isEqualTo("REAL");
        assertThat(body.path("sent").asInt()).isEqualTo(2);
        assertThat(body.path("failed").asInt()).isZero();
    }

    @Test
    void oneFailedRecipientMakesTheNotificationPartialAfterTwoRetries() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E");
        SERVER.answerWith(seen -> phoneOf(seen).equals(NUMBER_B) ? 500 : 202);

        sendTest("AREA-E");

        JsonNode body = lastNotification();
        assertThat(body.path("state").asText()).isEqualTo("PARTIAL");
        assertThat(body.path("recipients").asInt()).isEqualTo(2);
        assertThat(body.path("sent").asInt()).isEqualTo(1);
        assertThat(body.path("failed").asInt()).isEqualTo(1);
        assertThat(body.path("gateway_mode").asText()).isEqualTo("REAL");
        long toB = SERVER.requests.stream().filter(seen -> phoneOf(seen).equals(NUMBER_B)).count();
        assertThat(toB).as("1 try + 2 retries").isEqualTo(3);
    }

    @Test
    void whenEveryRecipientFailsTheNotificationIsFailed() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        SERVER.answerWith(503);

        Receipt receipt = sendTest("AREA-E");

        assertThat(receipt.status()).as("the command itself is applied: the failure is recorded").isEqualTo(Receipt.Status.APPLIED);
        JsonNode body = lastNotification();
        assertThat(body.path("state").asText()).isEqualTo("FAILED");
        assertThat(body.path("sent").asInt()).isZero();
        assertThat(body.path("failed").asInt()).isEqualTo(1);
        assertThat(SERVER.requests).hasSize(3);
    }

    @Test
    void failedSendsDoNotCountTowardTheDailyCap() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        SERVER.answerWith(503);
        sendTest("AREA-E");
        SERVER.answerWith(202);

        sendTest("AREA-E");

        assertThat(lastNotification().path("state").asText()).isEqualTo("SENT");
    }

    @Test
    void theWordingTextIsWhatTheGatewayReceives() throws Exception {
        signUp(GUEST_1, NUMBER_A, "AREA-E");

        openInvestigation(List.of("AREA-E"));

        JsonNode request = MAPPER.readTree(SERVER.requests.get(0).body());
        assertThat(request.path("textMessage").path("text").asText()).isEqualTo(lastNotification().path("text").asText());
    }
}
