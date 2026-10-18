package fuelprint.core.read;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.api.ServerMode;
import fuelprint.core.Ids;
import fuelprint.core.command.CommandLog;
import fuelprint.core.live.LiveHub;
import fuelprint.core.run.Presets;
import fuelprint.core.run.Runs;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read API of AGREEMENT section 3. Reads need no login: every record is public teaching data (no truth, and
 * phone numbers never reach a record). The {@code PhoneGuardFilter} still checks every response.
 */
@RestController
public class ReadController {

    /** Record types not tied to a run (AGREEMENT section 3): list them without {@code run_id}. */
    public static final Set<String> RUN_INDEPENDENT = Set.of("model_version", "alert_subscription", "notification",
            "automation_settings");

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final int LATEST_READINGS_SCAN = 400;

    private final RecordStore store;
    private final LiveHub live;
    private final ClockControl clock;
    private final ServerMode mode;
    private final CommandLog commands;

    public ReadController(RecordStore store, LiveHub live, ClockControl clock, ServerMode mode, CommandLog commands) {
        this.store = store;
        this.live = live;
        this.clock = clock;
        this.mode = mode;
        this.commands = commands;
    }

    /** The current run (the active one, else the newest), the cursor, the clock and the presets. */
    @GetMapping("/api/runs/current")
    public ObjectNode currentRun() {
        long cursor = live.cursor();
        Optional<StoredRecord> run = Runs.current(store.listAll(Runs.RUN));
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("server_epoch", live.serverEpoch());
        out.put("mode", mode.isReplay() ? "replay" : "live");
        out.put("cursor", cursor);
        out.put("run_id", run.map(StoredRecord::id).orElse(null));
        out.put("state", run.map(Runs::state).orElse(null));
        out.set("run", run.<com.fasterxml.jackson.databind.JsonNode>map(StoredRecord::body)
                .orElse(JsonNodeFactory.instance.nullNode()));
        out.put("sim_time_s", clock.simTimeS());
        out.put("time_scale", clock.timeScale());
        ArrayNode presets = out.putArray("presets");
        for (Presets.Preset preset : Presets.ALL) {
            presets.addObject().put("preset_id", preset.id()).put("description", preset.description());
        }
        return out;
    }

    /**
     * Everything current about one run (default: the current run) plus the records not tied to a run, and the
     * {@code cursor} to give {@code /ws/live?after=}. The cursor is read first, so no event can be missed (an
     * event may arrive twice; screens keep the higher revision). {@code reading} records are left out unless
     * {@code include_readings=true}; {@code latest_readings} has the newest reading per sensor and channel.
     */
    @GetMapping("/api/snapshot")
    public ResponseEntity<ObjectNode> snapshot(@RequestParam(name = "run_id", required = false) String runId,
                                               @RequestParam(name = "include_readings", defaultValue = "false")
                                               boolean includeReadings) {
        long cursor = live.cursor();
        if (runId != null && !Ids.isUuid(runId)) {
            return bad("run_id must be a UUID.");
        }
        Optional<StoredRecord> run = runId == null ? Runs.current(store.listAll(Runs.RUN)) : store.get(Runs.RUN, runId);
        if (runId != null && run.isEmpty()) {
            return notFound("There is no run " + runId + ".");
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("schema_version", LiveHub.SCHEMA_VERSION);
        out.put("server_epoch", live.serverEpoch());
        out.put("mode", mode.isReplay() ? "replay" : "live");
        out.put("cursor", cursor);
        out.put("run_id", run.map(StoredRecord::id).orElse(null));
        out.set("run", run.<com.fasterxml.jackson.databind.JsonNode>map(StoredRecord::body)
                .orElse(JsonNodeFactory.instance.nullNode()));
        ObjectNode records = out.putObject("records");
        List<String> excluded = includeReadings ? List.of() : List.of("reading");
        if (run.isPresent()) {
            group(records, store.listRun(run.get().id(), excluded));
        }
        group(records, store.listRun(null, List.of()));
        ArrayNode latest = out.putArray("latest_readings");
        if (run.isPresent()) {
            Map<String, Boolean> seen = new HashMap<>();
            for (StoredRecord reading : store.newest("reading", run.get().id(), LATEST_READINGS_SCAN)) {
                var body = reading.body();
                String key = body.path("sensor_or_vehicle_id").asText() + "/" + body.path("channel").asText();
                if (seen.putIfAbsent(key, true) == null) {
                    latest.add(body);
                }
            }
        }
        return ResponseEntity.ok(out);
    }

    private static void group(ObjectNode records, List<StoredRecord> list) {
        for (StoredRecord record : list) {
            ArrayNode ofType = records.has(record.type()) ? (ArrayNode) records.get(record.type())
                    : records.putArray(record.type());
            ofType.add(record.body());
        }
    }

    /** One record, latest revision (or {@code ?revision=}). */
    @GetMapping("/api/records/{type}/{id}")
    public ResponseEntity<?> record(@PathVariable String type, @PathVariable String id,
                                    @RequestParam(name = "revision", required = false) Long revision) {
        if (!TYPE.matcher(type).matches() || !Ids.isUuid(id)) {
            return bad("Record type must be lower snake case and id a UUID.");
        }
        Optional<StoredRecord> found = revision == null ? store.get(type, id) : store.get(type, id, revision);
        return found.<ResponseEntity<?>>map(r -> ResponseEntity.ok(r.body()))
                .orElseGet(() -> notFound("There is no " + type + " " + id + "."));
    }

    /**
     * All records of one type in a run, latest revisions, oldest first. Omit {@code run_id} for model_version,
     * alert_subscription, notification and automation_settings, and for {@code run} (every run).
     */
    @GetMapping("/api/records/{type}")
    public ResponseEntity<?> records(@PathVariable String type,
                                     @RequestParam(name = "run_id", required = false) String runId) {
        if (!TYPE.matcher(type).matches()) {
            return bad("Record type must be lower snake case.");
        }
        if (runId == null) {
            if (Runs.RUN.equals(type)) {
                return ResponseEntity.ok(store.listAll(Runs.RUN).stream().map(StoredRecord::body).toList());
            }
            if (!RUN_INDEPENDENT.contains(type)) {
                return bad("run_id is required for " + type + " records.");
            }
            return ResponseEntity.ok(store.list(type, null).stream().map(StoredRecord::body).toList());
        }
        if (!Ids.isUuid(runId)) {
            return bad("run_id must be a UUID.");
        }
        return ResponseEntity.ok(store.list(type, runId).stream().map(StoredRecord::body).toList());
    }

    /**
     * The automation log (AGREEMENT section 12): receipts of commands the {@code automation} actor sent, newest
     * first, with when and what. Optional {@code run_id} (commands with no run are always included).
     */
    @GetMapping("/api/automation-log")
    public ResponseEntity<?> automationLog(@RequestParam(name = "run_id", required = false) String runId,
                                           @RequestParam(name = "limit", defaultValue = "200") int limit) {
        if (runId != null && !Ids.isUuid(runId)) {
            return bad("run_id must be a UUID.");
        }
        return ResponseEntity.ok(commands.automationEntries(runId, Math.max(1, Math.min(limit, 1000))));
    }

    static ResponseEntity<ObjectNode> bad(String text) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID", text);
    }

    static ResponseEntity<ObjectNode> notFound(String text) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", text);
    }

    static ResponseEntity<ObjectNode> problem(HttpStatus status, String code, String text) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("reason_code", code);
        body.put("reason_text", text);
        return ResponseEntity.status(status).body(body);
    }
}
