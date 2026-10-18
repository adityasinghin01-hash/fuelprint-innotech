package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import fuelprint.api.AutomationSettings;
import fuelprint.api.Enu;
import fuelprint.api.InletHistory;
import fuelprint.api.InletSample;
import fuelprint.api.RunEnded;
import fuelprint.api.RunStarted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** Public inlet tracks use the same positions as the live vehicle, never guessed positions. */
class InletHistoryTest extends FieldSpringTest {

    @Autowired private ObjectProvider<InletHistory> histories;

    private InletHistory history() {
        InletHistory history = histories.getIfAvailable();
        assertThat(history).as("field supplies the real InletHistory bean").isNotNull();
        return history;
    }

    private List<InletSample> samples(double start, double end) {
        return history().track(runId, world.droneId(), start, end).orElseThrow();
    }

    @Test
    void flightSamplesFollowTheReportedInletAndHoverSamplesStayAtTheTarget() {
        InletHistory history = history();
        settings.set(AutomationSettings.AUTO_AUTOPILOT, true).waitS(1);
        String mission = approvedMission();
        tickUntil(() -> missionState(mission).equals("AT_TARGET"), 2400, "flight to target");

        List<InletSample> flight = history.track(runId, world.droneId(), 5.013, 15.017).orElseThrow();
        assertThat(flight).hasSizeGreaterThan(2);
        assertOrdered(flight);
        for (InletSample sample : flight) {
            int tickIndex = (int) Math.round(sample.simTimeS() / DT) - 1;
            assertThat(sample.inlet()).isEqualTo(track.get(tickIndex).inlet());
        }
        assertThat(flight.get(0).inlet()).isNotEqualTo(flight.get(flight.size() - 1).inlet());

        tickUntil(() -> speed() == 0, 400, "hover at target");
        double hoverStart = simTimeS;
        Enu hoverInlet = world.vehicles().get(0).inlet();
        ticks(600);
        List<InletSample> hover = samples(hoverStart + 0.013, simTimeS - 0.017);
        assertThat(hover).allSatisfy(sample -> assertThat(sample.inlet()).isEqualTo(hoverInlet));
        assertThat(hoverInlet.north()).isBetween(95.0, 105.0);
        assertOrdered(hover);
    }

    @Test
    void includesTheLastSampleBeforeTheStartAndFirstSampleAfterTheEnd() {
        world.onStep(10, DT);
        world.onStep(11, DT);
        world.onStep(12, DT);
        world.onStep(13, DT);
        assertThat(samples(10.25, 11.75)).extracting(InletSample::simTimeS)
                .containsExactly(10.0, 11.0, 12.0);
        assertThat(samples(11, 12)).extracting(InletSample::simTimeS).containsExactly(11.0, 12.0);
        assertThat(samples(11, 11)).extracting(InletSample::simTimeS).containsExactly(11.0);
        assertThat(samples(11.25, 11.25)).extracting(InletSample::simTimeS).containsExactly(11.0, 12.0);
    }

    @Test
    void unknownUntilRecordedAndForTheWrongRunOrVehicleOrIncompleteCoverage() {
        InletHistory history = history();
        assertThat(history.track(runId, world.droneId(), 0, 0)).isEmpty();
        world.onStep(10, DT);
        world.onStep(11, DT);
        assertThat(history.track("other-run", world.droneId(), 10, 11)).isEmpty();
        assertThat(history.track(runId, "other-vehicle", 10, 11)).isEmpty();
        assertThat(history.track(null, world.droneId(), 10, 11)).isEmpty();
        assertThat(history.track(runId, null, 10, 11)).isEmpty();
        assertThat(history.track(runId, world.droneId(), 9.99, 11)).isEmpty();
        assertThat(history.track(runId, world.droneId(), 10, 11.01)).isEmpty();
        assertThat(history.track(runId, world.droneId(), 9, 12)).isEmpty();
    }

