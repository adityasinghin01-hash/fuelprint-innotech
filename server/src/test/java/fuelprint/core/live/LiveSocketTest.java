package fuelprint.core.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.LiveClient;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.privacy.PhoneGuard;
import fuelprint.core.run.RunCommands;
import fuelprint.core.store.RecordChange;
import fuelprint.core.world.WorldLoop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@code /ws/live} on a real port, live mode with the stub world driven by hand. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveSocketTest {

    static final Path DATA = CoreSpringTest.TempFolders.create("fp-live-test-");
    static final Session PRESENTER = Session.station("s-live", "laptop-1", Set.of(Role.PRESENTER));
    static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("fp.data-dir", DATA::toString);
        registry.add("fp.loop.auto", () -> "false");
        registry.add("fp.live.kept-events", () -> "200");
    }

    @LocalServerPort int port;
    @Autowired CommandBus bus;
    @Autowired WorldLoop world;
    @Autowired LiveHub hub;

    String reset() {
        Receipt receipt = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), RunCommands.RESET,
                null, null, JsonNodeFactory.instance.objectNode().put("preset_id", "SC1-NIGHT-PLUME").put("mode", "BLIND")),
                PRESENTER);
        bus.awaitIdle();
        return receipt.recordRefs().get(receipt.recordRefs().size() - 5).id();
    }

    void steps(int n) {
        for (int i = 0; i < n; i++) {
            world.wallStep();
        }
        bus.awaitIdle();
    }

    String httpGet(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void helloThenLiveEventsWithTheirRecordsThenTicksInTheContractEnvelope() throws Exception {
        try (LiveClient client = LiveClient.connect(port, null)) {
            JsonNode hello = client.await(m -> m.path("message_type").asText().equals("hello"), 5);
            assertThat(client.messages.get(0)).isEqualTo(hello);
            String epoch = MAPPER.readTree(httpGet("/api/runs/current")).path("server_epoch").asText();
            assertThat(hello.path("server_epoch").asText()).isEqualTo(epoch);
            assertThat(hello.path("payload").path("reload_snapshot").asBoolean()).isFalse();

            String runId = reset();
            steps(30);
            JsonNode created = client.await(m -> m.path("payload").path("event_type").asText().equals("RUN_CREATED"), 5);
            assertThat(created.path("schema_version").asText()).isEqualTo("fp/1");
            assertThat(created.path("run_id").asText()).isEqualTo(runId);
            assertThat(created.path("payload").path("record").path("state").asText()).isEqualTo("RUNNING");
            assertThat(created.path("payload").path("actor").asText()).isEqualTo("presenter@laptop-1");
            for (String field : List.of("schema_version", "message_type", "run_id", "server_epoch", "cursor",
                    "sim_time_s", "server_time_utc", "payload")) {
                assertThat(created.has(field)).as(field).isTrue();
            }
            JsonNode reading = client.await(m -> m.path("payload").path("record_type").asText().equals("reading"), 5);
            assertThat(reading.path("payload").path("automatic").asBoolean()).isTrue();

            JsonNode tick = client.await(m -> m.path("message_type").asText().equals("tick")
                    && m.path("run_id").asText().equals(runId), 5);
            assertThat(tick.path("payload").path("wind").path("from_deg").asDouble()).isEqualTo(270);
            assertThat(tick.path("payload").path("vehicles").get(0).path("label").asText()).isEqualTo("DRONE-1");
            assertThat(tick.path("payload").path("latest_readings").size()).isEqualTo(14);
            assertThat(tick.path("payload").path("latest_readings").get(0).path("sensor").asText()).isEqualTo("N1");

            long last = -1;
            for (JsonNode event : client.ofType("event")) {
                assertThat(event.path("cursor").asLong()).as("cursors only go up").isGreaterThan(last);
                last = event.path("cursor").asLong();
            }
        }
    }

    @Test
    void aReconnectWithAfterGetsEveryLaterEventOnceAndInOrder() throws Exception {
        reset();
        long after = hub.cursor();
        steps(60); // 3 s of readings: 42 reading events
        long end = hub.cursor();
        try (LiveClient client = LiveClient.connect(port, "after=" + after)) {
            client.await(m -> m.path("cursor").asLong() == end && m.path("message_type").asText().equals("event"), 5);
            List<Long> cursors = client.ofType("event").stream().map(m -> m.path("cursor").asLong()).toList();
            assertThat(cursors).first().isEqualTo(after + 1);
            for (int i = 1; i < cursors.size(); i++) {
                assertThat(cursors.get(i)).isEqualTo(cursors.get(i - 1) + 1);
            }
            assertThat(cursors).last().isEqualTo(end);
            // events from now on keep coming
            steps(20);
            client.await(m -> m.path("cursor").asLong() > end && m.path("message_type").asText().equals("event"), 5);
        }
    }

    @Test
    void aCursorOlderThanTheKeptWindowAsksForASnapshot() throws Exception {
        reset();
        steps(400); // well over 200 events
        try (LiveClient client = LiveClient.connect(port, "after=1")) {
            JsonNode hello = client.await(m -> m.path("message_type").asText().equals("hello"), 5);
            assertThat(hello.path("payload").path("reload_snapshot").asBoolean()).isTrue();
            Thread.sleep(200);
            assertThat(client.ofType("event")).as("no partial replay").isEmpty();
        }
        try (LiveClient client = LiveClient.connect(port, "after=" + (hub.cursor() + 1000))) {
            JsonNode hello = client.await(m -> m.path("message_type").asText().equals("hello"), 5);
            assertThat(hello.path("payload").path("reload_snapshot").asBoolean()).as("a cursor from another server").isTrue();
        }
    }

    @Test
    void aSocketFromAForeignOriginIsRefused() {
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "http://evil.example")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/live"), new java.net.http.WebSocket.Listener() { })
                .get(5, TimeUnit.SECONDS)).hasRootCauseMessage("Unexpected HTTP response status code 403");
    }

    @Test
    void noPhoneNumberLeavesTheCoreInAnApiResponseEventOrTick() throws Exception {
        String runId = reset();
        try (LiveClient client = LiveClient.connect(port, "run_id=" + runId)) {
            // A buggy package puts a phone number into a record: the guard masks it on every way out.
            String id = UUID.randomUUID().toString();
            bus.saveWithoutCommand("automation", HandlerResult.builder().save(RecordChange.create("leak_test", id, runId,
                    JsonNodeFactory.instance.objectNode().put("note", "call +919812345678 or +91 9876543210")),
                    "LEAK_TEST", null).build(), false).get(5, TimeUnit.SECONDS);
            steps(40);
            client.await(m -> m.path("payload").path("record_id").asText().equals(id), 5);
            client.await(m -> m.path("message_type").asText().equals("tick"), 5);

            List<String> outputs = new java.util.ArrayList<>(client.raw);
            outputs.add(httpGet("/api/records/leak_test/" + id));
            outputs.add(httpGet("/api/records/leak_test?run_id=" + runId));
            outputs.add(httpGet("/api/snapshot?run_id=" + runId + "&include_readings=true"));
            outputs.add(httpGet("/api/runs/current"));
            outputs.add(httpGet("/api/automation-log"));
            outputs.add(httpGet("/api/session"));
            for (String output : outputs) {
                assertThat(PhoneGuard.PHONE.matcher(output).find()).as(output.substring(0, Math.min(120, output.length())))
                        .isFalse();
            }
            assertThat(httpGet("/api/records/leak_test/" + id)).contains("+91 98xxxxxx78").contains("+91 98xxxxxx10");
        }
    }
}
