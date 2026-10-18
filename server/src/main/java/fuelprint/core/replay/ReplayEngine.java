package fuelprint.core.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import fuelprint.api.ClockControl;
import fuelprint.api.RunContext;
import fuelprint.api.ServerMode;
import fuelprint.core.Ids;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.RecordedCommand;
import fuelprint.core.command.Session;
import fuelprint.core.live.LiveHub;
import fuelprint.core.run.Presets;
import fuelprint.core.run.RunDriver;
import fuelprint.core.run.Runs;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Fake mode ({@code --fp.mode=replay}, CORE-API section 7): plays {@code classpath:replay/SC1.ndjson} (and SC2,
 * SC3) as if live, at real timing times {@code fp.replay.speed}. Records go into the real store (so ops and cases
 * handlers can work on them), events and ticks go out on {@code /ws/live}. At the end of a file the run is saved
 * COMPLETED and the next scenario of {@code fp.replay.scenarios} starts as a NEW run (all ids made fresh).
 *
 * <p>The run commands still work in fake mode: {@code create_run}/{@code reset_run} start that preset's recording,
 * {@code pause_run}/{@code resume_run} hold and continue it.
 *
 * <p>File format (one JSON object per line; {@code tools/replay/make_replay.py} writes them):
 * <ul>
 *   <li>{@code header}: {@code scenario}, {@code preset_id}, {@code duration_s}, {@code keep_ids} (ids that stay the
 *       same in every loop, like model versions and the drone)</li>
 *   <li>{@code frame} at {@code t}: {@code wind}, {@code vehicles} (tick vehicle objects), {@code readings}
 *       ({@code sensor} label, {@code sensor_or_vehicle_id}, channel, value, raw_value, unit, quality); one per
 *       second. Readings are saved as {@code reading} records; ticks are made at 10 Hz by moving each vehicle in a
 *       straight line between frames.</li>
 *   <li>{@code write} at {@code t}: records saved by the server itself ({@code actor}, {@code records[]})</li>
 *   <li>{@code command} at {@code t}: a recorded command with its receipt ({@code command_type},
 *       {@code command_id}, {@code actor}, {@code status}, {@code reason_code}, {@code reason_text},
 *       {@code records[]})</li>
 * </ul>
 * Each record entry is {@code {type, id, run_id, event_type, fields}}. The string {@code "@run"} means the current
 * run id and {@code "@sensor:N2"} the id of that run's N2 sensor; every other UUID is replaced by a fresh one per
 * loop (consistently), except {@code keep_ids}. A record whose id is kept and that already exists at that revision
 * is skipped, so model versions are made once.
 */
@Component
@Order(10)
public class ReplayEngine implements RunDriver, ClockControl, ApplicationRunner {

    static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static final double TICK_S = 0.1;

    private static final Logger log = LoggerFactory.getLogger(ReplayEngine.class);

    private final ServerMode mode;
    private final CommandBus bus;
    private final RecordStore store;
    private final fuelprint.core.run.Runs runs;
    private final LiveHub live;
    private final ObjectMapper mapper;
    private final double speed;
    private final List<String> rotation;
    private final boolean autoStart;
    private final Object lock = new Object();

    private ScheduledExecutorService scheduler;
    private RunContext loaded;
    private boolean playing;
    private double t;
    private Recording recording;
    private int next;
    private ObjectNode frame;
    private ObjectNode nextFrame;
    private Map<String, String> ids = new HashMap<>();

