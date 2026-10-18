package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import fuelprint.core.command.Session;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** {@code WS /ws/pilot} over a real HTTP server: handshake checks, input, stale sequence, neutral, reconnect. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "fp.field.safety-loop=false")
class PilotSocketTest extends FieldSpringTest {

    @Value("${local.server.port}") int port;

    static class Client implements WebSocket.Listener {
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messages.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            return null;
        }
    }

    CompletableFuture<WebSocket> connect(Client client, String token, String origin) {
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header("Cookie", FieldTestConfig.FakeSessions.COOKIE + "=" + token);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return builder.buildAsync(URI.create("ws://localhost:" + port + "/ws/pilot"), client);
    }

    /** The handshake must fail with HTTP 403 (the cause chain carries the status). */
    void refused(CompletableFuture<WebSocket> attempt) {
        assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS)).hasStackTraceContaining("status code 403");
    }

    WebSocket open(Session session) throws Exception {
        String token = sessions.tokenOf(session.actorLabel()).orElseGet(() -> sessions.register(session));
        return connect(new Client(), token, null).get(5, TimeUnit.SECONDS);
    }

    String message(String lease, long sequence, boolean neutral, double north, double up) {
        return "{\"lease_id\":\"" + lease + "\",\"sequence\":" + sequence + ",\"neutral\":" + neutral
                + ",\"velocity_enu\":{\"east\":0,\"north\":" + north + ",\"up\":" + up + "},\"sent_utc\":\"2026-10-22T10:00:00.000Z\"}";
    }

    void sendAndWait(WebSocket socket, String lease, long sequence, boolean neutral, double north, double up) {
        socket.sendText(message(lease, sequence, neutral, north, up), true).join();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
                world.lease().map(l -> l.lastSequence() != null && l.lastSequence() >= sequence).orElse(false));
        time.advanceMillis(50);
    }

    @Test
    void theHandshakeNeedsASignedInPilotFromTheSameOrigin() throws Exception {
        approvedMission();
        Session visitor = sessions.createVisitor("visitor-ws1");
        String token = sessions.register(visitor);
        refused(connect(new Client(), null, null));
        refused(connect(new Client(), "no-such-token", null));
        refused(connect(new Client(), token, "http://evil.example"));
        String qc = sessions.register(QC);
        refused(connect(new Client(), qc, null));
        WebSocket ok = connect(new Client(), token, "http://localhost:" + port).get(5, TimeUnit.SECONDS);
        ok.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    @Test
    void inputOverTheSocketFliesTheDroneStaleIsAnsweredNeutralStopsAndReconnectStaysNeutral() throws Exception {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        sessions.register(visitor);
        Client client = new Client();
        WebSocket socket = connect(client, sessions.tokenOf(visitor.actorLabel()).orElseThrow(), null).get(5, TimeUnit.SECONDS);

        long sequence = 1;
        for (int i = 0; i < 20; i++) {
            sendAndWait(socket, lease, sequence++, false, 0, 3);
            tick();
        }
        assertThat(speed()).isGreaterThan(0.5);
        assertThat(world.vehicles().get(0).pilot().name()).isEqualTo("VISITOR");
        assertThat(world.lease().orElseThrow().lastSequence()).isEqualTo(sequence - 1);

        // A stale number is answered and changes nothing.
        socket.sendText(message(lease, 3, false, 0, 3), true).join();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> !client.messages.isEmpty());
        assertThat(client.messages.get(0)).contains("\"accepted\":false").contains("STALE_SEQUENCE");
        assertThat(world.lease().orElseThrow().lastSequence()).isEqualTo(sequence - 1);

        // One neutral message stops at once.
        sendAndWait(socket, lease, sequence++, true, 0, 0);
        assertThat(speed()).isZero();

        // Fly again, then drop the socket: the drone stops. A new socket stays neutral until fresh input.
        for (int i = 0; i < 10; i++) {
            sendAndWait(socket, lease, sequence++, false, 0, 3);
            tick();
        }
        assertThat(speed()).isPositive();
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "gone").join();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> speed() == 0);

        Client second = new Client();
        WebSocket again = connect(second, sessions.tokenOf(visitor.actorLabel()).orElseThrow(), null).get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 6; i++) {
            tick();
            assertThat(speed()).as("no replay after reconnect").isZero();
        }
        // The old sequence is still stale on the new socket.
        again.sendText(message(lease, sequence - 1, false, 0, 3), true).join();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> !second.messages.isEmpty());
        assertThat(second.messages.get(0)).contains("STALE_SEQUENCE");
        sendAndWait(again, lease, sequence++, false, 0, 3);
        tick();
        assertThat(speed()).isPositive();
        again.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void aSocketNotHoldingTheLeaseCannotMoveTheDroneAndAnOperatorKeyboardUsesTheSameSocket() throws Exception {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        Client client = new Client();
        WebSocket operator = connect(client, sessions.register(OPERATOR), null).get(5, TimeUnit.SECONDS);
        operator.sendText(message(lease, 1, false, 0, 3), true).join();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> !client.messages.isEmpty());
        assertThat(client.messages.get(0)).contains("NOT_HOLDER");
        tick();
        assertThat(speed()).isZero();

        // Keyboard flying: the operator takes over (visitor lease revoked) and flies on the same socket.
        assertThat(send(OPERATOR, "take_over", payload().put("vehicle_id", world.droneId())).applied()).isTrue();
        String operatorLease = world.lease().orElseThrow().leaseId();
        assertThat(world.lease().orElseThrow().kind().name()).isEqualTo("OPERATOR");
        for (int i = 1; i <= 10; i++) {
            sendAndWait(operator, operatorLease, i, false, 0, 3);
            tick();
        }
        assertThat(speed()).isPositive();
        assertThat(world.vehicles().get(0).leaseHolder()).isEqualTo(OPERATOR.actorLabel());
        operator.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
