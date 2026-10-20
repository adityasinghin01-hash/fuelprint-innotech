package fuelprint.science.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import fuelprint.api.*;
import org.junit.jupiter.api.Test;

class CaptureWindowsTest {
    private final CaptureRules rules = new TeachCaptureRules();

    @SuppressWarnings("unchecked")
    private List<Window> windows(CaptureInput input) {
        try {
            return (List<Window>) CaptureRules.class.getMethod("windows", CaptureInput.class).invoke(rules, input);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Capture rules must provide measured capture windows", e);
        }
    }

    private Reading reading(String instrument, String channel, double time, Double value, List<String> quality, Origin origin) {
        return new Reading(instrument, channel, value, value, channel.contains("flow") ? "L_min" : "ppb", time, quality, origin);
    }

    private List<Reading> readings() {
        List<Reading> readings = new ArrayList<>();
        for (int t = 0; t < 210; t++) {
            if (t >= 30 && t < 180) continue;
            readings.add(reading("vehicle", "SO2_ppb", t, t < 30 ? 10.0 : 45.0, List.of("OK"), Origin.SYNTHETIC_TEACHING));
            readings.add(reading("vehicle", "SO2_flow_L_min", t, .5, List.of("OK"), Origin.SYNTHETIC_TEACHING));
        }
        return readings;
    }

    private CaptureInput input(List<Reading> readings) {
        return new CaptureInput("attempt", TeachCaptureRules.METHOD, "SO2_ppb", List.of(
                new Stage("BACKGROUND", 0, 30.0), new Stage("SETTLE", 60, 180.0),
                new Stage("COLLECT", 180, 210.0)), readings);
    }

    @Test void measuredContrastHasSavedBackgroundAndThirtyRightEndpointSamples() {
        var result = windows(input(readings()));
        assertThat(result).hasSize(2);
        Window bg = result.get(0), collect = result.get(1);
        assertThat(UUID.fromString(bg.id()).version()).isEqualTo(4);
        assertThat(UUID.fromString(collect.id()).version()).isEqualTo(4);
        assertThat(bg.meanEnhancement()).isNull();
        assertThat(bg.backgroundWindowId()).isNull();
        assertThat(bg.startS()).isEqualTo(-1);
        assertThat(bg.endS()).isEqualTo(29);
        assertThat(collect.startS()).isEqualTo(179);
        assertThat(collect.endS()).isEqualTo(209);
        assertThat(collect.validSeconds()).isEqualTo(30);
        assertThat(collect.meanEnhancement()).isEqualTo(35);
        assertThat(collect.backgroundWindowId()).isEqualTo(bg.id());
        // Stationary AR1 variance of a 30-sample mean: 69.4444*(30+2*sum((30-k)*.8^k))/900.
        assertThat(bg.stdUncertainty()).isCloseTo(4.213162040894856, within(1e-12));
        assertThat(collect.stdUncertainty()).isCloseTo(5.958310898709014, within(1e-12));
    }

    @Test void missingBackgroundFlowGapsInvalidValuesAndMixedInstrumentsCannotProduceEvidence() {
        List<Reading> normal = readings();
        for (int variant = 0; variant < 8; variant++) {
            List<Reading> bad = new ArrayList<>(normal);
            if (variant == 0) bad.removeIf(r -> r.simTimeS() < 30);
            if (variant == 1) bad.removeIf(r -> r.channel().contains("flow") && r.simTimeS() == 190);
            if (variant == 2) bad.removeIf(r -> r.simTimeS() == 190);
            int index = 80; // SO2 at collection second 190.
            Reading original = normal.get(index);
            if (variant == 3) bad.set(index, reading("vehicle", "SO2_ppb", 190, Double.NaN, List.of("OK"), original.origin()));
            if (variant == 4) bad.set(index, reading("other", "SO2_ppb", 190, 45.0, List.of("OK"), original.origin()));
            if (variant == 5) bad.set(index, reading("vehicle", "SO2_ppb", 190, 45.0, List.of("OK", "STALE"), original.origin()));
            if (variant == 6) bad.set(index, reading("vehicle", "SO2_ppb", 190, 45.0, List.of("OK"), Origin.PHYSICAL_BENCH));
            if (variant == 7) bad.set(index + 1, reading("vehicle", "SO2_flow_L_min", 190.5, .5, List.of("OK"), original.origin()));
            assertThat(windows(input(bad))).as("invalid variant %s", variant).isEmpty();
        }
    }

    @Test void collectionSearchDoesNotStitchButCanUseACompleteLaterRun() {
        List<Reading> extended = new ArrayList<>(readings());
        extended.removeIf(r -> r.simTimeS() == 190);
        for (int t = 210; t < 221; t++) {
            extended.add(reading("vehicle", "SO2_ppb", t, 45.0, List.of("OK"), Origin.SYNTHETIC_TEACHING));
            extended.add(reading("vehicle", "SO2_flow_L_min", t, .5, List.of("OK"), Origin.SYNTHETIC_TEACHING));
        }
        var original = input(extended);
        var longer = new CaptureInput(original.attemptId(), original.methodId(), original.channel(), List.of(
                new Stage("BACKGROUND", 0, 30.0), new Stage("COLLECT", 180, 221.0)), extended);
        var result = windows(longer);
        assertThat(result).hasSize(2);
        assertThat(result.get(1).startS()).isEqualTo(190);
        assertThat(result.get(1).endS()).isEqualTo(220);
    }

    @Test void unsupportedChannelCannotReuseSo2FlowOrNoise() {
        CaptureInput valid = input(readings());
        assertThat(windows(new CaptureInput(valid.attemptId(), valid.methodId(), "PM_ugm3", valid.stages(), valid.readings()))).isEmpty();
    }
    @Test void adjacentMeansRetainTheirSharedAr1Covariance() {
        List<Reading> raw = new ArrayList<>();
        for (int t = 0; t < 32; t++) {
            raw.add(reading("vehicle", "SO2_ppb", t, t < 2 ? 10.0 : 45.0, List.of("OK"), Origin.SYNTHETIC_TEACHING));
            raw.add(reading("vehicle", "SO2_flow_L_min", t, .5, List.of("OK"), Origin.SYNTHETIC_TEACHING));
        }
        CaptureInput adjacent = new CaptureInput("attempt", TeachCaptureRules.METHOD, "SO2_ppb", List.of(
                new Stage("BACKGROUND", 0, 2.0), new Stage("COLLECT", 2, 32.0)), raw);
        var result = windows(adjacent);
        assertThat(result).hasSize(2);
        // BG has variance 62.5; variance(COLLECT)=17.750734387...; 2*covariance=16.646034332...
        assertThat(result.get(1).stdUncertainty()).isCloseTo(7.975255484945842, within(1e-12));
    }

}
