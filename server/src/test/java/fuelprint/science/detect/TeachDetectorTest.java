package fuelprint.science.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import fuelprint.api.DetectorInput;
import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.api.Trigger;
import fuelprint.api.Window;
import fuelprint.api.WindNow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeachDetectorTest {

    private static final String RUN = UUID.randomUUID().toString();
    private static final String N2 = UUID.randomUUID().toString();

    private TeachDetector detector;
    private int t;
    private final List<Trigger> triggers = new ArrayList<>();
    private final List<Window> windows = new ArrayList<>();
    private DetectionResult last;

    @BeforeEach
    void setUp() {
        detector = new TeachDetector();
        t = 0;
    }

    private static Reading so2(String sensor, int second, Double value, String... quality) {
        return new Reading(sensor, "SO2_ppb", value, value, "ppb", second,
                List.of(quality.length == 0 ? new String[] {"OK"} : quality), Origin.SYNTHETIC_TEACHING);
    }

    private static Reading co2(String sensor, int second, double value) {
        return new Reading(sensor, "CO2_ppm", value, value, "ppm", second, List.of("OK"), Origin.SYNTHETIC_TEACHING);
    }

    private DetectionResult feed(Reading... readings) {
        last = detector.evaluateDetailed(new DetectorInput(RUN, t, List.of(readings), List.of(), new WindNow(270, 3)));
        triggers.addAll(last.triggers());
        windows.addAll(last.windows());
        return last;
    }

    /** One value per second, with a tiny deterministic wobble so the background has a small SD. */
    private DetectionResult seconds(int n, double level) {
        DetectionResult r = null;
        for (int i = 0; i < n; i++) {
            r = feed(so2(N2, t, level + 0.5 * Math.sin(t)));
            t++;
        }
        return r;
    }

    @Test
    void coreReceivesEachCompletedWindowOnceBeforeItsTrigger() {
        List<String> delivered = new ArrayList<>();
        List<Trigger> seen = new ArrayList<>();
        for (int second = 0; second < 90; second++) {
            DetectorInput input = new DetectorInput(RUN, second,
                    List.of(so2(N2, second, second < 40 ? 5.0 : 80.0)), List.of(), new WindNow(270, 3));
            for (Window window : detector.windows(input)) {
                assertThat(window.endS()).isLessThanOrEqualTo(second);
                assertThat(delivered).doesNotContain(window.id());
                delivered.add(window.id());
            }
            assertThat(detector.windows(input)).isEmpty();
            detector.evaluate(input).ifPresent(trigger -> {
                assertThat(delivered).contains(trigger.backgroundWindowId());
                seen.add(trigger);
            });
            assertThat(detector.evaluate(input)).isEmpty();
        }
        assertThat(seen).hasSize(1);
        assertThat(delivered).hasSize(3);
    }

    @Test
    void withoutABackgroundTheAnswerIsBackgroundRequired() {
        DetectionResult r = seconds(20, 80.0);
        assertThat(r.status()).isEqualTo(DetectionStatus.BACKGROUND_REQUIRED);
        assertThat(triggers).isEmpty();
        assertThat(detector.evaluate(new DetectorInput(RUN, t, List.of(), List.of(), new WindNow(270, 3)))).isEmpty();
    }

    @Test
    void aTenSecondSpikeDoesNotTrigger() {
        seconds(40, 5.0);
        assertThat(last.status()).isEqualTo(DetectionStatus.MONITORING);
        seconds(10, 80.0);
        assertThat(last.status()).isEqualTo(DetectionStatus.ENHANCING);
        seconds(40, 5.0);
        assertThat(triggers).isEmpty();
        assertThat(last.status()).isEqualTo(DetectionStatus.MONITORING);
    }

    @Test
    void fifteenValidEnhancedSecondsTriggerWithTheWindowsToSave() {
        seconds(40, 5.0);
        seconds(14, 80.0);
        assertThat(triggers).isEmpty();
        DetectionResult r = seconds(1, 80.0);
        assertThat(r.status()).isEqualTo(DetectionStatus.TRIGGERED);
        assertThat(triggers).hasSize(1);
        Trigger trig = triggers.get(0);
        assertThat(trig.rule()).isEqualTo("TEACH-DETECT-1");
        assertThat(trig.channel()).isEqualTo("SO2_ppb");
        assertThat(trig.sensor()).isEqualTo(N2);
        assertThat(trig.intervals()).hasSize(1);
        assertThat(trig.intervals().get(0).startS()).isEqualTo(40.0);
        assertThat(trig.intervals().get(0).endS()).isEqualTo(54.0);
        assertThat(trig.backgroundWindowId()).isNotNull();
        // Alarm timing is independent of the complete windows used for ranking.
        assertThat(windows).extracting(Window::id).contains(trig.backgroundWindowId());
        Window bg = windows.stream().filter(w -> w.id().equals(trig.backgroundWindowId())).findFirst().orElseThrow();
        assertThat(bg.validSeconds()).isEqualTo(30.0);
        assertThat(bg.endS() - bg.startS()).isEqualTo(30.0);
        assertThat(windows).noneMatch(w -> w.meanEnhancement() != null);
        seconds(5, 80.0);
        Window enh = windows.stream().filter(w -> trig.backgroundWindowId().equals(w.backgroundWindowId()))
                .findFirst().orElseThrow();
        assertThat(enh.validSeconds()).isEqualTo(30.0);
        assertThat(enh.meanEnhancement()).isBetween(45.0, 55.0);
        assertThat(enh.stdUncertainty()).isNotNull();
        // it fires once per episode, not every second
        seconds(30, 80.0);
        assertThat(triggers).hasSize(1);
    }

    @Test
    void aMissingSecondResetsTheCount() {
        seconds(40, 5.0);
        seconds(10, 80.0);
        t++; // one second never arrives
        seconds(10, 80.0);
        assertThat(triggers).isEmpty();
        seconds(5, 80.0); // 15 in a row after the gap
        assertThat(triggers).hasSize(1);
    }

    @Test
    void invalidSecondsResetTheCount() {
        for (String bad : new String[] {"FLOW_FAULT", "CALIBRATION_FAILED", "STALE"}) {
            detector.reset();
            triggers.clear();
            t = 0;
            seconds(40, 5.0);
            seconds(10, 80.0);
            feed(so2(N2, t++, 80.0, bad));
            seconds(10, 80.0);
            assertThat(triggers).as(bad).isEmpty();
            seconds(5, 80.0);
            assertThat(triggers).as(bad).hasSize(1);
        }
        // an unknown (null) value also resets
        detector.reset();
        triggers.clear();
        t = 0;
        seconds(40, 5.0);
        seconds(10, 80.0);
        feed(so2(N2, t++, null));
        seconds(10, 80.0);
        assertThat(triggers).isEmpty();
    }

    @Test
    void secondsBelowTheFloorAreNotEnhancements() {
        seconds(40, 5.0);
        seconds(60, 25.0); // 20 above background, floor is 25
        assertThat(triggers).isEmpty();
        // the quiet-looking +20 ppb is not absorbed into the background either
        assertThat(detector.backgroundMean(N2).orElseThrow()).isBetween(4.0, 6.0);
    }

    @Test
    void theThresholdGrowsWithStandardUncertainty() {
        // a noisy background (SD about 20 ppb) makes 3 x uncertainty about 61 ppb, above the 25 ppb floor
        for (int i = 0; i < 40; i++) {
            feed(so2(N2, t, 5.0 + (i % 2 == 0 ? 20.0 : -20.0)));
            t++;
        }
        seconds(30, 50.0);
        assertThat(triggers).isEmpty();
        seconds(20, 120.0);
        assertThat(triggers).hasSize(1);
    }

    @Test
    void co2AloneNeverTriggers() {
        for (int i = 0; i < 120; i++) {
            double c = i < 40 ? 420.0 : 900.0;
            feed(so2(N2, t, 5.0 + 0.5 * Math.sin(t)), co2(N2, t, c));
            t++;
        }
        assertThat(triggers).isEmpty();
        // only CO2 readings at all: no background to judge, still no trigger
        detector.reset();
        t = 0;
        for (int i = 0; i < 100; i++) {
            feed(co2(N2, t, 420 + 400.0 * (i > 40 ? 1 : 0)));
            t++;
        }
        assertThat(triggers).isEmpty();
        assertThat(last.status()).isEqualTo(DetectionStatus.BACKGROUND_REQUIRED);
    }

    @Test
    void aBackgroundOlderThan180SecondsIsNotUsed() {
        seconds(40, 5.0);
        seconds(185, 25.0); // 20 ppb up: below the floor, never accepted as the new background
        assertThat(last.status()).isEqualTo(DetectionStatus.BACKGROUND_REQUIRED);
        seconds(15, 120.0); // a real enhancement but nothing to compare with; a ramp is not a background either
        assertThat(triggers).isEmpty();
        assertThat(last.status()).isEqualTo(DetectionStatus.BACKGROUND_REQUIRED);
    }

    @Test
    void resendingTheSameReadingsDoesNotCountThemTwice() {
        List<Reading> all = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            all.add(so2(N2, i, 5.0 + 0.5 * Math.sin(i)));
        }
        for (int i = 40; i < 50; i++) {
            all.add(so2(N2, i, 80.0));
        }
        for (int k = 0; k < 5; k++) {
            last = detector.evaluateDetailed(new DetectorInput(RUN, 49, all, List.of(), new WindNow(270, 3)));
            triggers.addAll(last.triggers());
        }
        assertThat(triggers).isEmpty();
        assertThat(last.status()).isEqualTo(DetectionStatus.ENHANCING);
    }

    @Test
    void eachBoxIsJudgedOnItsOwn() {
        String n3 = UUID.randomUUID().toString();
        for (int i = 0; i < 40; i++) {
            feed(so2(N2, t, 5.0), so2(n3, t, 5.0));
            t++;
        }
        for (int i = 0; i < 15; i++) {
            feed(so2(N2, t, 5.0), so2(n3, t, 90.0));
            t++;
        }
        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).sensor()).isEqualTo(n3);
    }

    @Test
    void theApiMethodReturnsTheTriggerOnce() {
        List<Reading> all = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            all.add(so2(N2, i, 5.0 + 0.5 * Math.sin(i)));
        }
        for (int i = 40; i < 55; i++) {
            all.add(so2(N2, i, 80.0));
        }
        Optional<Trigger> first = detector.evaluate(new DetectorInput(RUN, 54, all, List.of(), new WindNow(270, 3)));
        assertThat(first).isPresent();
        assertThat(detector.evaluate(new DetectorInput(RUN, 54, all, List.of(), new WindNow(270, 3)))).isEmpty();
    }

    @Test
    void aNewRunStartsFromNothing() {
        seconds(40, 5.0);
        seconds(14, 80.0);
        Optional<Trigger> other = detector.evaluate(new DetectorInput(UUID.randomUUID().toString(), 54,
                List.of(so2(N2, 54, 80.0)), List.of(), new WindNow(270, 3)));
        assertThat(other).isEmpty();
    }
}
