package fuelprint.field.world;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.InletHistory;
import fuelprint.api.InletSample;
import fuelprint.api.ObservationListener;
import fuelprint.api.Pilot;
import fuelprint.api.Reading;
import fuelprint.api.ReadingBatch;
import fuelprint.api.VehiclePose;
import fuelprint.api.VehicleSource;
import fuelprint.api.VehicleState;
import fuelprint.api.WorldStepListener;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.FieldAutomation;
import fuelprint.field.FieldPorts;
import fuelprint.field.FieldTime;
import fuelprint.field.geo.Area;
import fuelprint.field.geo.Obstacles;
import fuelprint.field.geo.PathPlanner;
import fuelprint.field.geo.Vec3;
import fuelprint.field.profile.CaptureMethod;
import fuelprint.field.profile.WorldProfile;
import fuelprint.field.vehicle.Drone;
import fuelprint.field.vehicle.Energy;
import fuelprint.field.vehicle.Kinematics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The live field world: the drone, who is flying it, the current mission, the autopilot and the capture
 * stages. The core world loop calls {@link #onStep} (20 Hz), {@link #onObservation} and {@link #vehicles}.
 *
 * <p>Records stay the truth: the world mirrors the {@code mission}, {@code lease}, {@code attempt} and
 * {@code pairing} records after each commit (see {@code FieldRecordListener}) and never saves anything
 * itself. Every automatic step (mission moved by position, lease expired, autopilot start, automatic
 * capture, battery return) is sent as a command through {@link FieldAutomation}, once per step (a latch),
 * and never retried in a loop. The one thing done directly is the safety stop: the dead man, neutral
 * input, take over and the end of a run set the velocity to zero at once.
 *
 * <p>Threads: the loop thread, the command thread, socket threads and the safety loop all use one lock.
 * Calls to other packages ({@link FieldPorts}) and command submissions run after the lock is released.
 * Waits for a pilot are counted in loop seconds ({@code dt / time scale}), so they pause with the run;
 * leases and the dead man use wall time ({@link FieldTime}), which a pause does not stop.
 */
@Component
@Profile("!stub")
public class FieldWorld implements VehicleSource, WorldStepListener, ObservationListener, InletHistory {

    private static final Logger log = LoggerFactory.getLogger(FieldWorld.class);

    public static final String AUTOPILOT_HOLDER = "autopilot";
    public static final String TIME_SCALE_REQUESTER = "field-capture";
    public static final long LEASE_MS = 2000;
    public static final long DEAD_MAN_MS = 400;
    static final long MIN_INPUT_GAP_MS = 40;
    public static final double RESUME_AFTER_S = 10;
    public static final double TARGET_HORIZONTAL_M = 5;
    public static final double HEIGHT_BAND_M = 2;
    public static final double CAPTURE_TIME_SCALE = 15;
    static final double RETURN_ENERGY_MARGIN = 1.2;
    static final double LANDED_HORIZONTAL_M = 1.5;
    static final double LANDED_HEIGHT_M = 0.5;
    // Every world step: at most 20 Hz * 900 simulated seconds, plus a possible time-zero sample.
    // Keep a hard cap even if a caller steps past the run limit; older intervals then become unknown.
    private static final int MAX_INLET_SAMPLES = 18_001;

    private final WorldProfile profile;
    private final CaptureMethod method;
    private final FieldTime time;
    private final FieldPorts ports;
    private final FieldAutomation automation;
    private final Obstacles obstacles;
    private final Kinematics kinematics;
    private final PathPlanner planner;
    private final Energy energy;
    private final Autopilot autopilot;
    private final Drone drone;

    private final Object lock = new Object();
    private final List<Runnable> outbox = new ArrayList<>();

    // Everything below is guarded by lock.
    private String runId;
    // Only RunStarted enables recording: late mission-cleanup records cannot revive an ended track.
    private String inletRunId;
    private final Deque<InletSample> inletSamples = new ArrayDeque<>();
    private double simTimeS;
    private final Map<String, MissionView> missions = new LinkedHashMap<>();
    private final Map<String, PairingView> pairings = new LinkedHashMap<>();
    private final List<QueueEntry> queue = new ArrayList<>();
    private LiveLease lease;
    private boolean autopilotEnabled;
    private String autopilotMissionId;
    private boolean autopilotNoPathLogged;
    private double noPilotS;
    private String noPilotMissionId;
    private int pilotEpisode;
    private CaptureRun capture;
    private boolean attemptUpdateInFlight;
    private boolean attemptUpdateDirty;
    private final Set<String> latches = new HashSet<>();
    private boolean timeScaleRequested;
    private int flight;

    public FieldWorld(WorldProfile profile, CaptureMethod method, FieldTime time, FieldPorts ports,
                      FieldAutomation automation) {
        this.profile = profile;
        this.method = method;
        this.time = time;
        this.ports = ports;
        this.automation = automation;
        this.obstacles = new Obstacles(profile.obstacles(), profile.obstacleMarginM());
        this.kinematics = new Kinematics(profile, obstacles);
        Area area = new Area(profile.xyMin(), profile.xyMax(), profile.zMin(), profile.zMax());
        this.planner = new PathPlanner(obstacles, area);
        this.energy = new Energy(profile);
        this.autopilot = new Autopilot(planner, profile.maxSpeedMS(), profile.maxAccelMS2());
        this.drone = new Drone(UUID.randomUUID().toString(), "DRONE-1", profile.depot(), profile.usableWh());
    }

    /** A pairing as the world tracks it (for expiry and the queue). */
    record PairingView(String id, String missionId, String state, Instant expires, String redeemedBy) {
    }

    /** A confirmed visitor waiting for (or holding) the lease of a mission. */
    public record QueueEntry(String missionId, String visitor, String pairingId) {
    }

    /**
     * The active lease as handlers see it.
     *
     * @param ending true once it is being ended; input is no longer accepted
     */
    public record LeaseInfo(String leaseId, String runId, String missionId, String holder, Pilot kind,
                            boolean homing, Long lastSequence, boolean ending) {
        public boolean person() {
            return kind != Pilot.AUTOPILOT;
        }
    }

    /** What happened to one pilot input message. */
    public enum InputResult {
        ACCEPTED, NO_LEASE, NOT_HOLDER, LEASE_ENDED, STALE_SEQUENCE, RATE_LIMITED, INVALID
    }

    // ---------------------------------------------------------------- world loop (core calls these)

    @Override
    public void onStep(double simTimeS, double dtS) {
        double scale = ports.timeScale();
        int waitS = ports.autopilotWaitS();
        List<Runnable> todo;
        synchronized (lock) {
            step(simTimeS, dtS, scale, waitS);
            recordInlet(simTimeS);
            todo = drain();
        }
        run(todo);
    }

    @Override
    public void onObservation(double simTimeS, ReadingBatch batch) {
        synchronized (lock) {
            if (capture == null || batch == null) {
                return;
            }
            List<Reading> mine = new ArrayList<>();
            for (Reading reading : batch.readings()) {
                if (drone.id().equals(reading.sensorOrVehicleId())) {
                    mine.add(reading);
                }
            }
            if (!mine.isEmpty()) {
                MissionView mission = missions.get(capture.missionId);
                capture.accept(mine, mission != null && inPosition(mission));
            }
        }
    }

    @Override
    public List<VehicleState> vehicles() {
        synchronized (lock) {
            MissionView mission = current();
            boolean flying = lease != null && !lease.ending;
            Vec3 p = drone.position();
            return List.of(new VehicleState(drone.id(), drone.label(), "DRONE",
                    new VehiclePose(p.e(), p.n(), p.u(), drone.yawDeg()), drone.velocity().toVelocity(),
                    drone.inlet().toEnu(), drone.energyWh(), mission == null ? null : mission.state(),
                    flying ? lease.holder : null, flying ? lease.kind : Pilot.NONE));
        }
    }

    /** Recorded inlet positions, copied under the same lock that guards the live drone and run lifecycle. */
    @Override
    public Optional<List<InletSample>> track(String runId, String sensorOrVehicleId, double startS, double endS) {
        if (!Double.isFinite(startS) || !Double.isFinite(endS) || startS > endS) {
            return Optional.empty();
        }
        synchronized (lock) {
            if (inletRunId == null || !inletRunId.equals(runId) || !drone.id().equals(sensorOrVehicleId)
                    || inletSamples.isEmpty() || inletSamples.getFirst().simTimeS() > startS
                    || inletSamples.getLast().simTimeS() < endS) {
                return Optional.empty();
            }
            List<InletSample> result = new ArrayList<>();
            for (InletSample sample : inletSamples) {
                if (sample.simTimeS() <= startS) {
                    result.clear();
                }
                result.add(sample);
                if (sample.simTimeS() >= endS) {
                    break;
                }
            }
            return Optional.of(List.copyOf(result));
        }
    }

    /** Called after movement, under lock; the inlet is exactly the one exposed by vehicles(). */
    private void recordInlet(double t) {
        if (inletRunId == null || !Double.isFinite(t)
                || !inletSamples.isEmpty() && t <= inletSamples.getLast().simTimeS()) {
            return;
        }
        if (inletSamples.size() == MAX_INLET_SAMPLES) {
            inletSamples.removeFirst();
        }
        inletSamples.addLast(new InletSample(t, drone.inlet().toEnu()));
    }

    private void step(double t, double dt, double scale, int waitS) {
        simTimeS = t;
        long now = time.monoMillis();
        deadMan(now);
        MissionView mission = current();
        Vec3 wanted = Vec3.ZERO;
        if (lease != null && !lease.ending) {
            wanted = lease.kind == Pilot.AUTOPILOT ? steerAutopilot(mission, dt) : lease.wanted;
        }
        boolean wasLanded = landed();
        boolean motorsOff = wasLanded && drone.speed() == 0 && wanted.norm() == 0;
        kinematics.step(drone, wanted, dt, !motorsOff);
        if (wasLanded && !landed()) {
            flight++;
        }
        pilotWaits(mission, dt / scale, waitS);
        missionProgress(mission);
        if (capture != null && !capture.done() && capture.advance(t)) {
            requestAttemptUpdate();
        }
        battery(mission);
        timeScale(mission);
    }

    private Vec3 steerAutopilot(MissionView mission, double dt) {
        boolean home = lease.homing || mission == null || MissionView.RETURNING.equals(mission.state());
        String key = (home ? "home:" : "out:") + lease.leaseId + ":" + (mission == null ? "-" : mission.id());
        Optional<Vec3> wanted = home
                ? autopilot.steer(key, drone.position(), () -> List.of(profile.depot()), dt)
                : autopilot.steer(key, drone.position(),
                        () -> Autopilot.routePoints(drone.position(), mission.route(), mission.target()), dt);
        if (wanted.isEmpty()) {
            if (!autopilotNoPathLogged) {
                log.warn("Autopilot found no safe path from {}; holding position", drone.position());
                autopilotNoPathLogged = true;
            }
            return Vec3.ZERO;
        }
        autopilotNoPathLogged = false;
        return wanted.get();
    }

    private void deadMan(long now) {
        if (lease != null && lease.person() && now - lease.lastInputMono > DEAD_MAN_MS
                && (lease.wanted.norm() > 0 || drone.speed() > 0)) {
            lease.wanted = Vec3.ZERO;
            drone.brake();
        }
    }

    private void pilotWaits(MissionView mission, double loopS, int waitS) {
        boolean pilot = lease != null && !lease.ending;
        if (mission == null || !mission.active() || pilot) {
            noPilotS = 0;
            noPilotMissionId = mission == null ? null : mission.id();
            return;
        }
        if (!mission.id().equals(noPilotMissionId)) {
            noPilotMissionId = mission.id();
            noPilotS = 0;
        }
        noPilotS += loopS;
        ObjectNode payload = missionPayload(mission);
        if (autopilotEnabled && mission.id().equals(autopilotMissionId)) {
            if (noPilotS >= RESUME_AFTER_S) {
                payload.put("resume", true);
                submitOnce("resume:" + mission.id() + ":" + pilotEpisode, "start_autopilot", payload, null);
            }
        } else if (noPilotS >= waitS && notFlownYet(mission)) {
            submitOnce("auto:" + mission.id() + ":" + pilotEpisode, "start_autopilot", payload,
                    () -> ports.isOn(AutomationSettings.AUTO_AUTOPILOT));
        }
    }

    /** APPROVED, or LEASED with the drone still on the pad: nobody has flown this mission yet. */
    private boolean notFlownYet(MissionView mission) {
        return MissionView.APPROVED.equals(mission.state())
                || MissionView.LEASED.equals(mission.state()) && landed();
    }

    /** True if the automatic (not resume) autopilot start may still happen for {@code mission}. */
    public boolean awaitingFirstPilot(MissionView mission) {
        synchronized (lock) {
            return notFlownYet(mission);
        }
    }

    private void missionProgress(MissionView mission) {
        if (mission == null) {
            return;
        }
        String to = switch (mission.state()) {
            case MissionView.LEASED -> landed() ? null : MissionView.OUTBOUND;
            case MissionView.OUTBOUND -> inPosition(mission) ? MissionView.AT_TARGET : null;
            case MissionView.RETURNING -> landed() && drone.speed() == 0 ? MissionView.COMPLETE : null;
            default -> null;
        };
        if (to != null) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode()
                    .put("mission_id", mission.id()).put("from_state", mission.state()).put("to_state", to);
            submitOnce("advance:" + mission.id() + ":" + to, "advance_mission", payload, null);
        }
    }

    private void battery(MissionView mission) {
        if (landed()) {
            return;
        }
        boolean homingAlready = lease != null && !lease.ending && lease.kind == Pilot.AUTOPILOT
                && (lease.homing || mission != null && MissionView.RETURNING.equals(mission.state()));
        if (homingAlready) {
            return;
        }
        if (drone.energyWh() - returnEnergyWh() < profile.reserveWh()) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode()
                    .put("vehicle_id", drone.id()).put("reason", "BATTERY_RESERVE");
            submitOnce("battery:" + flight, "return_to_depot", payload, null);
        }
    }

    /** Energy (Wh) the flight home from here would take, with a 20 % margin: climb over what is in the way, cross, descend. */
    public double returnEnergyWh() {
        synchronized (lock) {
            Vec3 p = drone.position();
            Vec3 depot = profile.depot();
            double cruise = Math.max(p.u(), obstacles.highestTopUnder(p, depot) + 1);
            double length = (cruise - p.u()) + p.horizontalDistance(depot) + (cruise - depot.u());
            double seconds = length / profile.maxSpeedMS() + 3 * profile.maxSpeedMS() / profile.maxAccelMS2();
            return energy.wattHours(profile.maxSpeedMS(), seconds) * RETURN_ENERGY_MARGIN;
        }
    }

    private void timeScale(MissionView mission) {
        boolean want = capture != null && !capture.done() && mission != null && inPosition(mission)
                && (lease == null || lease.ending || lease.kind == Pilot.AUTOPILOT) && drone.speed() == 0;
        if (want != timeScaleRequested) {
            timeScaleRequested = want;
            outbox.add(want ? () -> ports.requestTimeScale(TIME_SCALE_REQUESTER, CAPTURE_TIME_SCALE)
                    : () -> ports.releaseTimeScale(TIME_SCALE_REQUESTER));
        }
    }

    private void releaseTimeScale() {
        if (timeScaleRequested) {
            timeScaleRequested = false;
            outbox.add(() -> ports.releaseTimeScale(TIME_SCALE_REQUESTER));
        }
    }

    private void requestAttemptUpdate() {
        if (capture == null) {
            return;
        }
        if (attemptUpdateInFlight) {
            attemptUpdateDirty = true;
            return;
        }
        attemptUpdateInFlight = true;
        String attemptId = capture.attemptId;
        String run = capture.runId;
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("attempt_id", attemptId);
        outbox.add(() -> automation.submit("update_attempt", run, payload)
                .whenComplete((receipt, error) -> attemptUpdateDone(attemptId)));
    }

    private void attemptUpdateDone(String attemptId) {
        List<Runnable> todo;
        synchronized (lock) {
            attemptUpdateInFlight = false;
            if (attemptUpdateDirty && capture != null && capture.attemptId.equals(attemptId)) {
                attemptUpdateDirty = false;
                requestAttemptUpdate();
            }
            todo = drain();
        }
        run(todo);
    }

    // ---------------------------------------------------------------- safety loop and pilot input

    /** Wall-time safety checks: dead man, lease expiry, pairing expiry. Runs even while the run is paused. */
    public void safetyTick() {
        long now = time.monoMillis();
        Instant wall = time.now();
        List<Runnable> todo;
        synchronized (lock) {
            deadMan(now);
            if (lease != null && lease.person() && now >= lease.expiresMono) {
                // Also when already "ending" (frozen by a handler): the record must still end; a duplicate is refused.
                lease.ending = true;
                lease.wanted = Vec3.ZERO;
                drone.brake();
                ObjectNode payload = JsonNodeFactory.instance.objectNode()
                        .put("lease_id", lease.leaseId).put("reason", "EXPIRED");
                submitOnce("expire:" + lease.leaseId, "end_lease", payload, null, lease.runId);
            }
            for (PairingView pairing : pairings.values()) {
                if (("CREATED".equals(pairing.state()) || "REDEEMED".equals(pairing.state()))
                        && pairing.expires() != null && !wall.isBefore(pairing.expires())) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode().put("pairing_id", pairing.id());
                    submitOnce("pairing:" + pairing.id(), "expire_pairing", payload, null);
                }
            }
            todo = drain();
        }
        run(todo);
    }

    /**
     * One pilot input message (AGREEMENT section 7). Only fresh input from the lease holder renews the lease;
     * a stale, rate-limited or invalid message changes nothing and does not renew it.
     */
    public InputResult acceptInput(String actor, String leaseId, long sequence, boolean neutral, Vec3 velocity) {
        long now = time.monoMillis();
        synchronized (lock) {
            if (lease == null || !lease.leaseId.equals(leaseId)) {
                return InputResult.NO_LEASE;
            }
            if (!lease.person() || !lease.holder.equals(actor)) {
                return InputResult.NOT_HOLDER;
            }
            if (lease.ending || now >= lease.expiresMono) {
                return InputResult.LEASE_ENDED;
            }
            if (lease.lastSequence != null && sequence <= lease.lastSequence) {
                return InputResult.STALE_SEQUENCE;
            }
            if (now - lease.lastAcceptedMono < MIN_INPUT_GAP_MS) {
                return InputResult.RATE_LIMITED;
            }
            if (!neutral && (velocity == null || !velocity.isFinite())) {
                return InputResult.INVALID;
            }
            lease.lastSequence = sequence;
            lease.lastAcceptedMono = now;
            lease.lastInputMono = now;
            lease.expiresMono = now + LEASE_MS;
            if (neutral) {
                lease.wanted = Vec3.ZERO;
                drone.brake();
            } else {
                lease.wanted = velocity.limit(profile.maxSpeedMS());
            }
            return InputResult.ACCEPTED;
        }
    }

    /** A pilot socket of {@code actor} (re)connected: the drone stays neutral; old input is never replayed. */
    public void pilotConnected(String actor) {
        neutralFor(actor);
    }

    /** A pilot socket of {@code actor} closed: the drone stops. */
    public void pilotDisconnected(String actor) {
        neutralFor(actor);
    }

    private void neutralFor(String actor) {
        synchronized (lock) {
            if (lease != null && lease.person() && lease.holder.equals(actor)) {
                lease.wanted = Vec3.ZERO;
                drone.brake();
            }
        }
    }

    // ---------------------------------------------------------------- records (after commit) and runs

    /** A run started: the drone is parked at the depot with a full battery and nothing else is going on. */
    public void startRun(String newRunId) {
        List<Runnable> todo;
        synchronized (lock) {
            clear();
            runId = newRunId;
            inletRunId = newRunId;
            drone.placeAt(profile.depot());
            drone.setEnergyWh(profile.usableWh());
            todo = drain();
        }
        run(todo);
    }

    /** A run ended: stop the drone, drop every lease, capture and queue entry of it at once. True if it was the current run. */
    public boolean endRun(String endedRunId) {
        List<Runnable> todo;
        boolean current;
        synchronized (lock) {
            current = runId == null || runId.equals(endedRunId);
            if (current) {
                drone.brake();
                clear();
            }
            todo = drain();
        }
        run(todo);
        return current;
    }

    /** The run paused: stop the drone; lease expiry keeps running on wall time. */
    public void pause() {
        synchronized (lock) {
            drone.brake();
            if (lease != null) {
                lease.wanted = Vec3.ZERO;
            }
        }
    }

    private void clear() {
        releaseTimeScale();
        runId = null;
        inletRunId = null;
        inletSamples.clear();
        missions.clear();
        pairings.clear();
        queue.clear();
        lease = null;
        autopilotEnabled = false;
        autopilotMissionId = null;
        autopilot.reset();
        noPilotS = 0;
        noPilotMissionId = null;
        capture = null;
        attemptUpdateInFlight = false;
        attemptUpdateDirty = false;
        latches.clear();
        flight = 0;
    }

    public void onMissionSaved(StoredRecord record) {
        Optional<MissionView> parsed = MissionView.of(record);
        if (parsed.isEmpty() || !drone.id().equals(parsed.get().vehicleId())) {
            return;
        }
        MissionView mission = parsed.get();
        List<Runnable> todo;
        synchronized (lock) {
            if (runId == null) {
                runId = mission.runId();
            }
            if (!Objects.equals(runId, mission.runId())) {
                return;
            }
            missions.put(mission.id(), mission);
            if (MissionView.TERMINAL.contains(mission.state())) {
                if (mission.id().equals(autopilotMissionId)) {
                    autopilotEnabled = false;
                    autopilotMissionId = null;
                }
                queue.removeIf(entry -> entry.missionId().equals(mission.id()));
            }
            todo = drain();
        }
        run(todo);
    }

    public void onLeaseSaved(StoredRecord record) {
        ObjectNode body = record.body();
        if (!drone.id().equals(body.path("vehicle_id").asText(null))) {
            return;
        }
        String state = body.path("state").asText("");
        long now = time.monoMillis();
        synchronized (lock) {
            if ("ACTIVE".equals(state)) {
                if (lease != null && lease.leaseId.equals(record.id())) {
                    return;
                }
                Pilot kind = pilotOf(body.path("holder_kind").asText(""));
                long expires = kind == Pilot.AUTOPILOT ? Long.MAX_VALUE : now + LEASE_MS;
                lease = new LiveLease(record.id(), record.runId(), body.path("mission_id").asText(null),
                        body.path("holder_session").asText(), kind, "RETURN".equals(body.path("purpose").asText()),
                        expires, null);
                drone.brake();
                pilotEpisode++;
                noPilotS = 0;
                if (kind == Pilot.AUTOPILOT) {
                    autopilotEnabled = true;
                    autopilotMissionId = lease.missionId;
                    autopilot.reset();
                }
            } else if (lease != null && lease.leaseId.equals(record.id())) {
                if (lease.person()) {
                    String holder = lease.holder;
                    String mission = lease.missionId;
                    queue.removeIf(entry -> entry.visitor().equals(holder) && entry.missionId().equals(mission));
                } else if ("STOP_AUTOPILOT".equals(body.path("ended_reason").asText())) {
                    autopilotEnabled = false;
                    autopilotMissionId = null;
                }
                lease = null;
                drone.brake();
                pilotEpisode++;
                noPilotS = 0;
            }
        }
    }

    public void onAttemptSaved(StoredRecord record) {
        ObjectNode body = record.body();
        String state = body.path("state").asText("");
        List<Runnable> todo;
        synchronized (lock) {
            if ("COLLECTING".equals(state)) {
                if (capture == null || !capture.attemptId.equals(record.id())) {
                    MissionView mission = missions.get(body.path("mission_id").asText(""));
                    JsonNode first = body.path("stages").path(0);
                    if (mission != null && first.path("start_s").isNumber()) {
                        List<String> channels = new ArrayList<>();
                        body.path("channels").forEach(c -> channels.add(c.path("channel").asText()));
                        capture = new CaptureRun(record.id(), mission.id(), record.runId(),
                                body.path("method_id").asText(), channels, first.path("start_s").asDouble(), method,
                                profile.procedure());
                        attemptUpdateInFlight = false;
                        attemptUpdateDirty = false;
                    }
                }
            } else if (capture != null && capture.attemptId.equals(record.id())) {
                capture = null;
                releaseTimeScale();
                if ("COMPLETE".equals(state) || "FAILED".equals(state)) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode()
                            .put("vehicle_id", drone.id()).put("reason", "MISSION_DONE");
                    submitOnce("done:" + record.id(), "return_to_depot", payload, null);
                }
            }
            todo = drain();
        }
        run(todo);
    }

    public void onPairingSaved(StoredRecord record) {
        ObjectNode body = record.body();
        if (!drone.id().equals(body.path("vehicle_id").asText(null))) {
            return;
        }
        Instant expires = null;
        try {
            expires = body.hasNonNull("expires_utc") ? Instant.parse(body.get("expires_utc").asText()) : null;
        } catch (DateTimeParseException ignored) {
            // unknown expiry stays null
        }
        PairingView pairing = new PairingView(record.id(), body.path("mission_id").asText(null),
                body.path("state").asText(""), expires, body.path("redeemed_by").asText(null));
        synchronized (lock) {
            if (runId != null && !runId.equals(record.runId())) {
                return;
            }
            pairings.put(record.id(), pairing);
            if ("CONFIRMED".equals(pairing.state()) && pairing.redeemedBy() != null
                    && queue.stream().noneMatch(e -> e.pairingId().equals(pairing.id()))) {
                queue.add(new QueueEntry(pairing.missionId(), pairing.redeemedBy(), pairing.id()));
            }
        }
    }

    // ---------------------------------------------------------------- for the command handlers

    public String droneId() {
        return drone.id();
    }

    /** The drone itself, for tests and scenario hooks. Hold no reference across steps. */
    public Drone drone() {
        return drone;
    }

    public WorldProfile profile() {
        return profile;
    }

    public CaptureMethod method() {
        return method;
    }

    public Optional<String> runId() {
        synchronized (lock) {
            return Optional.ofNullable(runId);
        }
    }

    public double simTimeS() {
        synchronized (lock) {
            return simTimeS;
        }
    }

    public Optional<MissionView> currentMission() {
        synchronized (lock) {
            return Optional.ofNullable(current());
        }
    }

    public Optional<LeaseInfo> lease() {
        synchronized (lock) {
            return lease == null ? Optional.empty()
                    : Optional.of(new LeaseInfo(lease.leaseId, lease.runId, lease.missionId, lease.holder, lease.kind,
                    lease.homing, lease.lastSequence, lease.ending));
        }
    }

    /** True if the drone is inside the target zone of {@code mission}: within 5 m across and inside the height band. */
    public boolean inPosition(MissionView mission) {
        synchronized (lock) {
            Vec3 p = drone.position();
            return p.horizontalDistance(mission.target()) <= TARGET_HORIZONTAL_M
                    && Math.abs(p.u() - mission.target().u()) <= HEIGHT_BAND_M;
        }
    }

    /** True if the drone is on the depot pad. */
    public boolean landed() {
        synchronized (lock) {
            Vec3 p = drone.position();
            return p.horizontalDistance(profile.depot()) <= LANDED_HORIZONTAL_M
                    && p.u() <= profile.depot().u() + LANDED_HEIGHT_M;
        }
    }

    public double noPilotSeconds() {
        synchronized (lock) {
            return noPilotS;
        }
    }

    public boolean autopilotEnabledFor(String missionId) {
        synchronized (lock) {
            return autopilotEnabled && missionId.equals(autopilotMissionId);
        }
    }

    public boolean captureRunning() {
        synchronized (lock) {
            return capture != null;
        }
    }

    public Optional<AttemptSnapshot> captureSnapshot(String attemptId) {
        synchronized (lock) {
            return capture != null && capture.attemptId.equals(attemptId)
                    ? Optional.of(capture.snapshot()) : Optional.empty();
        }
    }

    /** The running capture, if any. */
    public Optional<AttemptSnapshot> captureSnapshot() {
        synchronized (lock) {
            return capture == null ? Optional.empty() : Optional.of(capture.snapshot());
        }
    }

    /** The visitors waiting for {@code missionId}, first in line first. */
    public List<QueueEntry> queue(String missionId) {
        synchronized (lock) {
            return queue.stream().filter(e -> e.missionId().equals(missionId)).toList();
        }
    }

    /** The whole queue, every mission. */
    public List<QueueEntry> queue() {
        synchronized (lock) {
            return List.copyOf(queue);
        }
    }

    /** True if a safe path exists from the drone along {@code mission}'s route to its target (or home when returning). */
    public boolean routeFlyable(MissionView mission) {
        synchronized (lock) {
            Vec3 p = drone.position();
            List<Vec3> points = MissionView.RETURNING.equals(mission.state())
                    ? List.of(profile.depot())
                    : Autopilot.routePoints(p, mission.route(), mission.target());
            return autopilot.plan(p, points).isPresent();
        }
    }

    /**
     * "Vehicle stops first" (take over, stop autopilot, return to depot, hand-over): the drone stops now and
     * the current lease takes no more input. The handler's command then ends the lease in its record.
     */
    public void freeze() {
        synchronized (lock) {
            drone.brake();
            if (lease != null) {
                lease.wanted = Vec3.ZERO;
                lease.ending = true;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private MissionView current() {
        for (MissionView mission : missions.values()) {
            if (mission.active()) {
                return mission;
            }
        }
        return null;
    }

    private ObjectNode missionPayload(MissionView mission) {
        return JsonNodeFactory.instance.objectNode().put("mission_id", mission.id()).put("vehicle_id", drone.id());
    }

    private void submitOnce(String key, String type, ObjectNode payload, BooleanSupplier gate) {
        submitOnce(key, type, payload, gate, runId);
    }

    private void submitOnce(String key, String type, ObjectNode payload, BooleanSupplier gate, String run) {
        if (!latches.add(key)) {
            return;
        }
        outbox.add(() -> {
            if (gate != null && !gate.getAsBoolean()) {
                synchronized (lock) {
                    latches.remove(key);
                }
                return;
            }
            automation.submit(type, run, payload);
        });
    }

    private List<Runnable> drain() {
        if (outbox.isEmpty()) {
            return List.of();
        }
        List<Runnable> copy = List.copyOf(outbox);
        outbox.clear();
        return copy;
    }

    private static void run(List<Runnable> todo) {
        for (Runnable action : todo) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("A field world action failed", e);
            }
        }
    }

    private static Pilot pilotOf(String wire) {
        try {
            return Pilot.valueOf(wire);
        } catch (IllegalArgumentException e) {
            return Pilot.VISITOR;
        }
    }
}
