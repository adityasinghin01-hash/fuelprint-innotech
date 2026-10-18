package fuelprint.core.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.api.ServerMode;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.LiveClient;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.CommandLog;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.privacy.PhoneGuard;
import fuelprint.core.run.Runs;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fake mode end to end: SC1, SC2 and SC3 play at 60x as if live, a headless socket client sees ticks with the drone
 * flying and every saved record, and each scenario ends as AGREEMENT section 10 says.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fp.mode=replay", "fp.replay.speed=60",
        "spring.profiles.active=real"}) // replay mode must still switch the stubs on
class ReplayModeTest {

    static final Path DATA = CoreSpringTest.TempFolders.create("fp-replay-test-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("fp.data-dir", DATA::toString);
    }

    @LocalServerPort int port;
    @Autowired RecordStore store;
    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired CommandLog commands;
    @Autowired ServerMode mode;
    @Autowired Environment environment;

    StoredRecord awaitRunEnd(String runId, double seconds) throws InterruptedException {
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < deadline) {
            StoredRecord run = store.get("run", runId).orElseThrow();
            if (!Runs.ACTIVE.contains(Runs.state(run))) {
                bus.awaitIdle();
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Run " + runId + " did not finish in " + seconds + " s");
    }

    StoredRecord last(String type, String runId) {
        List<StoredRecord> list = store.list(type, runId);
        assertThat(list).as(type).isNotEmpty();
        return list.get(list.size() - 1);
    }

    @Test
    void theThreeScenariosPlayAsIfLiveAndEndAsTheAgreementSays() throws Exception {
        assertThat(mode.isReplay()).isTrue();
        assertThat(environment.getActiveProfiles()).contains("stub");

        try (LiveClient client = LiveClient.connect(port, null)) {
            // SC1 starts by itself.
            JsonNode tick = client.await(m -> m.path("message_type").asText().equals("tick")
                    && m.path("payload").path("vehicles").size() == 1, 10);
            String sc1 = tick.path("run_id").asText();
            assertThat(store.get("run", sc1).orElseThrow().body().path("preset_id").asText()).isEqualTo("SC1-NIGHT-PLUME");

            // Ticks fly the drone: its height changes and it reaches the 35 m target.
            client.await(m -> m.path("message_type").asText().equals("tick")
                    && m.path("payload").path("vehicles").path(0).path("pose").path("up").asDouble() > 30, 15);
            client.await(m -> m.path("message_type").asText().equals("tick")
                    && m.path("payload").path("vehicles").path(0).path("pilot").asText().equals("VISITOR"), 5);

            // A teammate's human command still runs in fake mode; automation is held back (the recording has it).
            Session analyst = Session.station("s-replay", "laptop-1", Set.of(Role.ANALYST));
            Receipt human = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "test_auto_note",
                    sc1, null, JsonNodeFactory.instance.objectNode().put("note_id", UUID.randomUUID().toString())), analyst);
            assertThat(human.status()).isEqualTo(Receipt.Status.APPLIED);
            Receipt auto = automation.submit("test_auto_note", JsonNodeFactory.instance.objectNode()
                    .put("note_id", UUID.randomUUID().toString())).get(5, TimeUnit.SECONDS);
            assertThat(auto.reasonCode()).isEqualTo(AutomationBus.REPLAY_MODE);

            StoredRecord sc1End = awaitRunEnd(sc1, 60);
            assertThat(Runs.state(sc1End)).isEqualTo("COMPLETED");
            assertThat(last("analysis", sc1).body().path("outcome").asText()).isEqualTo("PROVISIONAL_SINGLE");
            assertThat(last("analysis", sc1).body().path("learned_order").get(0).path("explanation_id").asText())
                    .isEqualTo("S02");
            assertThat(store.list("analysis", sc1).get(0).body().path("learned_status").asText()).isEqualTo("OK");
            assertThat(last("finding", sc1).body().path("outcome").asText()).isEqualTo("SUPPORTED_WITHIN_SCOPE");
            assertThat(last("mission", sc1).body().path("pilot").asText()).isEqualTo("VISITOR");
            assertThat(last("anomaly", sc1).createdBy()).isEqualTo("automation");
            assertThat(store.list("wording", sc1)).isNotEmpty();
            assertThat(store.list("trigger", sc1).get(0).body().path("sensor").asText())
                    .isEqualTo(store.list("sensor", sc1).get(1).id());
            assertThat(store.list("notification", null)).extracting(n -> n.body().path("gateway_mode").asText())
                    .contains("LOG_ONLY");
            assertThat(store.list("model_version", null)).hasSize(3);
            assertThat(store.list("automation_settings", null)).hasSize(1);
            assertThat(store.revisions("investigation", store.list("investigation", sc1).get(0).id()).get(0).createdBy())
                    .as("opened automatically").isEqualTo("automation");
            assertThat(store.list("reading", sc1).size()).isGreaterThan(400 * 14);
            var autoLog = commands.automationEntries(sc1, 500);
            assertThat(autoLog).extracting(CommandLog.LogEntry::commandType)
                    .contains("open_investigation", "run_analysis", "propose_mission", "seal_export", "send_alert");
            assertThat(autoLog).allSatisfy(e -> assertThat(e.receipt().automatic()).isTrue());

            String sc2 = Runs.active(store.listAll("run")).orElseThrow().id();
            assertThat(store.get("run", sc2).orElseThrow().body().path("parent_run_id").asText()).isEqualTo(sc1);
            awaitRunEnd(sc2, 60);
            assertThat(store.list("screening_record", sc2)).anySatisfy(s -> {
                assertThat(s.body().path("channel").asText()).isEqualTo("SO2_ppb");
                assertThat(s.body().path("decision").asText()).isEqualTo("REJECTED");
            });
            assertThat(store.list("screening_record", sc2)).anySatisfy(s ->
                    assertThat(s.body().path("decision").asText()).isEqualTo("VALID"));
            assertThat(last("finding", sc2).body().path("outcome").asText()).isEqualTo("REJECTED_FOR_USE");
            assertThat(commands.automationEntries(sc2, 500)).anySatisfy(e -> {
                assertThat(e.commandType()).isEqualTo("retrain_model");
                assertThat(e.receipt().reasonText()).isEqualTo("SO2 rejected, not a lesson");
            });

            String sc3 = Runs.active(store.listAll("run")).orElseThrow().id();
            awaitRunEnd(sc3, 60);
            assertThat(store.get("run", sc3).orElseThrow().body().path("preset_id").asText()).isEqualTo("SC3-ABOVE-THE-BOXES");
            assertThat(store.list("trigger", sc3)).as("no alarm in SC3").isEmpty();
            assertThat(store.list("anomaly", sc3)).isEmpty();
            assertThat(last("analysis", sc3).body().path("outcome").asText()).isEqualTo("AMBIGUOUS_OR_OPTIONAL_MIXTURE");
            assertThat(last("finding", sc3).body().path("outcome").asText()).isEqualTo("INCONCLUSIVE");
            assertThat(last("mission", sc3).body().path("pilot").asText()).isEqualTo("AUTOPILOT");
            assertThat(store.list("investigation", sc3).get(0).body().path("warned_area_ids")).isEmpty();

            // Every recorded message is privacy-clean.
            for (String raw : client.raw) {
                assertThat(PhoneGuard.PHONE.matcher(raw).find()).isFalse();
            }
            assertThat(client.ofType("event").size()).isGreaterThan(1000);
        }
    }
}
