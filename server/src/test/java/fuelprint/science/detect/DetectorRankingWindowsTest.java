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

    @Test
    void adjacentFixedMeansReportTheirActualElectricalContrastVariance() {
        TeachDetector detector=new TeachDetector();
        List<Window> windows=new ArrayList<>();
        for(int t=1;t<=60;t++) windows.addAll(detector.windows(input(t,t<=30?5:80)));
        Window collection=windows.stream().filter(w->w.meanEnhancement()!=null).findFirst().orElseThrow();
        Window bg=windows.stream().filter(w->w.id().equals(collection.backgroundWindowId())).findFirst().orElseThrow();
        double expected=0;
        for(int shock=0;shock<=60;shock++) {
            double electrical=shock==0?5/Math.sqrt(1-.8*.8):0;
            double contrast=0;
            for(int second=1;second<=60;second++) {
                electrical=.8*electrical+(shock==second?5:0);
                contrast+=(second<=30?-1.0/30:1.0/30)*electrical;
            }
            expected+=contrast*contrast;
        }
        assertThat(collection.stdUncertainty()*collection.stdUncertainty())
                .isCloseTo(expected,org.assertj.core.data.Offset.offset(1e-10));
        Window otherBg=new Window("other-bg","other",bg.channel(),bg.startS(),bg.endS(),30,null,bg.stdUncertainty(),null);
        Window other=new Window("other-col","other",collection.channel(),collection.startS(),collection.endS(),30,
                collection.meanEnhancement(),collection.stdUncertainty(),otherBg.id());
        var track=InletTrack.fixed(new Enu(120,100,3));
        double[][] c=WindowCovariance.of(List.of(new WindowSelection.Entry(collection,Gas.SO2,track,bg),
                new WindowSelection.Entry(other,Gas.SO2,track,otherBg)));
        // Common ambient noise cancels from diagonal-minus-cross; different instruments' electrical noise is independent.
        assertThat(c[0][0]-c[0][1]).isCloseTo(expected,org.assertj.core.data.Offset.offset(1e-10));
    }

    private static DetectorInput input(int t, double value) {
        return new DetectorInput("run", t, List.of(new Reading("N2", "SO2_ppb", value, value,
                "ppb", t, List.of("OK"), Origin.SYNTHETIC_TEACHING)), List.of(), new WindNow(270, 3));
    }
}
