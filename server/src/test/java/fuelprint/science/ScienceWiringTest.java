package fuelprint.science;

import static org.assertj.core.api.Assertions.assertThat;

import fuelprint.api.CaptureRules;
import fuelprint.api.Detector;
import fuelprint.api.ObservationProvider;
import fuelprint.api.RunContext;
import fuelprint.api.WorldClock;
import fuelprint.api.WorldSetup;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.run.RunCommands;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.EventLog;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.core.world.WorldLoop;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/** The real science beans are wired in the normal (non-stub) profile and drive a run through the api interfaces only. */
@ActiveProfiles("real")
class ScienceWiringTest extends CoreSpringTest {

    @Autowired
    WorldClock clock;
    @Autowired
    ObservationProvider observations;
    @Autowired
    Detector detector;
    @Autowired
    CaptureRules captureRules;
    @Autowired
    WorldSetup setup;

    @Autowired CommandBus bus;
    @Autowired CookieSessionService sessions;
    @Autowired WorldLoop loop;
    @Autowired RecordStore store;
    @Autowired EventLog events;
    @Autowired fuelprint.api.Ranker ranker;

    @Test
    void fixedSensorUuidFromARealRunIsRecognisedByRanking() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CookieSessionService.COOKIE,
                sessions.createStation("laptop-2", Set.of("presenter")).sessionId()));
        var receipt = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(),
                RunCommands.RESET, null, null, JsonNodeFactory.instance.objectNode()
                .put("preset_id", "SC1-NIGHT-PLUME").put("mode", "TEACHING")),
                sessions.resolve(request).orElseThrow());
        bus.awaitIdle();
        String runId = receipt.recordRefs().stream().filter(r -> r.type().equals("run") && r.revision() == 1)
                .findFirst().orElseThrow().id();
        String sensorId = store.list("sensor", runId).stream()
                .filter(r -> "N2".equals(r.body().path("label").asText())).findFirst().orElseThrow().id();
        try {
            var background = new fuelprint.api.Window(UUID.randomUUID().toString(), sensorId,
                    "SO2_ppb", 0, 30, 30, null, 4.2, null);
            var window = new fuelprint.api.Window(UUID.randomUUID().toString(), sensorId,
                    "SO2_ppb", 30, 60, 30, 75.0, 6.0, background.id());
            var missingBackground = ranker.analyze(new fuelprint.api.AnalysisInput(runId, "investigation", 60,
                    java.util.List.of(window), new fuelprint.api.WindNow(270, 3)));
            assertThat(missingBackground.inputWindowIds()).isEmpty();
            assertThat(missingBackground.exclusions()).anyMatch(e -> window.id().equals(e.readingWindow())
                    && "BACKGROUND_WINDOW_UNKNOWN".equals(e.reason()));
            var analysis = ranker.analyze(new fuelprint.api.AnalysisInput(runId, "investigation", 60,
                    java.util.List.of(background, window), new fuelprint.api.WindNow(270, 3)));
            assertThat(analysis.inputWindowIds()).containsExactly(window.id());
            assertThat(analysis.exclusions()).noneMatch(e -> window.id().equals(e.readingWindow()));
            var otherRun = ranker.analyze(new fuelprint.api.AnalysisInput("other-run", "investigation", 60,
                    java.util.List.of(background, window), new fuelprint.api.WindNow(270, 3)));
            assertThat(otherRun.inputWindowIds()).isEmpty();
        } finally {
            loop.stop(runId);
        }
    }

    @Test
    void headlessSc1StoresBackgroundWindowsBeforeTheN2Trigger() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CookieSessionService.COOKIE,
                sessions.createStation("laptop-2", Set.of("presenter")).sessionId()));
        var receipt = bus.send(new Command(
                Command.SCHEMA_VERSION, UUID.randomUUID().toString(),
                RunCommands.RESET, null, null,
                JsonNodeFactory.instance.objectNode()
                        .put("preset_id", "SC1-NIGHT-PLUME").put("mode", "TEACHING")),
                sessions.resolve(request).orElseThrow());
        bus.awaitIdle();
        String run = receipt.recordRefs().stream().filter(r -> r.type().equals("run") && r.revision() == 1)
                .findFirst().orElseThrow().id();
        try {
            for (int i = 0; i < 20 * 300; i++) loop.wallStep();
            bus.awaitIdle();
            String n2 = store.list("sensor", run).stream()
                    .filter(r -> "N2".equals(r.body().path("label").asText())).findFirst().orElseThrow().id();
            var triggers = store.list("trigger", run).stream()
                    .filter(r -> n2.equals(r.body().path("sensor").asText())).toList();
            assertThat(triggers).isNotEmpty();
            var windows = store.list("window", run);
            assertThat(windows).extracting(StoredRecord::id).doesNotHaveDuplicates();
            for (var trigger : triggers) {
                String background = trigger.body().path("background_window_id").asText();
                assertThat(windows).anyMatch(w -> w.id().equals(background));
                var bgEvent = events.forRecord("window", background).get(0);
                var triggerEvent = events.forRecord("trigger", trigger.id()).get(0);
                assertThat(bgEvent.cursor()).isLessThan(triggerEvent.cursor());
                assertThat(bgEvent.simTimeS()).isLessThan(triggerEvent.simTimeS());
            }
            var analysis = ranker.analyze(new fuelprint.api.AnalysisInput(run, "integration-check", 300,
                    windows.stream().map(this::analysisWindow).toList(), clock.wind()));
            assertThat(analysis.inputWindowIds()).as("real saved detector windows reach the real ranker")
                    .isNotEmpty();
            assertThat(analysis.outcome()).isNotEqualTo(fuelprint.api.Outcome.INVALID);
            assertThat(analysis.exclusions()).noneMatch(e -> "INLET_HISTORY_UNKNOWN".equals(e.reason())
                    || "INCOMPLETE_WINDOW".equals(e.reason()));
        } finally {
            loop.stop(run);
        }
    }

    private fuelprint.api.Window analysisWindow(StoredRecord record) {
        var b = record.body();
        return new fuelprint.api.Window(record.id(), b.path("sensor_or_vehicle_id").asText(),
                b.path("channel").asText(), b.path("start_s").asDouble(), b.path("end_s").asDouble(),
                b.path("valid_seconds").asDouble(), b.hasNonNull("mean_enhancement")
                ? b.path("mean_enhancement").asDouble() : null,
                b.hasNonNull("std_uncertainty") ? b.path("std_uncertainty").asDouble() : null,
                b.hasNonNull("background_window_id") ? b.path("background_window_id").asText() : null);
    }

    @Test
    void theFiveInterfacesAreTheRealScienceBeans() {
        for (Object bean : new Object[] {clock, observations, detector, captureRules, setup}) {
            assertThat(bean.getClass().getName()).startsWith("fuelprint.science.");
        }
    }

    @Test
    void aRunCanBeStartedSteppedAndStopped() {
        setup.startRun(new RunContext("11111111-1111-4111-8111-111111111111", "SC1-NIGHT-PLUME", "TEACHING", 1));
        for (int i = 0; i < 40; i++) {
            clock.step(0.05);
        }
        assertThat(clock.wind().fromDeg()).isEqualTo(270.0);
        assertThat(observations.observe(2.0, java.util.List.of(
                new fuelprint.api.InletPose("n2", "N2", new fuelprint.api.Enu(120, 100, 3)))).readings()).hasSize(5);
        setup.stopRun("11111111-1111-4111-8111-111111111111");
    }
}
