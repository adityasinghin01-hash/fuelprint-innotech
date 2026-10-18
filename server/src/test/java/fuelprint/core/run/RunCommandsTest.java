package fuelprint.core.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.RunEnded;
import fuelprint.api.RunPaused;
import fuelprint.api.RunStarted;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.ReasonCode;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.core.world.WorldLoop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunCommandsTest extends CoreSpringTest {

    static final Session PRESENTER = Session.station("s-run-test", "laptop-1", Set.of(Role.PRESENTER, Role.ANALYST));
    static final Session VISITOR = Session.human("s-visitor", "visitor-7f3a", Set.of(Role.VISITOR));

    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired RecordStore store;
    @Autowired WorldLoop world;

    static ObjectNode preset(String preset, String mode) {
        return JsonNodeFactory.instance.objectNode().put("preset_id", preset).put("mode", mode);
    }

    Receipt send(String type, ObjectNode payload, Long expected, Session session) {
        Receipt receipt = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), type, null,
                expected, payload), session);
        bus.awaitIdle();
        return receipt;
    }

    /** Resets to a fresh SC1 run and returns it. */
    StoredRecord freshRun() {
        Receipt receipt = send(RunCommands.RESET, preset("SC1-NIGHT-PLUME", "BLIND"), null, PRESENTER);
        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        String runId = receipt.recordRefs().stream().filter(r -> r.type().equals("run") && r.revision() == 1)
                .findFirst().orElseThrow().id();
        return store.get("run", runId).orElseThrow();
    }

    @Test
    void createRunMakesANewRunningRunWithFourSensorsAndStartsTheWorld() {
        StoredRecord run = freshRun();
        assertThat(run.body().path("state").asText()).isEqualTo("RUNNING");
        assertThat(run.body().path("preset_id").asText()).isEqualTo("SC1-NIGHT-PLUME");
        assertThat(run.body().path("seed").asLong()).isEqualTo(1001);
        assertThat(run.runId()).isEqualTo(run.id());
        assertThat(run.createdBy()).isEqualTo("presenter@laptop-1");
        List<StoredRecord> sensors = store.list("sensor", run.id());
        assertThat(sensors).extracting(s -> s.body().path("label").asText()).containsExactly("N1", "N2", "N3", "N4");
        assertThat(sensors.get(1).body().path("inlet").path("east").asDouble()).isEqualTo(120);
        assertThat(sensors.get(1).body().path("inlet").path("up").asDouble()).isEqualTo(3);
        assertThat(world.isLoaded(run.id())).isTrue();
        assertThat(RunEventsRecorder.EVENTS).contains(new RunStarted(run.id(), "SC1-NIGHT-PLUME", "BLIND"));
    }

    @Test
    void onlyOneRunIsActiveAndCreateNeverReusesOne() {
        StoredRecord run = freshRun();
        Receipt second = send(RunCommands.CREATE, preset("SC2-BLOCKED-INLET", "BLIND"), null, PRESENTER);
        assertThat(second.reasonCode()).isEqualTo(RunCommands.RUN_ACTIVE);
        long active = store.listAll("run").stream().filter(r -> Runs.ACTIVE.contains(Runs.state(r))).count();
        assertThat(active).isEqualTo(1);
        assertThat(Runs.active(store.listAll("run")).orElseThrow().id()).isEqualTo(run.id());
    }

    @Test
    void pauseAndResumeFollowTheStateMachineWithRevisionChecks() {
        StoredRecord run = freshRun();
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("run_id", run.id());

        Receipt stale = send(RunCommands.PAUSE, payload, 0L, PRESENTER);
        assertThat(stale.reasonCode()).isEqualTo(ReasonCode.STALE_REVISION);

        Receipt paused = send(RunCommands.PAUSE, payload, 1L, PRESENTER);
        assertThat(paused.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(Runs.state(store.get("run", run.id()).orElseThrow())).isEqualTo("PAUSED");
        assertThat(world.isRunning()).isFalse();
        assertThat(RunEventsRecorder.EVENTS).contains(new RunPaused(run.id()));

        assertThat(send(RunCommands.PAUSE, payload, null, PRESENTER).reasonCode()).isEqualTo(RunCommands.WRONG_STATE);
        Receipt resumed = send(RunCommands.RESUME, payload, 2L, PRESENTER);
        assertThat(resumed.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(world.isRunning()).isTrue();
        assertThat(send(RunCommands.RESUME, payload, null, PRESENTER).reasonCode()).isEqualTo(RunCommands.WRONG_STATE);
    }

    @Test
    void resetAbortsTheOldRunAndStartsANewOneWithItAsParent() {
        StoredRecord old = freshRun();
        RunEventsRecorder.EVENTS.clear();
        Receipt reset = send(RunCommands.RESET, preset("SC3-ABOVE-THE-BOXES", "TEACHING"), null, PRESENTER);
        assertThat(reset.status()).isEqualTo(Receipt.Status.APPLIED);

        StoredRecord oldNow = store.get("run", old.id()).orElseThrow();
        assertThat(Runs.state(oldNow)).isEqualTo("ABORTED");
        assertThat(oldNow.body().path("state_reason").asText()).isEqualTo("RESET");
        StoredRecord fresh = Runs.active(store.listAll("run")).orElseThrow();
        assertThat(fresh.id()).isNotEqualTo(old.id());
        assertThat(fresh.body().path("parent_run_id").asText()).isEqualTo(old.id());
        assertThat(fresh.body().path("mode").asText()).isEqualTo("TEACHING");
        assertThat(RunEventsRecorder.EVENTS).containsExactly(new RunEnded(old.id(), RunEnded.Reason.RESET),
                new RunStarted(fresh.id(), "SC3-ABOVE-THE-BOXES", "TEACHING"));
        assertThat(world.isLoaded(fresh.id())).isTrue();
    }

    @Test
    void runCommandsAreForThePresenterOnlyAndNeverAutomation() throws Exception {
        assertThat(send(RunCommands.RESET, preset("SC1-NIGHT-PLUME", "BLIND"), null, VISITOR).reasonCode())
                .isEqualTo(ReasonCode.NOT_ALLOWED);
        Receipt auto = automation.submit(RunCommands.RESET, preset("SC1-NIGHT-PLUME", "BLIND")).get(10, TimeUnit.SECONDS);
        assertThat(auto.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(auto.automatic()).isTrue();
    }

    @Test
    void unknownPresetsAndModesAreInvalid() {
        assertThat(send(RunCommands.RESET, preset("SC9", "BLIND"), null, PRESENTER).reasonCode())
                .isEqualTo(ReasonCode.INVALID);
        assertThat(send(RunCommands.RESET, preset("SC1-NIGHT-PLUME", "OPEN"), null, PRESENTER).reasonCode())
                .isEqualTo(ReasonCode.INVALID);
    }

    @Test
    void sameRunCommandRetriedAppliesOnce() {
        freshRun();
        Command reset = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), RunCommands.RESET, null, null,
                preset("SC2-BLOCKED-INLET", "BLIND"));
        Receipt first = bus.send(reset, PRESENTER);
        int runsAfterFirst = store.listAll("run").size();
        Receipt again = bus.send(reset, PRESENTER);
        bus.awaitIdle();
        assertThat(again).isEqualTo(first);
        assertThat(store.listAll("run")).hasSize(runsAfterFirst);
    }
}
