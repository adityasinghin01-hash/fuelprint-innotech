package fuelprint.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.api.Velocity;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.run.RunCommands;
import fuelprint.core.stub.StubVehicles;
import fuelprint.core.store.EventLog;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import fuelprint.api.WorldStepListener;

/** The world loop with the stub science, driven step by step. */
@Import(WorldLoopTest.Transition.class)
class WorldLoopTest extends CoreSpringTest {

    static final Session PRESENTER = Session.station("s-world-test", "laptop-2", Set.of(Role.PRESENTER));

    @Autowired CommandBus bus;
    @Autowired WorldLoop world;
    @Autowired RecordStore store;
    @Autowired EventLog events;
    @Autowired StubVehicles vehicles;
    @Autowired JdbcTemplate jdbc;
    @Autowired Transition transition;

    static class Transition implements WorldStepListener {
        Runnable action;
        @Override
        public void onStep(double time, double dt) {
            if (action != null) {
                Runnable next = action;
                action = null;
                next.run();
            }
        }
    }

    @AfterEach
    void standStill() {
        transition.action = null;
        vehicles.setVelocity(null);
        world.releaseTimeScale("test");
        world.stopThread();
    }

    String freshRun(String preset) {
        Receipt receipt = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), RunCommands.RESET,
                null, null, JsonNodeFactory.instance.objectNode().put("preset_id", preset).put("mode", "BLIND")), PRESENTER);
        bus.awaitIdle();
        return receipt.recordRefs().stream().filter(r -> r.type().equals("run") && r.revision() == 1).findFirst()
                .orElseThrow().id();
    }

    void steps(int n) {
        for (int i = 0; i < n; i++) {
            world.wallStep();
        }
        bus.awaitIdle();
    }

    Map<String, Long> readingCounts(String runId) {
        return store.list("reading", runId).stream().collect(Collectors.groupingBy(
                r -> r.body().path("sensor_or_vehicle_id").asText() + "/" + r.body().path("channel").asText(),
                Collectors.counting()));
    }

    @Test
    void sixtySimulatedSecondsGiveSixtyReadingsPerSensorChannelAndTicksAtTenHertz() {
        String runId = freshRun("SC1-NIGHT-PLUME");
        long ticksBefore = world.ticks();

        steps(1200); // 1200 wall steps of 50 ms = 60 s at time scale 1

        assertThat(world.simTimeS()).isEqualTo(60.0);
        Map<String, Long> counts = readingCounts(runId);
        List<StoredRecord> sensors = store.list("sensor", runId);
        for (StoredRecord sensor : sensors) {
            for (String channel : WorldProfile.SENSOR_CHANNELS) {
                assertThat(counts.get(sensor.id() + "/" + channel)).as(sensor.body().path("label") + " " + channel)
                        .isEqualTo(60);
            }
        }
        assertThat(counts.get(StubVehicles.DRONE_ID + "/SO2_ppb")).isEqualTo(60);
        assertThat(counts).hasSize(4 * 3 + 2);
        assertThat(world.ticks() - ticksBefore).as("one tick every 2 wall steps = 10 Hz").isEqualTo(600);

        StoredRecord reading = store.list("reading", runId).get(0);
        assertThat(reading.createdBy()).isEqualTo("automation");
        assertThat(reading.body().path("origin").asText()).isEqualTo("SYNTHETIC_TEACHING");
        assertThat(reading.body().has("raw_value")).isTrue();
        assertThat(events.forRecord("reading", reading.id())).singleElement()
                .satisfies(e -> assertThat(e.actor()).isEqualTo("automation"));
        // Stub detector: one SO2 window per box every 30 s.
        assertThat(store.list("window", runId)).hasSize(2 * 4);
    }

    @Test
    void theDetectorTriggerIsSavedAsAutomationWhenN2StaysHigh() {
        String runId = freshRun("SC1-NIGHT-PLUME");
        steps(20 * 120);
        List<StoredRecord> triggers = store.list("trigger", runId);
        assertThat(triggers).singleElement().satisfies(t -> {
            assertThat(t.createdBy()).isEqualTo("automation");
            assertThat(t.body().path("rule").asText()).isEqualTo("TEACH-DETECT-1");
            String n2 = store.list("sensor", runId).get(1).id();
            assertThat(t.body().path("sensor").asText()).isEqualTo(n2);
            assertThat(t.body().path("intervals").get(0).isArray()).isTrue();
        });
    }

    @Test
    void timeScaleFifteenIsRefusedWhileAVehicleMoves() {
        String runId = freshRun("SC1-NIGHT-PLUME");
        vehicles.setVelocity(new Velocity(1.2, 0, 0));
        assertThatThrownBy(() -> world.requestTimeScale("test", 15)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> world.requestTimeScale("test", 4)).isInstanceOf(IllegalArgumentException.class);
        steps(20);
        assertThat(world.timeScale()).isEqualTo(1);
        assertThat(world.simTimeS()).isEqualTo(1.0);

        vehicles.setVelocity(new Velocity(0.01, 0, 0)); // under 0.05 m/s counts as standing still
        world.requestTimeScale("test", 15);
        steps(20);
        assertThat(world.timeScale()).isEqualTo(15);
        assertThat(world.simTimeS()).isEqualTo(16.0);
        // Readings still come once per simulated second at 15x.
        assertThat(readingCounts(runId).get(store.list("sensor", runId).get(0).id() + "/SO2_ppb")).isEqualTo(16);

        vehicles.setVelocity(new Velocity(0, 2.0, 0)); // starts moving: back to 1 at once
        steps(1);
        assertThat(world.timeScale()).isEqualTo(1);
        assertThat(world.simTimeS()).isEqualTo(16.05);
    }

    @Test
    void releasingSpeedUpDuringAStepStopsTheExtraStepsImmediately() {
        freshRun("SC1-NIGHT-PLUME");
        world.requestTimeScale("test", 15);
        transition.action = () -> world.releaseTimeScale("test");
        steps(1);
        assertThat(world.simTimeS()).isEqualTo(0.05);
        assertThat(world.timeScale()).isEqualTo(1);
    }

    @Test
    void movementStartingDuringAStepStopsTheExtraStepsImmediately() {
        freshRun("SC1-NIGHT-PLUME");
        world.requestTimeScale("test", 15);
        transition.action = () -> vehicles.setVelocity(new Velocity(1, 0, 0));
        steps(1);
        assertThat(world.simTimeS()).isEqualTo(0.05);
        assertThat(world.timeScale()).isEqualTo(1);
    }

    @Test
    void fastClockStillRecordsAtExactConsecutiveSimulatedSeconds() {
        String runId = freshRun("SC1-NIGHT-PLUME");
        world.requestTimeScale("test", 15);
        steps(40); // 2 wall seconds, 30 simulation seconds
        String sensor = store.list("sensor", runId).get(0).id();
        List<Double> times = store.list("reading", runId).stream()
                .filter(r -> sensor.equals(r.body().path("sensor_or_vehicle_id").asText())
                        && "SO2_ppb".equals(r.body().path("channel").asText()))
                .map(r -> r.body().path("sim_time_s").asDouble()).toList();
        assertThat(times).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> (double) i).toList());
    }

    @Test
    void thePausedWorldStandsStillButTicksContinue() {
        String runId = freshRun("SC1-NIGHT-PLUME");
        steps(40);
        world.pause(runId);
        long ticks = world.ticks();
        steps(40);
        assertThat(world.simTimeS()).isEqualTo(2.0);
        assertThat(world.ticks() - ticks).isEqualTo(20);
    }

    @Test
    void theLoopThreadTicksTenTimesASecondInWallTime() throws Exception {
        freshRun("SC1-NIGHT-PLUME");
        long ticks = world.ticks();
        long start = System.nanoTime();
        world.startThread();
        Thread.sleep(2000);
        world.stopThread();
        double seconds = (System.nanoTime() - start) / 1e9;
        double rate = (world.ticks() - ticks) / seconds;
        assertThat(rate).as("ticks per wall second").isBetween(7.0, 11.0);
    }
}
