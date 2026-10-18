package fuelprint.science.detect;

import fuelprint.api.*;
import fuelprint.science.rank.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Catches detector/ranker boundary failures without substituting hand-made analysis windows. */
class DetectorRankingWindowsTest {
    @Test
    void savedWindowIdsAreServerGeneratedVersionFourUuids() {
        TeachDetector detector = new TeachDetector();
        List<Window> windows = new ArrayList<>();
        for (int t = 1; t <= 60; t++) windows.addAll(detector.windows(input(t, t <= 30 ? 5.0 : 80.0)));
        assertThat(windows).isNotEmpty().allSatisfy(w ->
                assertThat(java.util.UUID.fromString(w.id()).version()).isEqualTo(4));
    }

    @Test
    void alarmRemainsFifteenSecondsWhileAnalysisReceivesCompleteNonoverlappingMeans() {
        TeachDetector detector = new TeachDetector();
        List<Window> windows = new ArrayList<>();
        List<Double> alarmTimes = new ArrayList<>();
        for (int t = 1; t <= 120; t++) {
            DetectorInput input = input(t, t <= 30 ? 5.0 : 80.0);
            windows.addAll(detector.windows(input));
            double now = t;
            detector.evaluate(input).ifPresent(trigger -> alarmTimes.add(now));
        }
        assertThat(alarmTimes).containsExactly(45.0);
        List<Window> enhancements = windows.stream().filter(w -> w.meanEnhancement() != null).toList();
        assertThat(enhancements).hasSize(3);
        assertThat(enhancements).allSatisfy(w -> {
            assertThat(w.endS() - w.startS()).isEqualTo(30);
            assertThat(w.validSeconds()).isEqualTo(30);
            assertThat(w.meanEnhancement()).isEqualTo(75);
            assertThat(w.stdUncertainty()).isPositive();
        });
        assertThat(enhancements).extracting(Window::startS).containsExactly(30.0, 60.0, 90.0);
        assertThat(enhancements).extracting(Window::endS).containsExactly(60.0, 90.0, 120.0);
        var selected = WindowSelection.select("run", windows,
                InletTrack.fixedNodes(WorldProfile.load()), WorldProfile.load());
        assertThat(selected.entries()).hasSize(3);
        assertThat(selected.exclusions()).noneMatch(e -> e.reason().equals("INCOMPLETE_WINDOW"));
    }

    @Test
    void invalidAndMissingSecondsCannotBeStitchedIntoAnAnalysisWindow() {
        TeachDetector detector = new TeachDetector();
        List<Window> enhancements = new ArrayList<>();
        for (int t = 1; t <= 90; t++) {
            if (t == 45) continue;
            var input = input(t, t <= 30 ? 5.0 : 80.0);
            enhancements.addAll(detector.windows(input).stream().filter(w -> w.meanEnhancement() != null).toList());
            detector.evaluate(input);
        }
        assertThat(enhancements).hasSize(1);
        assertThat(enhancements.get(0).startS()).isEqualTo(45);
        assertThat(enhancements.get(0).endS()).isEqualTo(75);
    }

    private static DetectorInput input(int t, double value) {
        return new DetectorInput("run", t, List.of(new Reading("N2", "SO2_ppb", value, value,
                "ppb", t, List.of("OK"), Origin.SYNTHETIC_TEACHING)), List.of(), new WindNow(270, 3));
    }
}
