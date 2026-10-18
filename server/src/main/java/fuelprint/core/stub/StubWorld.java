package fuelprint.core.stub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import fuelprint.api.Detector;
import fuelprint.api.DetectorInput;
import fuelprint.api.InletPose;
import fuelprint.api.Interval;
import fuelprint.api.ObservationProvider;
import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.api.ReadingBatch;
import fuelprint.api.RunContext;
import fuelprint.api.Trigger;
import fuelprint.api.WindNow;
import fuelprint.api.Window;
import fuelprint.api.WorldClock;
import fuelprint.api.WorldSetup;
import fuelprint.core.Ids;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stub science world: wind from 270 at 3 m/s; SO2 5 ppb, CO2 420 ppm, particles 18 ug/m3 everywhere. In SC1 and
 * SC2 box N2's SO2 rises to about 61 ppb from 60 s; in SC3 the boxes see nothing but a drone inlet above 20 m sees
 * a rise. Every 30 s one SO2 window per box; one TEACH-DETECT-1 trigger when N2 has been 40 ppb above background
 * for 15 seconds. Fixture-shaped, deterministic, not physics.
 */
@Component
@Profile("stub")
public class StubWorld implements WorldClock, ObservationProvider, Detector, WorldSetup {

    static final double SO2_BACKGROUND = 5.0;
    static final double SO2_PEAK = 61.2;
    static final double RISE_START_S = 60;

    private volatile RunContext run;
    private volatile double simTimeS;
    private volatile boolean triggered;
    private volatile double lastWindowEnd;
    private final Map<String, String> lastWindowBySensor = new ConcurrentHashMap<>();

    @Override
    public void startRun(RunContext context) {
        run = context;
        triggered = false;
        lastWindowEnd = 0;
        lastWindowBySensor.clear();
    }

    @Override
    public void stopRun(String runId) {
        if (run != null && run.runId().equals(runId)) {
            run = null;
        }
    }

    @Override
    public void step(double dtS) {
        simTimeS += dtS;
    }

    @Override
    public WindNow wind() {
        return new WindNow(270, 3.0);
    }

    private boolean aboveTheBoxes() {
        return run != null && run.presetId().startsWith("SC3");
    }

    double so2At(String label, double up, double east, double t) {
        if (aboveTheBoxes()) {
            return up > 20 && east > -50 ? SO2_BACKGROUND + 43 : SO2_BACKGROUND;
        }
        if ("N2".equals(label) && t >= RISE_START_S) {
            double f = Math.min(1, (t - RISE_START_S) / 20);
            return SO2_BACKGROUND + (SO2_PEAK - SO2_BACKGROUND) * f;
        }
        return SO2_BACKGROUND;
    }

    @Override
    public ReadingBatch observe(double simTimeS, List<InletPose> inlets) {
        List<Reading> readings = new ArrayList<>();
        for (InletPose inlet : inlets) {
            double so2 = Math.round(so2At(inlet.label(), inlet.inlet().up(), inlet.inlet().east(), simTimeS) * 10) / 10.0;
            readings.add(reading(inlet, "SO2_ppb", so2, "ppb", simTimeS));
            readings.add(reading(inlet, "CO2_ppm", 420.0, "ppm", simTimeS));
            if (inlet.label().startsWith("N")) {
                readings.add(reading(inlet, "PM_ugm3", 18.0, "ug_m3", simTimeS));
            }
        }
        return new ReadingBatch(simTimeS, readings);
    }

    private static Reading reading(InletPose inlet, String channel, double value, String unit, double t) {
        return new Reading(inlet.sensorOrVehicleId(), channel, value, value, unit, t, List.of("OK"),
                Origin.SYNTHETIC_TEACHING);
    }

    @Override
    public List<Window> windows(DetectorInput input) {
        if (input.simTimeS() - lastWindowEnd < 30) {
            return List.of();
        }
        double end = Math.floor(input.simTimeS());
        double start = end - 30;
        lastWindowEnd = end;
        Map<String, double[]> sums = new java.util.LinkedHashMap<>();
        for (Reading reading : input.readings()) {
            if ("SO2_ppb".equals(reading.channel()) && reading.simTimeS() > start && reading.value() != null) {
                double[] acc = sums.computeIfAbsent(reading.sensorOrVehicleId(), k -> new double[2]);
                acc[0] += reading.value();
                acc[1] += 1;
            }
        }
        List<Window> windows = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            double mean = entry.getValue()[0] / entry.getValue()[1];
            String id = Ids.newId();
            windows.add(new Window(id, entry.getKey(), "SO2_ppb", start, end, entry.getValue()[1],
                    Math.round((mean - SO2_BACKGROUND) * 10) / 10.0, 1.0, lastWindowBySensor.get(entry.getKey())));
            lastWindowBySensor.put(entry.getKey(), id);
        }
        return windows;
    }

    @Override
    public Optional<Trigger> evaluate(DetectorInput input) {
        if (triggered) {
            return Optional.empty();
        }
        Map<String, Integer> highSeconds = new java.util.HashMap<>();
        Map<String, Double> firstHigh = new java.util.HashMap<>();
        for (Reading reading : input.readings()) {
            if (!"SO2_ppb".equals(reading.channel()) || reading.value() == null) {
                continue;
            }
            if (reading.value() - SO2_BACKGROUND >= 40) {
                highSeconds.merge(reading.sensorOrVehicleId(), 1, Integer::sum);
                firstHigh.putIfAbsent(reading.sensorOrVehicleId(), reading.simTimeS());
            } else {
                highSeconds.remove(reading.sensorOrVehicleId());
                firstHigh.remove(reading.sensorOrVehicleId());
            }
        }
        for (Map.Entry<String, Integer> entry : highSeconds.entrySet()) {
            if (entry.getValue() >= 15) {
                triggered = true;
                return Optional.of(new Trigger("SO2_ppb", entry.getKey(),
                        List.of(new Interval(firstHigh.get(entry.getKey()), input.simTimeS())),
                        lastWindowBySensor.get(entry.getKey()), Trigger.TEACH_DETECT_1));
            }
        }
        return Optional.empty();
    }
}
