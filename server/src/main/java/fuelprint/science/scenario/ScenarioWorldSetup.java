package fuelprint.science.scenario;

import java.time.ZonedDateTime;

import fuelprint.api.RunContext;
import fuelprint.api.WorldSetup;
import fuelprint.science.detect.TeachDetector;
import fuelprint.science.sensor.BackgroundProfile;
import fuelprint.science.sensor.SensorField;
import fuelprint.science.truth.EmissionPlan;
import fuelprint.science.truth.TruthLibrary;
import fuelprint.science.world.PuffWorld;
import fuelprint.science.world.ScienceWorld;
import fuelprint.science.world.WorldProfile;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The real {@link WorldSetup}: loads SC1-NIGHT-PLUME, SC2-BLOCKED-INLET or SC3-ABOVE-THE-BOXES exactly as
 * AGREEMENT section 10 describes them. Which source really emits is read from {@code fuelprint.science.truth}
 * and goes straight into the physics; nothing here hands it to anyone. Seeds are fixed per preset (the seed in
 * {@link RunContext} is not used, so a preset always runs the same way).
 */
@Component
@Profile("!stub")
public class ScenarioWorldSetup implements WorldSetup {

    /** Sim time zero is 02:00 IST on this night (inside the Kundli wind history of Nov 2025 to Jan 2026). */
    public static final ZonedDateTime START_LOCAL = ZonedDateTime.of(2026, 1, 15, 2, 0, 0, 0,
            BackgroundProfile.IST);

    private final ScienceWorld world;
    private final SensorField sensors;
    private final TeachDetector detector;
    private final WorldProfile profile = WorldProfile.load();

    public ScenarioWorldSetup(ScienceWorld world, SensorField sensors, TeachDetector detector) {
        this.world = world;
        this.sensors = sensors;
        this.detector = detector;
    }

    @Override
    public void startRun(RunContext run) {
        if (!TruthLibrary.knows(run.presetId())) {
            throw new IllegalArgumentException("Unknown preset " + run.presetId());
        }
        EmissionPlan plan = TruthLibrary.plan(run.presetId()).orElseThrow();
        long seed = TruthLibrary.seed(run.presetId());
        PuffWorld puffs = new PuffWorld(profile);
        world.load(run.runId(), puffs, plan);
        sensors.reset(seed, START_LOCAL, "SC2-BLOCKED-INLET".equals(run.presetId()));
        detector.reset();
    }

    @Override
    public void stopRun(String runId) {
        world.unload(runId);
        sensors.reset(1, START_LOCAL, false);
        detector.reset();
    }
}
