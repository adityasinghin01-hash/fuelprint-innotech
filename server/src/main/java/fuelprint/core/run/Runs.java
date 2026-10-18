package fuelprint.core.run;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.RunContext;
import fuelprint.core.Ids;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.StoredRecord;
import fuelprint.core.world.WorldProfile;
import org.springframework.stereotype.Component;

/**
 * Builds and reads {@code run} and {@code sensor} records (AGREEMENT sections 6 and 8). Shared by the run commands,
 * start-up recovery and fake replay, so every path makes runs the same way.
 *
 * <p>Run record fields: {@code preset_id}, {@code mode}, {@code state}, {@code parent_run_id} (AGREEMENT), plus
 * {@code seed}, {@code sim_time_s} (where the clock stood at this change) and {@code state_reason} (null,
 * {@code INTERRUPTED}, {@code RESET}, {@code REPLAY_LOOP}, {@code WORLD_CAPACITY}) added by the core.
 */
@Component
public class Runs {

    public static final String RUN = "run";
    public static final String SENSOR = "sensor";

    public static final String CREATED = "CREATED";
    public static final String RUNNING = "RUNNING";
    public static final String PAUSED = "PAUSED";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";

    /** States in which a run is still the current one. */
    public static final Set<String> ACTIVE = Set.of(CREATED, RUNNING, PAUSED);

    private final WorldProfile world;

    public Runs(WorldProfile world) {
        this.world = world;
    }

    /** The active run (CREATED, RUNNING or PAUSED) among {@code allRuns} (oldest first); there is at most one. */
    public static Optional<StoredRecord> active(List<StoredRecord> allRuns) {
        return allRuns.stream().filter(r -> ACTIVE.contains(state(r))).reduce((a, b) -> b);
    }

    /** The run screens should show: the active one, otherwise the most recently made one. */
    public static Optional<StoredRecord> current(List<StoredRecord> allRuns) {
        Optional<StoredRecord> active = active(allRuns);
        return active.isPresent() || allRuns.isEmpty() ? active : Optional.of(allRuns.get(allRuns.size() - 1));
    }

    public static String state(StoredRecord run) {
        return run.body().path("state").asText();
    }

    public static RunContext context(StoredRecord run) {
        var body = run.body();
        return new RunContext(run.id(), body.path("preset_id").asText(), body.path("mode").asText(),
                body.path("seed").asLong());
    }

    public static double simTime(StoredRecord run) {
        return run.body().path("sim_time_s").asDouble(0);
    }

    /** A new run in state RUNNING plus its four sensor records, with events RUN_CREATED and SENSOR_CREATED. */
    public HandlerResult.Builder newRun(HandlerResult.Builder builder, String runId, Presets.Preset preset, String mode,
                                        String parentRunId) {
        ObjectNode run = JsonNodeFactory.instance.objectNode();
        run.put("preset_id", preset.id());
        run.put("mode", mode);
        run.put("state", RUNNING);
        run.put("parent_run_id", parentRunId);
        run.put("seed", preset.seed());
        run.put("sim_time_s", 0.0);
        run.putNull("state_reason");
        builder.save(RecordChange.create(RUN, runId, runId, run), "RUN_CREATED", 0.0);
        for (WorldProfile.Node node : world.nodes()) {
            ObjectNode sensor = JsonNodeFactory.instance.objectNode();
            sensor.put("label", node.label());
            ObjectNode inlet = sensor.putObject("inlet");
            inlet.put("east", node.inlet().east());
            inlet.put("north", node.inlet().north());
            inlet.put("up", node.inlet().up());
            ArrayNode channels = sensor.putArray("channels");
            WorldProfile.SENSOR_CHANNELS.forEach(channels::add);
            sensor.put("status", "OK");
            sensor.put("calibration_state", "VALID_SYNTHETIC");
            builder.save(RecordChange.create(SENSOR, Ids.newId(), runId, sensor), "SENSOR_CREATED", 0.0);
        }
        return builder;
    }

    /** The next revision of {@code run} in {@code state}, at {@code simTimeS}, with {@code reason} (may be null). */
    public static RecordChange changeState(StoredRecord run, String state, double simTimeS, String reason) {
        ObjectNode body = run.body();
        for (String common : RecordChange.COMMON_FIELDS) {
            body.remove(common);
        }
        body.put("state", state);
        body.put("sim_time_s", simTimeS);
        body.put("state_reason", reason);
        return RecordChange.next(run, body);
    }
}