    @Test
    void invalidIntervalsStayUnknown() {
        InletHistory history = history();
        world.onStep(10, DT);
        world.onStep(11, DT);
        assertThat(history.track(runId, world.droneId(), 11, 10)).isEmpty();
        assertThat(history.track(runId, world.droneId(), Double.NaN, 11)).isEmpty();
        assertThat(history.track(runId, world.droneId(), 10, Double.NaN)).isEmpty();
        assertThat(history.track(runId, world.droneId(), Double.NEGATIVE_INFINITY, 11)).isEmpty();
        assertThat(history.track(runId, world.droneId(), 10, Double.POSITIVE_INFINITY)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(RunEnded.Reason.class)
    void everyRunEndClearsHistoryEvenAfterMissionCleanupAndAnotherStep(RunEnded.Reason reason) {
        approvedMission();
        world.onStep(10, DT);
        world.onStep(11, DT);
        assertThat(samples(10, 11)).hasSize(2);
        events.publishEvent(new RunEnded(runId, reason));
        settle();
        assertThat(history().track(runId, world.droneId(), 10, 11)).isEmpty();
        world.onStep(12, DT);
        assertThat(history().track(runId, world.droneId(), 12, 12)).isEmpty();
    }

    @Test
    void aNewRunCannotReuseThePreviousRunsTrack() {
        world.onStep(10, DT);
        String nextRun = "next-run";
        events.publishEvent(new RunStarted(nextRun, "SC1-NIGHT-PLUME", "BLIND"));
        assertThat(history().track(runId, world.droneId(), 10, 10)).isEmpty();
        assertThat(history().track(nextRun, world.droneId(), 10, 10)).isEmpty();
        world.onStep(DT, DT);
        assertThat(history().track(nextRun, world.droneId(), DT, DT)).isPresent();
    }

    @Test
    void anUnrelatedRunEndDoesNotClearTheActiveTrack() {
        world.onStep(10, DT);
        events.publishEvent(new RunEnded("other-run", RunEnded.Reason.RESET));
        assertThat(samples(10, 10)).hasSize(1);
    }

    @Test
    void full900SecondRunRetainsEveryStepWithinThe18001SampleBound() {
        for (int i = 0; i <= 18_000; i++) {
            world.onStep(i * DT, DT);
        }
        List<InletSample> all = samples(0, 900);
        assertThat(all).hasSize(18_001);
        assertOrdered(all);
        // The cap also holds if extra steps arrive beyond the allowed run duration.
        world.onStep(900.05, DT);
        assertThat(history().track(runId, world.droneId(), 0, 900)).isEmpty();
        assertThat(samples(DT, 900.05)).hasSize(18_001);
    }

    @Test
    void returnedTracksAreImmutableSnapshots() {
        world.onStep(10, DT);
        List<InletSample> snapshot = samples(10, 10);
        assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
        world.onStep(11, DT);
        events.publishEvent(new RunEnded(runId, RunEnded.Reason.RESET));
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).simTimeS()).isEqualTo(10);
    }

    @Test
    void anotherThreadCanReadOrderedSnapshotsWhileTheWorldWrites() throws Exception {
        InletHistory history = history();
        world.onStep(0, DT);
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch start = new CountDownLatch(1);
        try {
            var writer = executor.submit(() -> {
                start.await();
                for (int i = 1; i <= 2000; i++) {
                    world.onStep(i * DT, DT);
                }
                return null;
            });
            start.countDown();
            for (int i = 0; i < 500; i++) {
                List<InletSample> snapshot = history.track(runId, world.droneId(), 0, 0).orElseThrow();
                assertThat(snapshot).hasSize(1);
                history.track(runId, world.droneId(), 0, 50).ifPresent(InletHistoryTest::assertOrdered);
            }
            writer.get(10, TimeUnit.SECONDS);
            assertOrdered(samples(0, 100));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertOrdered(List<InletSample> samples) {
        assertThat(samples).isNotEmpty();
        for (int i = 1; i < samples.size(); i++) {
            assertThat(samples.get(i).simTimeS()).isGreaterThan(samples.get(i - 1).simTimeS());
        }
    }
}
