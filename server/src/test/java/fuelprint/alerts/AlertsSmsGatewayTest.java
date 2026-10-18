package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.GatewayMode;
import fuelprint.api.SendResult;
import fuelprint.api.SendStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The SMS gateway: LOG_ONLY, and REAL against a local mock of the Android app's local server. */
class AlertsSmsGatewayTest {

    static final String PHONE = "+919812345678";
    static final String TEXT = "FuelPrint DEMO (simulated): smoke may be heading toward East colony. Demo only.";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static MockGatewayServer server;
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeAll
    static void start() {
        server = new MockGatewayServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void setUp() {
        server.reset();
        logger = (Logger) LoggerFactory.getLogger("fuelprint.alerts");
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private AlertsSmsGateway real() {
        return new AlertsSmsGateway(SmsSettings.real(server.url(), "sms-user", "s3cret-pass", 80), MAPPER, 0);
    }

    private String logText() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            all.append(event.getFormattedMessage()).append('\n');
        }
        return all.toString();
    }

    // ---- request shape ----

    @Test
    void sendsTheDocumentedLocalServerRequest() throws Exception {
        SendResult result = real().send(PHONE, TEXT);

        assertThat(result.status()).isEqualTo(SendStatus.SENT);
        assertThat(result.gatewayMode()).isEqualTo(GatewayMode.REAL);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.failureReason()).isNull();
        assertThat(server.requests).hasSize(1);
        MockGatewayServer.Seen seen = server.requests.get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/message");
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("sms-user:s3cret-pass".getBytes(StandardCharsets.UTF_8));
        assertThat(seen.authorization()).isEqualTo(expectedAuth);
        assertThat(seen.contentType()).startsWith("application/json");
        JsonNode body = MAPPER.readTree(seen.body());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("textMessage", "phoneNumbers");
        assertThat(body.path("textMessage").path("text").asText()).isEqualTo(TEXT);
        assertThat(body.path("phoneNumbers")).hasSize(1);
        assertThat(body.path("phoneNumbers").get(0).asText()).isEqualTo(PHONE);
    }

    @Test
    void theGatewayAddressMayBeGivenWithOrWithoutTheMessagePath() {
        for (String url : List.of(server.url(), server.url() + "/", server.url() + "/message")) {
            server.reset();
            SendResult result = new AlertsSmsGateway(SmsSettings.real(url, "u", "p", 80), MAPPER, 0).send(PHONE, TEXT);
            assertThat(result.status()).as(url).isEqualTo(SendStatus.SENT);
            assertThat(server.requests).hasSize(1);
            assertThat(server.requests.get(0).path()).isEqualTo("/message");
        }
    }

    @Test
    void anyNon2xxAnswerIsAFailureThatIsRetriedTwice() {
        for (int status : new int[] {400, 401, 403, 409, 500, 503}) {
            server.reset();
            server.answerWith(status);
            SendResult result = real().send(PHONE, TEXT);
            assertThat(result.status()).as("HTTP " + status).isEqualTo(SendStatus.FAILED);
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.failureReason()).isEqualTo("HTTP_" + status);
            assertThat(server.requests).as("1 try + 2 retries").hasSize(3);
        }
    }

    @Test
    void aFailureFollowedByAnAnswerIsRetriedAndCountedAsSent() {
        AtomicInteger calls = new AtomicInteger();
        server.answerWith(seen -> calls.incrementAndGet() < 3 ? 503 : 202);

        SendResult result = real().send(PHONE, TEXT);

        assertThat(result.status()).isEqualTo(SendStatus.SENT);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(server.requests).hasSize(3);
    }

    @Test
    void successOnTheSecondTryStopsRetrying() {
        AtomicInteger calls = new AtomicInteger();
        server.answerWith(seen -> calls.incrementAndGet() < 2 ? 500 : 202);

        SendResult result = real().send(PHONE, TEXT);

        assertThat(result.status()).isEqualTo(SendStatus.SENT);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(server.requests).hasSize(2);
    }

    @Test
    void noConnectionIsRetriedTwiceThenFailed() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AlertsSmsGateway gateway = new AlertsSmsGateway(
                SmsSettings.real("http://127.0.0.1:" + closedPort, "u", "p", 80), MAPPER, 0);

        SendResult result = gateway.send(PHONE, TEXT);

        assertThat(result.status()).isEqualTo(SendStatus.FAILED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.failureReason()).isEqualTo("NO_CONNECTION");
    }

    @Test
    void aSlowAnswerTimesOutAndIsRetriedTwice() {
        server.delay(1500);
        AlertsSmsGateway gateway = new AlertsSmsGateway(SmsSettings.real(server.url(), "u", "p", 80), MAPPER, 0,
                Duration.ofMillis(300));

        SendResult result = gateway.send(PHONE, TEXT);

        assertThat(result.status()).isEqualTo(SendStatus.FAILED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.failureReason()).isEqualTo("TIMEOUT");
        assertThat(server.requests).hasSize(3);
    }

    @Test
    void theRealTimeoutIsFiveSeconds() {
        assertThat(AlertsSmsGateway.TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(AlertsSmsGateway.MAX_ATTEMPTS).isEqualTo(3);
    }

    @Test
    void realModeWithIncompleteSettingsSendsNothing() {
        for (SmsSettings settings : List.of(
                SmsSettings.real(null, "u", "p", 80),
                SmsSettings.real("not a url", "u", "p", 80),
                SmsSettings.real("ftp://127.0.0.1", "u", "p", 80),
                SmsSettings.real(server.url(), null, "p", 80),
                SmsSettings.real(server.url(), "u", null, 80))) {
            SendResult result = new AlertsSmsGateway(settings, MAPPER, 0).send(PHONE, TEXT);
            assertThat(result.status()).isEqualTo(SendStatus.FAILED);
            assertThat(result.failureReason()).isEqualTo("GATEWAY_NOT_CONFIGURED");
        }
        assertThat(server.requests).isEmpty();
    }

    // ---- LOG_ONLY ----

    @Test
    void logOnlySendsNothingAndLogsTheMaskedNumberAndTheText() {
        AlertsSmsGateway gateway = new AlertsSmsGateway(SmsSettings.logOnly(), MAPPER, 0);

        SendResult result = gateway.send(PHONE, TEXT);

        assertThat(result.status()).isEqualTo(SendStatus.LOGGED_ONLY);
        assertThat(result.gatewayMode()).isEqualTo(GatewayMode.LOG_ONLY);
        assertThat(server.requests).isEmpty();
        assertThat(logText()).contains("+91 98xxxxxx78").contains(TEXT).doesNotContain(PHONE).doesNotContain("9812345678");
    }

    @Test
    void realModeLogsNeverContainTheFullNumberOrThePassword() {
        server.answerWith(500);
        real().send(PHONE, TEXT);
        server.answerWith(202);
        real().send(PHONE, TEXT);

        assertThat(logs.list).isNotEmpty();
        assertThat(logText()).contains("+91 98xxxxxx78").doesNotContain(PHONE).doesNotContain("9812345678")
                .doesNotContain("s3cret-pass").doesNotContain("sms-user");
    }

    // ---- settings file ----

    @Test
    void settingsDefaultToLogOnlyWithACapOfEighty(@TempDir Path dir) {
        SmsSettings missing = SmsSettings.load(dir.resolve("nope.json"), MAPPER);
        assertThat(missing.mode()).isEqualTo(GatewayMode.LOG_ONLY);
        assertThat(missing.dailyCap()).isEqualTo(80);
        assertThat(SmsSettings.logOnly().dailyCap()).isEqualTo(80);
    }

    @Test
    void settingsReadAllFiveFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sms.json");
        Files.writeString(file, "{\"mode\":\"REAL\",\"gateway_url\":\"http://192.168.1.50:8080\","
                + "\"username\":\"sms\",\"password\":\"pw\",\"daily_cap\":25}");

        SmsSettings settings = SmsSettings.load(file, MAPPER);

        assertThat(settings.mode()).isEqualTo(GatewayMode.REAL);
        assertThat(settings.dailyCap()).isEqualTo(25);
        assertThat(settings.username()).isEqualTo("sms");
        assertThat(settings.password()).isEqualTo("pw");
        assertThat(settings.messageUri().toString()).isEqualTo("http://192.168.1.50:8080/message");
        assertThat(settings.ready()).isTrue();
        assertThat(settings.toString()).doesNotContain("pw").doesNotContain("192.168");
    }

    @Test
    void aBrokenOrUnclearFileMeansLogOnly(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");
        assertThat(SmsSettings.load(broken, MAPPER).mode()).isEqualTo(GatewayMode.LOG_ONLY);

        Path odd = dir.resolve("odd.json");
        Files.writeString(odd, "{\"mode\":\"SEND_EVERYTHING\",\"daily_cap\":\"many\"}");
        SmsSettings settings = SmsSettings.load(odd, MAPPER);
        assertThat(settings.mode()).isEqualTo(GatewayMode.LOG_ONLY);
        assertThat(settings.dailyCap()).isEqualTo(80);

        Path lower = dir.resolve("lower.json");
        Files.writeString(lower, "{\"mode\":\"real\"}");
        SmsSettings lowerCase = SmsSettings.load(lower, MAPPER);
        assertThat(lowerCase.mode()).isEqualTo(GatewayMode.REAL);
        assertThat(lowerCase.ready()).as("REAL without address and credentials is not ready").isFalse();
    }

    @Test
    void theCommittedExampleFileIsLogOnlyAndNotTheRealFile() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("contracts/AGREEMENT.md"))) {
            root = root.getParent();
        }
        Path example = root.resolve("server/config/sms.example.json");
        assertThat(example).exists();
        JsonNode json = MAPPER.readTree(example.toFile());
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("mode", "gateway_url", "username", "password", "daily_cap");
        assertThat(json.path("mode").asText()).isEqualTo("LOG_ONLY");
        assertThat(json.path("daily_cap").asInt()).isEqualTo(80);
        assertThat(root.resolve("server/config/sms.json")).doesNotExist();
        assertThat(Files.readString(root.resolve(".gitignore"))).contains("server/config/sms.json");
        assertThat(SmsSettings.load(example, MAPPER).mode()).isEqualTo(GatewayMode.LOG_ONLY);
    }
}
