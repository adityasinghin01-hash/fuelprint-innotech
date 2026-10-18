package fuelprint.science.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleFunction;

import fuelprint.api.CaptureInput;
import fuelprint.api.ChannelVerdict;
import fuelprint.api.DetectorInput;
import fuelprint.api.Enu;
import fuelprint.api.InletPose;
import fuelprint.api.Reading;
import fuelprint.api.ReadingBatch;
import fuelprint.api.RunContext;
import fuelprint.api.Stage;
import fuelprint.api.Trigger;
import fuelprint.api.Window;
import fuelprint.science.detect.DetectionResult;
import fuelprint.science.detect.TeachDetector;
import fuelprint.science.scenario.ScenarioWorldSetup;
import fuelprint.science.sensor.SensorField;
import fuelprint.science.sensor.TeachCaptureRules;
import fuelprint.science.world.ScienceWorld;
import fuelprint.science.world.WorldProfile;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A small headless runner: the same calls the core world loop makes (step, observe, once a second the detector),
 * with no web server and no database. A scripted drone flies a path, then a capture runs with the stages of
 * TEACH-NODE-ONLINE-2.
 */
public final class HeadlessRun {

    public static final double DT = 0.05;

    public final ScienceWorld world;
    public final SensorField sensors;
    public final TeachDetector detector;
    public final ScenarioWorldSetup setup;
    public final TeachCaptureRules captureRules = new TeachCaptureRules();
    public final WorldProfile profile = WorldProfile.load();
    public final String runId = UUID.randomUUID().toString();
    public final String droneId = UUID.randomUUID().toString();
    public final List<InletPose> boxes = new ArrayList<>();
    public final List<Reading> allReadings = new ArrayList<>();
    public final List<Trigger> triggers = new ArrayList<>();
    public final List<Window> windows = new ArrayList<>();
    public final List<String> events = new ArrayList<>();

    private long tick;
    private DoubleFunction<Enu> dronePath = t -> new Enu(0, 0, 2);
    private final List<Reading> secondBuffer = new ArrayList<>();

    public HeadlessRun(String preset) {
        ApplicationEventPublisher pub = e -> events.add(e.toString());
        world = new ScienceWorld(pub);
        sensors = new SensorField(world);
        detector = new TeachDetector();
        setup = new ScenarioWorldSetup(world, sensors, detector);
        setup.startRun(new RunContext(runId, preset, "TEACHING", 0));
        for (WorldProfile.Node n : profile.nodes()) {
            boxes.add(new InletPose(UUID.nameUUIDFromBytes(("box-" + n.id()).getBytes()).toString(), n.id(),
                    new Enu(n.east(), n.north(), n.inletHeight())));
        }
    }

    public String boxId(String label) {
        return boxes.stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow().sensorOrVehicleId();
    }

    public void dronePath(DoubleFunction<Enu> path) {
        this.dronePath = path;
    }

    public double timeS() {
        return tick * DT;
    }

    /** Runs {@code seconds} more simulated seconds. Returns nothing; read the lists afterwards. */
    public void run(double seconds) {
        long end = tick + Math.round(seconds / DT);
        while (tick < end) {
            world.step(DT);
            tick++;
            double t = timeS();
            List<InletPose> poses = new ArrayList<>(boxes);
            poses.add(new InletPose(droneId, "DRONE-1", dronePath.apply(t)));
            ReadingBatch batch = sensors.observe(t, poses);
            allReadings.addAll(batch.readings());
            if (tick % 20 == 0) {
                List<Reading> fixed = new ArrayList<>();
                for (Reading r : batch.readings()) {
                    if (!r.sensorOrVehicleId().equals(droneId)) {
                        fixed.add(r);
                    }
                }
                DetectionResult res = detector.evaluateDetailed(
                        new DetectorInput(runId, t, fixed, List.of(), world.wind()));
                triggers.addAll(res.triggers());
                windows.addAll(res.windows());
            }
        }
    }

    /** Triggers that came from the box with this label. */
    public List<Trigger> triggersAt(String label) {
        String id = boxId(label);
        return triggers.stream().filter(x -> x.sensor().equals(id)).toList();
    }

    /** Readings of one inlet and channel inside [from, to], oldest first. */
    public List<Reading> readings(String inletId, String channel, double from, double to) {
        return allReadings.stream()
                .filter(r -> r.sensorOrVehicleId().equals(inletId) && r.channel().equals(channel)
                        && r.simTimeS() >= from - 1e-9 && r.simTimeS() <= to + 1e-9)
                .toList();
    }

    /** Result of a scripted capture. */
    public record Capture(double collectStartS, double collectEndS, List<Stage> stages, ChannelVerdict so2,
                          ChannelVerdict co2, double so2MeanPpb, double co2MeanPpm) {
    }

    /**
     * Flies the drone from the depot to {@code target}, then runs setup, background, purge, settle (120 s) and
     * a 30 s collection, and judges both channels with the capture rules. Starts at the current time.
     */
    public Capture flyAndCapture(Enu target, double settleS) {
        final double t0 = timeS();
        final Enu depot = new Enu(0, 0, 2);
        double dist = Math.sqrt(Math.pow(target.east() - depot.east(), 2) + Math.pow(target.north() - depot.north(), 2)
                + Math.pow(target.up() - depot.up(), 2));
        final double flight = dist / 3.0;
        dronePath(t -> {
            double f = Math.min(1.0, Math.max(0.0, (t - t0) / flight));
            return new Enu(depot.east() + f * (target.east() - depot.east()),
                    depot.north() + f * (target.north() - depot.north()),
                    depot.up() + f * (target.up() - depot.up()));
        });
        run(flight);
        double setupStart = timeS();
        run(30);
        double bgStart = timeS();
        run(30);
        double purgeStart = timeS();
        run(30);
        double settleStart = timeS();
        run(settleS);
        double collectStart = timeS();
        sensors.collectionStarted(droneId, collectStart);
        run(30);
        double collectEnd = timeS();
        List<Stage> stages = List.of(new Stage("SETUP", setupStart, bgStart), new Stage("BACKGROUND", bgStart, purgeStart),
                new Stage("PURGE", purgeStart, settleStart), new Stage("SETTLE", settleStart, collectStart),
                new Stage("COLLECT", collectStart, collectEnd));
        List<Reading> so2 = new ArrayList<>(readings(droneId, SensorField.SO2, collectStart, collectEnd));
        so2.addAll(readings(droneId, SensorField.SO2_FLOW, collectStart, collectEnd));
        List<Reading> co2 = new ArrayList<>(readings(droneId, SensorField.CO2, collectStart, collectEnd));
        co2.addAll(readings(droneId, SensorField.CO2_FLOW, collectStart, collectEnd));
        ChannelVerdict vSo2 = captureRules.judge(new CaptureInput("attempt", "TEACH-NODE-ONLINE-2", SensorField.SO2,
                stages, so2));
        ChannelVerdict vCo2 = captureRules.judge(new CaptureInput("attempt", "TEACH-NODE-ONLINE-2", SensorField.CO2,
                stages, co2));
        return new Capture(collectStart, collectEnd, stages, vSo2, vCo2,
                mean(readings(droneId, SensorField.SO2, collectStart, collectStart + 9.99)),
                mean(readings(droneId, SensorField.CO2, collectStart, collectEnd)));
    }

    public static double mean(List<Reading> r) {
        return r.stream().filter(x -> x.value() != null).mapToDouble(Reading::value).average().orElse(Double.NaN);
    }
}
