package fuelprint.science;

import static org.assertj.core.api.Assertions.assertThat;

import fuelprint.api.CaptureRules;
import fuelprint.api.Detector;
import fuelprint.api.ObservationProvider;
import fuelprint.api.RunContext;
import fuelprint.api.WorldClock;
import fuelprint.api.WorldSetup;
import fuelprint.core.CoreSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The real science beans are wired in the normal (non-stub) profile and drive a run through the api interfaces only. */
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
