package fuelprint.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.RunEnded;
import fuelprint.FuelPrintApplication;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Done-when item: the real server is killed with SIGKILL in the middle of a run; after a restart every record it
 * had committed is still there and the run is PAUSED with reason INTERRUPTED ({@code RunEnded(INTERRUPTED)}).
 */
class KillRestartTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void killingTheServerMidRunKeepsEveryCommittedRecordAndMarksTheRunInterrupted() throws Exception {
        Path data = CoreSpringTest.TempFolders.create("fp-kill-");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        File log = data.resolve("server.log").toFile();
        Process server = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                FuelPrintApplication.class.getName(), "--fp.data-dir=" + data, "--server.port=" + port,
                "--server.address=127.0.0.1", "--spring.main.banner-mode=off")
                .redirectErrorStream(true).redirectOutput(log).start();
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        Set<String> committedReadings = new HashSet<>();
        String runId;
        try {
            waitUntilUp(http, base, server, log);
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/session/station"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"station_id\":\"laptop-1\",\"pin\":\"1111\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            String command = "{\"schema_version\":\"fp/1\",\"command_id\":\"" + UUID.randomUUID()
                    + "\",\"command_type\":\"create_run\",\"payload\":{\"preset_id\":\"SC1-NIGHT-PLUME\",\"mode\":\"BLIND\"}}";
            JsonNode receipt = MAPPER.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/api/commands"))
                    .header("Content-Type", "application/json").header("Cookie", cookie)
                    .POST(HttpRequest.BodyPublishers.ofString(command)).build(), HttpResponse.BodyHandlers.ofString()).body());
            if (!"APPLIED".equals(receipt.path("status").asText())) {
                // A run may still be active from nothing; fall back to reset.
                command = command.replace("create_run", "reset_run").replaceFirst("[0-9a-f-]{36}", UUID.randomUUID().toString());
                receipt = MAPPER.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/api/commands"))
                        .header("Content-Type", "application/json").header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString(command)).build(), HttpResponse.BodyHandlers.ofString()).body());
            }
            assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");
            runId = receipt.path("record_refs").get(0).path("id").asText();
            Thread.sleep(3500); // the real 20 Hz loop writes readings every second
            JsonNode readings = MAPPER.readTree(http.send(HttpRequest.newBuilder(
                    URI.create(base + "/api/records/reading?run_id=" + runId)).build(), HttpResponse.BodyHandlers.ofString()).body());
            readings.forEach(r -> committedReadings.add(r.path("id").asText()));
            assertThat(committedReadings.size()).isGreaterThanOrEqualTo(14 * 2);
        } finally {
            server.destroyForcibly(); // SIGKILL: no shutdown hook, no clean close
            server.waitFor(10, TimeUnit.SECONDS);
        }

        List<Object> runEvents = new CopyOnWriteArrayList<>();
        try (ConfigurableApplicationContext again = new SpringApplicationBuilder(FuelPrintApplication.class)
                .web(WebApplicationType.NONE)
                .listeners(event -> {
                    if (event instanceof PayloadApplicationEvent<?> payload && payload.getPayload() instanceof RunEnded ended) {
                        runEvents.add(ended);
                    }
                })
                .run("--fp.data-dir=" + data, "--fp.loop.auto=false", "--spring.main.banner-mode=off")) {
            RecordStore store = again.getBean(RecordStore.class);
            again.getBean(fuelprint.core.command.CommandBus.class).awaitIdle();
            StoredRecord run = store.get("run", runId).orElseThrow();
            assertThat(run.body().path("state").asText()).isEqualTo("PAUSED");
            assertThat(run.body().path("state_reason").asText()).isEqualTo("INTERRUPTED");
            assertThat(run.body().path("sim_time_s").asDouble()).isGreaterThanOrEqualTo(2.0);
            List<String> after = new ArrayList<>(store.list("reading", runId).stream().map(StoredRecord::id).toList());
            assertThat(after).containsAll(committedReadings);
            assertThat(store.list("sensor", runId)).hasSize(4);
            assertThat(runEvents).contains(new RunEnded(runId, RunEnded.Reason.INTERRUPTED));
        }
    }

    static void waitUntilUp(HttpClient http, String base, Process server, File log) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (!server.isAlive()) {
                throw new AssertionError("Server died: " + java.nio.file.Files.readString(log.toPath()));
            }
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs/current")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException notYet) {
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Server did not start");
    }
}