    public ReplayEngine(ServerMode mode, CommandBus bus, RecordStore store, fuelprint.core.run.Runs runs, LiveHub live,
                        ObjectMapper mapper, @Value("${fp.replay.speed:1}") double speed,
                        @Value("${fp.replay.scenarios:SC1,SC2,SC3}") String scenarios,
                        @Value("${fp.loop.auto:true}") boolean autoStart) {
        this.mode = mode;
        this.bus = bus;
        this.store = store;
        this.runs = runs;
        this.live = live;
        this.mapper = mapper;
        this.speed = speed > 0 ? speed : 1;
        this.rotation = Arrays.stream(scenarios.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.autoStart = autoStart;
    }

    /** A parsed recording. */
    record Recording(String scenario, String presetId, double durationS, Set<String> keepIds, List<ObjectNode> lines,
                     List<ObjectNode> frames) {
    }

    /** Reads {@code classpath:replay/<scenario>.ndjson}. */
    static Recording load(String scenario, ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource("replay/" + scenario + ".ndjson");
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            ObjectNode header = null;
            List<ObjectNode> lines = new ArrayList<>();
            List<ObjectNode> frames = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                ObjectNode node = (ObjectNode) mapper.readTree(line);
                String kind = node.path("kind").asText();
                if ("header".equals(kind)) {
                    header = node;
                } else {
                    lines.add(node);
                    if ("frame".equals(kind)) {
                        frames.add(node);
                    }
                }
            }
            if (header == null) {
                throw new IllegalStateException("replay/" + scenario + ".ndjson has no header line");
            }
            lines.sort((a, b) -> Double.compare(a.path("t").asDouble(), b.path("t").asDouble()));
            frames.sort((a, b) -> Double.compare(a.path("t").asDouble(), b.path("t").asDouble()));
            Set<String> keep = new HashSet<>();
            header.path("keep_ids").forEach(id -> keep.add(id.asText()));
            return new Recording(scenario, header.path("preset_id").asText(), header.path("duration_s").asDouble(),
                    keep, lines, frames);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read replay/" + scenario + ".ndjson", e);
        }
    }

    // --- start-up ---

    @Override
    public void run(ApplicationArguments args) {
        if (!mode.isReplay()) {
            return;
        }
        log.info("FAKE MODE: playing recorded scenarios {} at speed {}", rotation, speed);
        List<StoredRecord> all = store.listAll(Runs.RUN);
        Optional<StoredRecord> active = Runs.active(all);
        HandlerResult.Builder builder = HandlerResult.builder();
        String parent = null;
        if (active.isPresent()) {
            builder.save(Runs.changeState(active.get(), Runs.COMPLETED, Runs.simTime(active.get()), "REPLAY_RESTART"),
                    "RUN_COMPLETED", Runs.simTime(active.get()));
            parent = active.get().id();
        }
        Presets.Preset first = Presets.byReplayName(rotation.isEmpty() ? "SC1" : rotation.get(0)).orElseThrow();
        runs.newRun(builder, Ids.newId(), first, "BLIND", parent);
        bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, builder.build(), false).join();
        if (autoStart) {
            startThread();
        }
    }

    void startThread() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "fp-replay");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::tickStep, 100, 100, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopThread() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // --- playing ---

    /** One 100 ms wall step: advance the clock, play due lines, send a tick. Never throws. */
    public void tickStep() {
        try {
            synchronized (lock) {
                if (loaded != null && playing) {
                    t = Math.round((t + TICK_S * speed) * 1e6) / 1e6;
                    playDue();
                    if (next >= recording.lines().size() && t >= recording.durationS()) {
                        loop();
                    }
                }
                sendTick();
            }
        } catch (RuntimeException e) {
            log.error("Replay step failed", e);
        }
    }

    private void playDue() {
        while (next < recording.lines().size() && recording.lines().get(next).path("t").asDouble() <= t + 1e-9) {
            ObjectNode line = (ObjectNode) resolve(recording.lines().get(next));
            next++;
            switch (line.path("kind").asText()) {
                case "frame" -> playFrame(line);
                case "write" -> playWrite(line);
                case "command" -> playCommand(line);
                default -> log.warn("Unknown replay line kind {}", line.path("kind").asText());
            }
        }
    }

    private void playFrame(ObjectNode line) {
        frame = line;
        int index = indexOfFrame(line.path("t").asDouble());
        nextFrame = index + 1 < recording.frames().size() ? (ObjectNode) resolve(recording.frames().get(index + 1)) : null;
        HandlerResult.Builder readings = HandlerResult.builder();
        double at = line.path("t").asDouble();
        for (JsonNode reading : line.path("readings")) {
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("sensor_or_vehicle_id", reading.path("sensor_or_vehicle_id").asText());
            fields.put("channel", reading.path("channel").asText());
            fields.set("value", reading.path("value"));
            fields.set("raw_value", reading.has("raw_value") ? reading.get("raw_value") : reading.path("value"));
            fields.put("unit", reading.path("unit").asText());
            fields.put("sim_time_s", at);
            fields.set("quality", reading.has("quality") ? reading.get("quality") : mapper.createArrayNode().add("OK"));
            fields.put("origin", "SYNTHETIC_TEACHING");
            readings.save(RecordChange.create("reading", Ids.newId(), loaded.runId(), fields), "READING_RECORDED", at);
        }
        HandlerResult result = readings.build();
        if (!result.records().isEmpty()) {
            bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, result, false);
        }
    }

    private int indexOfFrame(double at) {
        List<ObjectNode> frames = recording.frames();
        for (int i = 0; i < frames.size(); i++) {
            if (Math.abs(frames.get(i).path("t").asDouble() - at) < 1e-9) {
                return i;
            }
        }
        return frames.size() - 1;
    }

    private HandlerResult records(JsonNode line, double at) {
        HandlerResult.Builder builder = HandlerResult.builder();
        for (JsonNode entry : line.path("records")) {
            String type = entry.path("type").asText();
            String id = entry.path("id").asText();
            long revision = entry.path("revision").asLong(1);
            if (recording.keepIds().contains(id) && store.latestRevision(type, id) >= revision) {
                continue; // made in an earlier loop
            }
            String runId = entry.path("run_id").isNull() ? null : entry.path("run_id").asText(null);
            ObjectNode fields = entry.path("fields").deepCopy();
            builder.save(new RecordChange(type, id, runId, 0, fields), entry.path("event_type").asText("RECORD_SAVED"),
                    entry.path("run_id").isNull() ? null : at);
        }
        return builder.build();
    }

    private void playWrite(ObjectNode line) {
        HandlerResult result = records(line, line.path("t").asDouble());
        if (!result.records().isEmpty()) {
            bus.saveWithoutCommand(line.path("actor").asText(Session.AUTOMATION_ACTOR), result, true);
        }
    }

    private void playCommand(ObjectNode line) {
        HandlerResult result = records(line, line.path("t").asDouble());
        String actor = line.path("actor").asText(Session.AUTOMATION_ACTOR);
        bus.saveRecordedCommand(new RecordedCommand(line.path("command_id").asText(), line.path("command_type").asText(),
                loaded.runId(), actor, Receipt.Status.valueOf(line.path("status").asText("APPLIED")),
                line.path("reason_code").asText(null), line.path("reason_text").asText(null), result));
    }

    /** End of the recording: this run COMPLETED, the next scenario as a NEW run. */
    private void loop() {
        StoredRecord run = store.get(Runs.RUN, loaded.runId()).orElse(null);
        playing = false;
        if (run == null) {
            return;
        }
        int at = Math.max(0, rotation.indexOf(recording.scenario()));
        String nextName = rotation.isEmpty() ? recording.scenario() : rotation.get((at + 1) % rotation.size());
        Presets.Preset preset = Presets.byReplayName(nextName).orElseThrow();
        HandlerResult.Builder builder = HandlerResult.builder()
                .save(Runs.changeState(run, Runs.COMPLETED, t, "REPLAY_LOOP"), "RUN_COMPLETED", t);
        runs.newRun(builder, Ids.newId(), preset, loaded.mode(), run.id());
        bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, builder.build(), false);
    }

    private void sendTick() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        if (frame == null || loaded == null) {
            payload.putNull("wind");
            payload.put("time_scale", 1);
            payload.putArray("vehicles");
            payload.putArray("latest_readings");
            live.broadcastTick(loaded == null ? null : loaded.runId(), loaded == null ? null : t, payload);
            return;
        }
        payload.set("wind", frame.path("wind"));
        payload.put("time_scale", frame.path("time_scale").asDouble(1));
        payload.set("vehicles", interpolate());
        ArrayNode latest = payload.putArray("latest_readings");
        for (JsonNode reading : frame.path("readings")) {
            ObjectNode item = latest.addObject();
            item.put("sensor", reading.path("sensor").asText());
            item.put("channel", reading.path("channel").asText());
            item.set("value", reading.path("value"));
            item.set("quality", reading.has("quality") ? reading.get("quality") : mapper.createArrayNode().add("OK"));
            item.put("sim_time_s", frame.path("t").asDouble());
        }
        live.broadcastTick(loaded.runId(), t, payload);
    }

    /** Vehicles of the current frame, moved in a straight line toward the next frame by the clock. */
    ArrayNode interpolate() {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        double t0 = frame.path("t").asDouble();
        double t1 = nextFrame == null ? t0 : nextFrame.path("t").asDouble();
        double f = t1 > t0 ? Math.max(0, Math.min(1, (t - t0) / (t1 - t0))) : 0;
        for (JsonNode vehicle : frame.path("vehicles")) {
            ObjectNode copy = vehicle.deepCopy();
            JsonNode later = null;
            if (nextFrame != null) {
                for (JsonNode candidate : nextFrame.path("vehicles")) {
                    if (candidate.path("vehicle_id").asText().equals(vehicle.path("vehicle_id").asText())) {
                        later = candidate;
                    }
                }
            }
            if (later != null && f > 0) {
                for (String part : List.of("pose", "inlet")) {
                    ObjectNode a = (ObjectNode) copy.path(part);
                    JsonNode b = later.path(part);
                    for (String axis : List.of("east", "north", "up")) {
                        if (a.has(axis) && b.has(axis)) {
                            double v = a.path(axis).asDouble() + (b.path(axis).asDouble() - a.path(axis).asDouble()) * f;
                            a.put(axis, Math.round(v * 100) / 100.0);
                        }
                    }
                }
                if (later.has("energy_wh") && copy.has("energy_wh") && copy.get("energy_wh").isNumber()) {
                    double e = copy.path("energy_wh").asDouble()
                            + (later.path("energy_wh").asDouble() - copy.path("energy_wh").asDouble()) * f;
                    copy.put("energy_wh", Math.round(e * 10) / 10.0);
                }
            }
            out.add(copy);
        }
        return out;
    }

    /** The line with "@run", "@sensor:N2" and remapped UUIDs filled in. */
    JsonNode resolve(JsonNode node) {
        if (node.isTextual()) {
            String text = node.asText();
            if ("@run".equals(text)) {
                return TextNode.valueOf(loaded.runId());
            }
            if (text.startsWith("@sensor:")) {
                return TextNode.valueOf(ids.getOrDefault(text, text));
            }
            if (UUID.matcher(text).matches() && !recording.keepIds().contains(text)) {
                return TextNode.valueOf(ids.computeIfAbsent(text, k -> Ids.newId()));
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.set(field.getKey(), resolve(field.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(resolve(item)));
            return out;
        }
        return node;
    }

    // --- RunDriver ---

    @Override
    public Optional<String> readyFor(String presetId) {
        Optional<Presets.Preset> preset = Presets.find(presetId);
        if (preset.isEmpty() || !new ClassPathResource("replay/" + preset.get().replayName() + ".ndjson").exists()) {
            return Optional.of("Fake mode has no recording for " + presetId + ".");
        }
        return Optional.empty();
    }

    @Override
    public void start(RunContext run, double simTimeS) {
        synchronized (lock) {
            Presets.Preset preset = Presets.find(run.presetId()).orElseThrow();
            recording = load(preset.replayName(), mapper);
            loaded = run;
            ids = new HashMap<>();
            for (StoredRecord sensor : store.list(Runs.SENSOR, run.runId())) {
                ids.put("@sensor:" + sensor.body().path("label").asText(), sensor.id());
            }
            t = simTimeS;
            next = 0;
            frame = null;
            nextFrame = null;
            if (simTimeS > 0) {
                // Resumed after a restart: skip what was already played (later lines get fresh ids).
                while (next < recording.lines().size() && recording.lines().get(next).path("t").asDouble() <= simTimeS) {
                    if ("frame".equals(recording.lines().get(next).path("kind").asText())) {
                        frame = (ObjectNode) resolve(recording.lines().get(next));
                    }
                    next++;
                }
            }
            playing = true;
            log.info("Fake mode: playing {} as run {}", preset.replayName(), run.runId());
        }
    }

    @Override
    public void pause(String runId) {
        synchronized (lock) {
            if (isLoaded(runId)) {
                playing = false;
            }
        }
    }

    @Override
    public void resume(String runId) {
        synchronized (lock) {
            if (isLoaded(runId)) {
                playing = true;
            }
        }
    }

    @Override
    public void stop(String runId) {
        synchronized (lock) {
            if (isLoaded(runId)) {
                loaded = null;
                playing = false;
                frame = null;
                nextFrame = null;
            }
        }
    }

    @Override
    public boolean isLoaded(String runId) {
        RunContext run = loaded;
        return run != null && run.runId().equals(runId);
    }

    @Override
    public Optional<Double> simTimeS(String runId) {
        synchronized (lock) {
            return isLoaded(runId) ? Optional.of(t) : Optional.empty();
        }
    }

    // --- ClockControl ---

    @Override
    public double simTimeS() {
        synchronized (lock) {
            return t;
        }
    }

    @Override
    public double timeScale() {
        return 1;
    }

    /** @throws IllegalStateException always: the recording sets its own pace */
    @Override
    public void requestTimeScale(String requester, double scale) {
        throw new IllegalStateException("Fake replay mode plays at its recorded pace; the time scale cannot change");
    }

    @Override
    public void releaseTimeScale(String requester) {
        // nothing to release
    }
}
