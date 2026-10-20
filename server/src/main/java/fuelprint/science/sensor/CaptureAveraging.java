package fuelprint.science.sensor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import fuelprint.api.CaptureInput;
import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.api.Stage;
import fuelprint.api.Window;

/** A measured temporal contrast; no clean-air value is invented for the BACKGROUND stage. */
final class CaptureAveraging {
    private static final double EPS = 1e-6;

    private CaptureAveraging() { }

    static List<Window> windows(CaptureInput input) {
        String channel = input.channel();
        if (!TeachCaptureRules.METHOD.equals(input.methodId())
                || !(SensorField.SO2.equals(channel) || SensorField.CO2.equals(channel))) return List.of();
        Stage bgStage = last(input, "BACKGROUND"), collectStage = last(input, "COLLECT");
        if (!closed(bgStage) || !closed(collectStage) || bgStage.endS() > collectStage.startS() + EPS) return List.of();
        List<Reading> background = gases(input, bgStage);
        List<Reading> collection = gases(input, collectStage);
        if (background.isEmpty() || collection.isEmpty()) return List.of();
        Reading reference = background.get(0);
        // The uncertainty profile is declared for the synthetic instruments only.
        if (reference.origin() != Origin.SYNTHETIC_TEACHING || reference.sensorOrVehicleId() == null) return List.of();
        String flowChannel = SensorField.SO2.equals(channel) ? SensorField.SO2_FLOW : SensorField.CO2_FLOW;
        double target = SensorField.SO2.equals(channel) ? GasSettings.SO2.flowLMin() : GasSettings.CO2.flowLMin();
        if (Math.abs(background.get(0).simTimeS() - bgStage.startS()) > EPS
                || Math.abs(background.get(background.size() - 1).simTimeS() + 1 - bgStage.endS()) > EPS
                || !consecutive(background) || background.stream().anyMatch(r -> !valid(input, r, reference, flowChannel, target))) return List.of();
        List<Reading> run = new ArrayList<>();
        for (Reading gas : collection) {
            if (!run.isEmpty() && Math.abs(gas.simTimeS() - run.get(run.size() - 1).simTimeS() - 1) > EPS) run.clear();
            if (!valid(input, gas, reference, flowChannel, target)) {
                run.clear();
                continue;
            }
            run.add(gas);
            if (run.size() == TeachCaptureRules.MIN_VALID_SECONDS) break;
        }
        if (run.size() != TeachCaptureRules.MIN_VALID_SECONDS) return List.of();
        List<Double> bgTimes = background.stream().map(Reading::simTimeS).toList();
        List<Double> collectTimes = run.stream().map(Reading::simTimeS).toList();
        double bgVariance = SyntheticNoiseProfile.meanCovariance(channel, bgTimes, bgTimes);
        double collectVariance = SyntheticNoiseProfile.meanCovariance(channel, collectTimes, collectTimes);
        double covariance = SyntheticNoiseProfile.meanCovariance(channel, bgTimes, collectTimes);
        String bgId = UUID.randomUUID().toString();
        Window bg = new Window(bgId, reference.sensorOrVehicleId(), channel, background.get(0).simTimeS() - 1,
                background.get(background.size() - 1).simTimeS(), background.size(), null, Math.sqrt(bgVariance), null);
        Window collect = new Window(UUID.randomUUID().toString(), reference.sensorOrVehicleId(), channel,
                run.get(0).simTimeS() - 1, run.get(run.size() - 1).simTimeS(), run.size(),
                mean(run) - mean(background), Math.sqrt(Math.max(0, collectVariance + bgVariance - 2 * covariance)), bgId);
        if (!Double.isFinite(collect.meanEnhancement())) return List.of();
        return List.of(bg, collect);
    }

    private static boolean valid(CaptureInput input, Reading gas, Reading reference, String flowChannel, double target) {
        if (!good(gas) || !reference.sensorOrVehicleId().equals(gas.sensorOrVehicleId()) || reference.origin() != gas.origin()) return false;
        List<Reading> flows = input.readings().stream().filter(r -> flowChannel.equals(r.channel())
                && Math.abs(r.simTimeS() - gas.simTimeS()) < EPS).toList();
        if (flows.size() != 1) return false;
        Reading flow = flows.get(0);
        return good(flow) && reference.sensorOrVehicleId().equals(flow.sensorOrVehicleId())
                && reference.origin() == flow.origin() && Math.abs(flow.value() - target) <= .05 * target + 1e-12;
    }

    private static boolean good(Reading reading) {
        return Double.isFinite(reading.simTimeS()) && reading.value() != null && Double.isFinite(reading.value())
                && reading.quality().equals(List.of("OK"));
    }

    private static boolean consecutive(List<Reading> readings) {
        for (int i = 1; i < readings.size(); i++) {
            if (Math.abs(readings.get(i).simTimeS() - readings.get(i - 1).simTimeS() - 1) > EPS) return false;
        }
        return true;
    }

    private static List<Reading> gases(CaptureInput input, Stage stage) {
        return input.readings().stream().filter(r -> input.channel().equals(r.channel())
                && r.simTimeS() >= stage.startS() - EPS && r.simTimeS() < stage.endS() - EPS)
                .sorted(Comparator.comparingDouble(Reading::simTimeS)).toList();
    }

    private static boolean closed(Stage stage) {
        return stage != null && Double.isFinite(stage.startS()) && stage.endS() != null
                && Double.isFinite(stage.endS()) && stage.endS() > stage.startS();
    }

    private static Stage last(CaptureInput input, String name) {
        return input.stages().stream().filter(s -> name.equals(s.stage())).reduce((a, b) -> b).orElse(null);
    }

    private static double mean(List<Reading> readings) {
        return readings.stream().mapToDouble(Reading::value).average().orElseThrow();
    }
}
