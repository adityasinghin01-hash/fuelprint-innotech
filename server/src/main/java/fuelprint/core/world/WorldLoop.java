package fuelprint.core.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.api.Detector;
import fuelprint.api.DetectorInput;
import fuelprint.api.InletPose;
import fuelprint.api.ObservationListener;
import fuelprint.api.ObservationProvider;
import fuelprint.api.Reading;
import fuelprint.api.ReadingBatch;
import fuelprint.api.RunContext;
import fuelprint.api.ServerMode;
import fuelprint.api.Trigger;
import fuelprint.api.VehicleSource;
import fuelprint.api.VehicleState;
import fuelprint.api.Velocity;
import fuelprint.api.WindNow;
import fuelprint.api.Window;
import fuelprint.api.WorldClock;
import fuelprint.api.WorldSetup;
import fuelprint.api.WorldStepListener;
import fuelprint.core.Ids;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Session;
import fuelprint.core.live.LiveHub;
import fuelprint.core.run.RunDriver;
import fuelprint.core.run.Runs;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The world loop of CORE-API section 3, on its own thread at 20 wall steps per second. While the loaded run is
 * RUNNING, each step (dt = 0.05 s x time scale of simulation time):
 * <ol>
 *   <li>{@link WorldClock#step}</li>
 *   <li>every {@link WorldStepListener#onStep}</li>
 *   <li>{@link ObservationProvider#observe} at the four fixed inlets plus every {@link VehicleSource} inlet</li>
 *   <li>every {@link ObservationListener#onObservation}</li>
 *   <li>once per simulated second: the {@code reading} records of that second in one transaction (actor
 *       {@code automation}), then {@link Detector#windows} and {@link Detector#evaluate} with their {@code window}
 *       and {@code trigger} records in a second transaction; events are published after each commit</li>
 * </ol>
 * Every second wall step (10 Hz) it broadcasts a {@code tick}, also while paused or with no run, so screens can
 * tell a live server from a stopped one.
 *
 * <p>Also the {@link ClockControl}: time scale 1 or 15, and 15 only while every vehicle is slower than 0.05 m/s.
 * Everything that is stepped (physics, readings, flow, energy, stages) gets the same scaled dt.
 */
@Component
public class WorldLoop implements RunDriver, ClockControl {

    /** A vehicle at or above this speed counts as moving (m/s). */
    public static final double STATIONARY_M_S = 0.05;
    /** Fixed-box readings kept for the detector (simulated seconds). */
    static final int READING_HISTORY_S = 300;
    /** Windows kept for the detector. */
    static final int WINDOW_HISTORY = 60;

    private static final Logger log = LoggerFactory.getLogger(WorldLoop.class);

    private final WorldProfile profile;
    private final ObjectProvider<WorldClock> clocks;
    private final ObjectProvider<ObservationProvider> observers;
    private final ObjectProvider<Detector> detectors;
    private final ObjectProvider<WorldSetup> setups;
    private final ObjectProvider<VehicleSource> vehicleSources;
    private final ObjectProvider<WorldStepListener> stepListeners;
    private final ObjectProvider<ObservationListener> observationListeners;
    private final CommandBus bus;
    private final RecordStore store;
    private final LiveHub live;
    private final ObjectMapper mapper;
    private final ServerMode mode;
    private final boolean autoStart;

    private final Object lock = new Object();
    private final Map<String, Double> scaleRequests = new ConcurrentHashMap<>();
    private final Deque<Reading> recentFixed = new ArrayDeque<>();
    private final Deque<Window> recentWindows = new ArrayDeque<>();
    private final Map<String, String> labelsById = new HashMap<>();
    private final List<InletPose> fixedInlets = new ArrayList<>();
    private final Throttle errors = new Throttle();

    private volatile RunContext loaded;
    private volatile boolean running;
    private volatile double simTimeS;
    private volatile double effectiveScale = 1;
    private long lastSavedSecond;
    private long wallSteps;
    private final java.util.concurrent.atomic.AtomicLong ticks = new java.util.concurrent.atomic.AtomicLong();
    private ReadingBatch lastBatch;
    private List<VehicleState> lastVehicles = List.of();
    private ScheduledExecutorService scheduler;

    public WorldLoop(WorldProfile profile, ObjectProvider<WorldClock> clocks, ObjectProvider<ObservationProvider> observers,
                     ObjectProvider<Detector> detectors, ObjectProvider<WorldSetup> setups,
                     ObjectProvider<VehicleSource> vehicleSources, ObjectProvider<WorldStepListener> stepListeners,
                     ObjectProvider<ObservationListener> observationListeners, CommandBus bus, RecordStore store,
                     LiveHub live, ObjectMapper mapper, ServerMode mode,
                     @Value("${fp.loop.auto:true}") boolean autoStart) {
        this.profile = profile;
        this.clocks = clocks;
        this.observers = observers;
        this.detectors = detectors;
        this.setups = setups;
        this.vehicleSources = vehicleSources;
        this.stepListeners = stepListeners;
        this.observationListeners = observationListeners;
        this.bus = bus;
        this.store = store;
        this.live = live;
        this.mapper = mapper;
        this.mode = mode;
        this.autoStart = autoStart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (autoStart && !mode.isReplay()) {
            startThread();
        }
    }

    /** Starts the 20 Hz loop thread (done at start-up unless {@code fp.loop.auto=false}). */
    public synchronized void startThread() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "fp-world-loop");
            thread.setDaemon(true);
            return thread;
        });
        long periodMs = Math.round(profile.dtS() * 1000);
        scheduler.scheduleAtFixedRate(this::wallStep, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Stops the loop thread (at shutdown; tests use it too). */
    @PreDestroy
    public synchronized void stopThread() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    /** Ticks broadcast so far. */
    public long ticks() {
        return ticks.get();
    }

    /** One wall step (normally every 50 ms on the loop thread; tests call it directly). Never throws. */
    public void wallStep() {
        try {
            synchronized (lock) {
                if (running && loaded != null) {
                    scienceStep();
                }
                wallSteps++;
                if (wallSteps % 2 == 0) {
                    tick();
                }
            }
        } catch (RuntimeException e) {
            if (errors.allow()) {
                log.error("World step failed (further failures logged at most every 5 s)", e);
            }
        }
    }

    private void scienceStep() {
        List<VehicleState> vehicles = vehicles();
        double scale = computeScale(vehicles);
        effectiveScale = scale;
        double dt = profile.dtS() * scale;
        WorldClock clock = clocks.getObject();
        clock.step(dt);
        simTimeS = Math.round((simTimeS + dt) * 1e6) / 1e6;
        double now = simTimeS;
        stepListeners.orderedStream().forEach(listener -> guarded("WorldStepListener", () -> listener.onStep(now, dt)));
        vehicles = vehicles();
        lastVehicles = vehicles;

        List<InletPose> inlets = new ArrayList<>(fixedInlets);
        for (VehicleState vehicle : vehicles) {
            if (vehicle.inlet() != null) {
                inlets.add(new InletPose(vehicle.vehicleId(), vehicle.label(), vehicle.inlet()));
                labelsById.put(vehicle.vehicleId(), vehicle.label());
            }
        }
        ReadingBatch batch = observers.getObject().observe(now, inlets);
        lastBatch = batch;
        observationListeners.orderedStream()
                .forEach(listener -> guarded("ObservationListener", () -> listener.onObservation(now, batch)));

        long second = (long) Math.floor(now + 1e-9);
        if (second > lastSavedSecond) {
            lastSavedSecond = second;
            saveSecond(batch, now, clock.wind());
        }
    }

    private void saveSecond(ReadingBatch batch, double now, WindNow wind) {
        String runId = loaded.runId();
        HandlerResult.Builder readings = HandlerResult.builder();
        for (Reading reading : batch.readings()) {
            ObjectNode fields = mapper.valueToTree(reading);
            readings.save(RecordChange.create("reading", Ids.newId(), runId, fields), "READING_RECORDED", now);
            if (fixedInlets.stream().anyMatch(i -> i.sensorOrVehicleId().equals(reading.sensorOrVehicleId()))) {
                recentFixed.addLast(reading);
            }
        }
        while (!recentFixed.isEmpty() && recentFixed.peekFirst().simTimeS() < now - READING_HISTORY_S) {
            recentFixed.removeFirst();
        }
        HandlerResult readingResult = readings.build();
        if (!readingResult.records().isEmpty()) {
            bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, readingResult, false);
        }

        Detector detector = detectors.getIfAvailable();
        if (detector == null) {
            return;
        }
        DetectorInput input = new DetectorInput(runId, now, List.copyOf(recentFixed), List.copyOf(recentWindows), wind);
        HandlerResult.Builder found = HandlerResult.builder();
        for (Window window : detector.windows(input)) {
            if (window == null || !Ids.isUuid(window.id())) {
                log.warn("Detector returned a window without a UUID id; it was not saved");
                continue;
            }
            ObjectNode fields = mapper.valueToTree(window);
            fields.remove("id");
            found.save(RecordChange.create("window", window.id(), runId, fields), "WINDOW_SAVED", now);
            recentWindows.addLast(window);
            while (recentWindows.size() > WINDOW_HISTORY) {
                recentWindows.removeFirst();
            }
        }
        Optional<Trigger> trigger = detector.evaluate(input);
        trigger.ifPresent(t -> found.save(RecordChange.create("trigger", Ids.newId(), runId, mapper.valueToTree(t)),
                "TRIGGER_RAISED", now));
        HandlerResult foundResult = found.build();
        if (!foundResult.records().isEmpty()) {
            bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, foundResult, false);
        }
    }

    private void tick() {
        RunContext run = loaded;
        if (!running || run == null) {
            lastVehicles = vehicles();
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        WorldClock clock = run == null ? null : clocks.getIfAvailable();
        payload.set("wind", clock == null ? JsonNodeFactory.instance.nullNode() : mapper.valueToTree(clock.wind()));
        payload.put("time_scale", running ? effectiveScale : computeScale(lastVehicles));
        payload.set("vehicles", mapper.valueToTree(lastVehicles));
        ArrayNode latest = payload.putArray("latest_readings");
        if (lastBatch != null && run != null) {
            for (Reading reading : lastBatch.readings()) {
                ObjectNode item = latest.addObject();
                item.put("sensor", labelsById.getOrDefault(reading.sensorOrVehicleId(), reading.sensorOrVehicleId()));
                item.put("channel", reading.channel());
                if (reading.value() == null) {
                    item.putNull("value");
                } else {
                    item.put("value", reading.value());
                }
                item.set("quality", mapper.valueToTree(reading.quality()));
                item.put("sim_time_s", reading.simTimeS());
            }
        }
        live.broadcastTick(run == null ? null : run.runId(), run == null ? null : simTimeS, payload);
        ticks.incrementAndGet();
    }

    private List<VehicleState> vehicles() {
        VehicleSource source = vehicleSources.getIfAvailable();
        if (source == null) {
            return List.of();
        }
        try {
            List<VehicleState> list = source.vehicles();
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            if (errors.allow()) {
                log.error("VehicleSource failed", e);
            }
            return List.of();
        }
    }

    private void guarded(String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            if (errors.allow()) {
                log.error("{} failed in the world loop", what, e);
            }
        }
    }

    // --- ClockControl ---

    @Override
    public double simTimeS() {
        return simTimeS;
    }

    @Override
    public double timeScale() {
        return running ? effectiveScale : 1;
    }

    /**
     * @throws IllegalArgumentException if {@code scale} is not 1 or 15
     * @throws IllegalStateException    if 15 is asked while a vehicle moves (the request is not kept)
     */
    @Override
    public void requestTimeScale(String requester, double scale) {
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester is required");
        }
        if (scale != 1 && scale != 15) {
            throw new IllegalArgumentException("Time scale must be 1 or 15");
        }
        if (scale == 15 && anyMoving(vehicles())) {
            throw new IllegalStateException("Time scale 15 is refused while a vehicle is moving");
        }
        scaleRequests.put(requester, scale);
    }

    @Override
    public void releaseTimeScale(String requester) {
        scaleRequests.remove(requester);
    }

    private double computeScale(List<VehicleState> vehicles) {
        boolean wants15 = scaleRequests.values().stream().anyMatch(v -> v == 15);
        return wants15 && !anyMoving(vehicles) ? 15 : 1;
    }

    static boolean anyMoving(List<VehicleState> vehicles) {
        for (VehicleState vehicle : vehicles) {
            Velocity v = vehicle.velocity();
            if (v != null && Math.sqrt(v.east() * v.east() + v.north() * v.north() + v.up() * v.up()) >= STATIONARY_M_S) {
                return true;
            }
        }
        return false;
    }

    // --- RunDriver ---

    @Override
    public Optional<String> readyFor(String presetId) {
        Map<String, Boolean> needed = new LinkedHashMap<>();
        needed.put("WorldSetup", setups.getIfAvailable() != null);
        needed.put("WorldClock", clocks.getIfAvailable() != null);
        needed.put("ObservationProvider", observers.getIfAvailable() != null);
        List<String> missing = needed.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
        return missing.isEmpty() ? Optional.empty()
                : Optional.of("The world cannot run yet: no " + String.join(", ", missing)
                + " is installed. Until join day use the stub profile (the default) or fake mode (tools/start-fake.sh).");
    }

    @Override
    public void start(RunContext run, double startSimTimeS) {
        synchronized (lock) {
            if (loaded != null) {
                stopLoaded();
            }
            setups.getObject().startRun(run);
            fixedInlets.clear();
            labelsById.clear();
            Map<String, WorldProfile.Node> nodes = new HashMap<>();
            profile.nodes().forEach(n -> nodes.put(n.label(), n));
            for (StoredRecord sensor : store.list(Runs.SENSOR, run.runId())) {
                String label = sensor.body().path("label").asText();
                WorldProfile.Node node = nodes.get(label);
                if (node != null) {
                    fixedInlets.add(new InletPose(sensor.id(), label, node.inlet()));
                    labelsById.put(sensor.id(), label);
                }
            }
            recentFixed.clear();
            recentWindows.clear();
            lastBatch = null;
            simTimeS = startSimTimeS;
            lastSavedSecond = (long) Math.floor(startSimTimeS + 1e-9);
            loaded = run;
            running = true;
        }
    }

    @Override
    public void pause(String runId) {
        synchronized (lock) {
            if (isLoaded(runId)) {
                running = false;
            }
        }
    }

    @Override
    public void resume(String runId) {
        synchronized (lock) {
            if (isLoaded(runId)) {
                running = true;
            }
        }
    }

    @Override
    public void stop(String runId) {
        synchronized (lock) {
            if (isLoaded(runId)) {
                stopLoaded();
            }
        }
    }

    private void stopLoaded() {
        String runId = loaded.runId();
        running = false;
        loaded = null;
        lastBatch = null;
        scaleRequests.clear();
        WorldSetup setup = setups.getIfAvailable();
        if (setup != null) {
            setup.stopRun(runId);
        }
    }

    @Override
    public boolean isLoaded(String runId) {
        RunContext run = loaded;
        return run != null && run.runId().equals(runId);
    }

    @Override
    public Optional<Double> simTimeS(String runId) {
        return isLoaded(runId) ? Optional.of(simTimeS) : Optional.empty();
    }

    /** True while the loaded run is moving. */
    public boolean isRunning() {
        return running;
    }

    /** Lets an error through at most once per 5 seconds. */
    static final class Throttle {
        private long lastMs;

        synchronized boolean allow() {
            long now = System.currentTimeMillis();
            if (now - lastMs >= 5000) {
                lastMs = now;
                return true;
            }
            return false;
        }
    }
}
