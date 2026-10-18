package fuelprint.core.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.run.RunCommands;
import fuelprint.core.world.WorldLoop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class ReadApiTest extends CoreSpringTest {

    static final Session PRESENTER = Session.station("s-read", "laptop-1", Set.of(Role.PRESENTER));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired WorldLoop world;

    JsonNode getJson(String url, int status) throws Exception {
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertThat(response.getStatus()).as(url).isEqualTo(status);
        return mapper.readTree(response.getContentAsString());
    }

    String freshRunWithReadings() {
        Receipt receipt = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), RunCommands.RESET,
                null, null, JsonNodeFactory.instance.objectNode().put("preset_id", "SC1-NIGHT-PLUME").put("mode", "BLIND")),
                PRESENTER);
        bus.awaitIdle();
        for (int i = 0; i < 60; i++) {
            world.wallStep();
        }
        bus.awaitIdle();
        return receipt.recordRefs().stream().filter(r -> r.type().equals("run") && r.revision() == 1).findFirst()
                .orElseThrow().id();
    }

    @Test
    void currentRunAndSnapshotDescribeTheRunWithACursor() throws Exception {
        String runId = freshRunWithReadings();
        JsonNode current = getJson("/api/runs/current", 200);
        assertThat(current.path("run_id").asText()).isEqualTo(runId);
        assertThat(current.path("state").asText()).isEqualTo("RUNNING");
        assertThat(current.path("mode").asText()).isEqualTo("live");
        assertThat(current.path("presets")).hasSize(3);
        assertThat(current.path("sim_time_s").asDouble()).isEqualTo(3.0);

        JsonNode snapshot = getJson("/api/snapshot?run_id=" + runId, 200);
        assertThat(snapshot.path("cursor").asLong()).isPositive();
        assertThat(snapshot.path("server_epoch").asText()).hasSize(36);
        assertThat(snapshot.path("records").path("sensor")).hasSize(4);
        assertThat(snapshot.path("records").path("run").get(0).path("id").asText()).isEqualTo(runId);
        assertThat(snapshot.path("records").path("automation_settings")).hasSize(1);
        assertThat(snapshot.path("records").has("reading")).as("readings only on request").isFalse();
        assertThat(snapshot.path("latest_readings")).hasSize(4 * 3 + 2);
        JsonNode withReadings = getJson("/api/snapshot?run_id=" + runId + "&include_readings=true", 200);
        assertThat(withReadings.path("records").path("reading")).hasSize(3 * 14);
        assertThat(getJson("/api/snapshot", 200).path("run_id").asText()).isEqualTo(runId);
    }

    @Test
    void recordsCanBeReadOneByOneAndByRun() throws Exception {
        String runId = freshRunWithReadings();
        JsonNode sensors = getJson("/api/records/sensor?run_id=" + runId, 200);
        assertThat(sensors).hasSize(4);
        String id = sensors.get(0).path("id").asText();
        assertThat(getJson("/api/records/sensor/" + id, 200).path("label").asText()).isEqualTo("N1");
        assertThat(getJson("/api/records/sensor/" + id + "?revision=1", 200).path("revision").asInt()).isEqualTo(1);
        assertThat(getJson("/api/records/sensor/" + UUID.randomUUID(), 404).path("reason_code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(getJson("/api/records/automation_settings", 200)).hasSize(1);
        assertThat(getJson("/api/records/run", 200).size()).isPositive();
        assertThat(getJson("/api/records/sensor", 400).path("reason_code").asText()).isEqualTo("INVALID");
        assertThat(getJson("/api/records/sensor?run_id=nope", 400).path("reason_code").asText()).isEqualTo("INVALID");
    }

    @Test
    void theAutomationLogListsAutomaticReceiptsNewestFirst() throws Exception {
        automation.submit("test_auto_note", JsonNodeFactory.instance.objectNode().put("note_id", UUID.randomUUID().toString())
                .put("text", "log me")).join();
        JsonNode log = getJson("/api/automation-log?limit=5", 200);
        assertThat(log.size()).isPositive();
        JsonNode newest = log.get(0);
        assertThat(newest.path("command_type").asText()).isEqualTo("test_auto_note");
        assertThat(newest.path("received_utc").asText()).endsWith("Z");
        assertThat(newest.path("receipt").path("automatic").asBoolean()).isTrue();
        assertThat(newest.path("receipt").path("actor").asText()).isEqualTo("automation");
    }
}
