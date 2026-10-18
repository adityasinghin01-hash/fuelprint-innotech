package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.api.ReadingBatch;
import fuelprint.api.RunEnded;
import fuelprint.api.RunStarted;
import fuelprint.api.VehicleState;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.geo.Vec3;
import fuelprint.field.world.AttemptSnapshot;
import fuelprint.field.world.FieldWorld;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for field tests with the Spring context. There is no core world loop yet, so the test is the loop:
 * {@link #tick} calls {@code onStep}, plays the science observation feed at the drone inlet, calls
 * {@code onObservation} and the safety tick, and moves the manual wall clock 50 ms (20 Hz).
 * Each test starts a fresh run.
 */
@SpringBootTest(properties = "fp.field.safety-loop=false")
@AutoConfigureMockMvc
@Import(FieldTestConfig.class)
public abstract class FieldSpringTest {

    protected static final Path DATA_DIR = CoreSpringTest.TempFolders.create("fp-field-test-");
    protected static final double DT = 0.05;
    protected static final ObjectMapper JSON = new ObjectMapper();

    protected static final Session OPERATOR = Session.human("s-op", "field_operator@laptop-2", Set.of(Role.FIELD_OPERATOR));
    protected static final Session SUPERVISOR = Session.human("s-sup", "supervisor@laptop-1", Set.of(Role.SUPERVISOR));
    protected static final Session QC = Session.human("s-qc", "qc_reviewer@laptop-2", Set.of(Role.QC_REVIEWER));
    protected static final Session ANALYST = Session.human("s-an", "analyst@laptop-1", Set.of(Role.ANALYST));

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("fp.data-dir", DATA_DIR::toString);
    }

    @Autowired protected FieldWorld world;
    @Autowired protected CommandBus bus;
    @Autowired protected RecordStore store;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ApplicationEventPublisher events;
    @Autowired protected FieldTestConfig.ManualFieldTime time;
    @Autowired protected FieldTestConfig.FakeSessions sessions;
    @Autowired protected FieldTestConfig.FakeClock clock;
    @Autowired protected FieldTestConfig.FakeSettings settings;
    @Autowired protected FieldTestConfig.FakeCaptureRules rules;

    protected String runId;
    protected double simTimeS;
    /** The science observation feed at the drone's inlet: (sim time, inlet) to readings. */
    protected BiFunction<Double, Vec3, List<Reading>> feed;
    /** Every vehicle state seen by {@link #tick}, newest last. */
    protected final List<VehicleState> track = new ArrayList<>();
    /** The step length (simulation seconds) of each entry of {@link #track}. */
    protected final List<Double> trackDt = new ArrayList<>();

    @BeforeEach
    void freshRun() {
        settle();
        settings.reset();
        clock.reset();
        rules.reset();
        if (runId != null) {
            events.publishEvent(new RunEnded(runId, RunEnded.Reason.RESET));
            settle();
        }
        runId = UUID.randomUUID().toString();
        simTimeS = 0;
        feed = this::cleanAir;
        track.clear();
        trackDt.clear();
        events.publishEvent(new RunStarted(runId, "SC1-NIGHT-PLUME", "BLIND"));
    }

    // ------------------------------------------------------------ loop

    /** One world-loop step (dt 0.05 s times the time scale), its observation, the safety tick, 50 ms of wall time. */
    protected void tick() {
        double dt = DT * clock.timeScale();
        simTimeS += dt;
        world.onStep(simTimeS, dt);
        VehicleState drone = world.vehicles().get(0);
        track.add(drone);
        trackDt.add(dt);
        List<Reading> readings = feed.apply(simTimeS, Vec3.of(drone.inlet()));
        world.onObservation(simTimeS, new ReadingBatch(simTimeS, readings));
        time.advanceMillis(50);
        world.safetyTick();
    }

    protected void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
            if (i % 10 == 9) {
                bus.awaitIdle();
            }
        }
        settle();
    }

    /** Ticks until {@code done} (checking after the bus is idle) or fails after {@code max} ticks. */
    protected int tickUntil(BooleanSupplier done, int max, String what) {
        for (int i = 0; i < max; i++) {
            tick();
            bus.awaitIdle();
            if (done.getAsBoolean()) {
                settle();
                return i + 1;
            }
        }
        settle();
        assertThat(done.getAsBoolean()).as("%s within %d ticks", what, max).isTrue();
        return max;
    }

    /** Waits for every queued command and the automatic commands its listeners queued after it. */
    protected void settle() {
        for (int i = 0; i < 4; i++) {
            bus.awaitIdle();
        }
    }

    // ------------------------------------------------------------ observation feeds

    protected Reading reading(double t, String channel, Double value, String unit, String... quality) {
        return new Reading(world.droneId(), channel, value, value, unit, t,
                quality.length == 0 ? List.of("OK") : List.of(quality), Origin.SYNTHETIC_TEACHING);
    }

    /** Ambient air with both branch flows at their nominal values. */
    protected List<Reading> cleanAir(double t, Vec3 inlet) {
        return List.of(reading(t, "SO2_ppb", 60.0, "ppb"), reading(t, "SO2_flow_L_min", 0.5, "L_min"),
                reading(t, "CO2_ppm", 430.0, "ppm"), reading(t, "CO2_flow_L_min", 0.3, "L_min"));
    }

    /**
     * SC2-style blocked SO2 inlet: from collection second 10 the SO2 branch flow is 0 and flagged, while the
     * SO2 number still looks normal. CO2 is untouched.
     */
    protected List<Reading> blockedSo2AtCollectSecond10(double t, Vec3 inlet) {
        Double collectStart = world.captureSnapshot().flatMap(s -> s.stages().stream()
                .filter(st -> st.stage().equals("COLLECT")).findFirst()).map(st -> st.startS()).orElse(null);
        if (collectStart == null || t < collectStart + 10) {
            return cleanAir(t, inlet);
        }
        return List.of(reading(t, "SO2_ppb", 61.0, "ppb", "FLOW_FAULT"),
                reading(t, "SO2_flow_L_min", 0.0, "L_min", "FLOW_FAULT"),
                reading(t, "CO2_ppm", 430.0, "ppm"), reading(t, "CO2_flow_L_min", 0.3, "L_min"));
    }

    // ------------------------------------------------------------ commands and records

    protected Receipt send(Session session, String type, ObjectNode payload) {
        return send(session, type, payload, null);
    }

    protected Receipt send(Session session, String type, ObjectNode payload, Long expectedRevision) {
        Receipt receipt = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), type, runId,
                expectedRevision, payload), session);
        settle();
        return receipt;
    }

    protected static ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** An APPROVED SC1 mission for the drone: route depot to (0, 100, 35). Returns its id. */
    protected String approvedMission() {
        return approvedMission(new Vec3(0, 100, 35));
    }

    protected String approvedMission(Vec3 target) {
        String id = UUID.randomUUID().toString();
        ObjectNode fields = payload();
        fields.put("proposal_id", UUID.randomUUID().toString());
        fields.putNull("survey_id");
        fields.put("action_id", "A-AER-0-100");
        fields.put("vehicle_id", world.droneId());
        fields.set("target", point(target));
        ArrayNode route = fields.putArray("route");
        route.add(point(new Vec3(0, 0, 2)));
        route.add(point(target));
        fields.put("method_id", "TEACH-NODE-ONLINE-2");
        fields.putArray("channel_ids").add("SO2_ppb").add("CO2_ppm");
        fields.put("dependency_hash", "91047a1e4622a81cdf22efb48d6ed4d047b6836c393d3a9a1f058199ac90290a");
        fields.put("state", "APPROVED");
        fields.put("expires_s", 10_000.0);
        fields.put("pilot", "NONE");
        Receipt receipt = send(ANALYST, FieldTestConfig.PutMission.TYPE, payload().put("mission_id", id).set("fields", fields));
        assertThat(receipt.applied()).as(receipt.reasonText()).isTrue();
        assertThat(world.currentMission()).isPresent();
        return id;
    }

    protected static ObjectNode point(Vec3 p) {
        return JsonNodeFactory.instance.objectNode().put("east", p.e()).put("north", p.n()).put("up", p.u());
    }

    protected StoredRecord record(String type, String id) {
        return store.get(type, id).orElseThrow(() -> new AssertionError("no " + type + " " + id));
    }

    protected JsonNode mission(String id) {
        return record("mission", id).body();
    }

    protected String missionState(String id) {
        return mission(id).path("state").asText();
    }

    protected List<StoredRecord> records(String type) {
        return store.list(type, runId);
    }

    protected StoredRecord onlyAttempt() {
        List<StoredRecord> attempts = records("attempt");
        assertThat(attempts).hasSize(1);
        return attempts.get(0);
    }

    protected ObjectNode missionVehicle(String missionId) {
        return payload().put("mission_id", missionId).put("vehicle_id", world.droneId());
    }

    /** Automatic commands of this run: command type and stored payload, with the receipt status. */
    protected List<Map<String, Object>> automatic(String type) {
        return jdbc.queryForList("SELECT command_type, payload, status, reason_code FROM commands "
                + "WHERE automatic = 1 AND run_id = ? AND command_type = ? ORDER BY row_id", runId, type);
    }

    protected List<Map<String, Object>> appliedAutomatic(String type) {
        return automatic(type).stream().filter(r -> "APPLIED".equals(r.get("status"))).toList();
    }

    /** A visitor that redeemed a code for {@code missionId} and was confirmed by the operator (now in the queue). */
    protected Session pairedVisitor(String missionId) {
        Receipt created = send(OPERATOR, "create_pairing", missionVehicle(missionId));
        assertThat(created.applied()).as(created.reasonText()).isTrue();
        StoredRecord pairing = record("pairing", created.recordRefs().get(0).id());
        Session visitor = sessions.createVisitor("visitor-" + UUID.randomUUID().toString().substring(0, 4));
        Receipt redeemed = send(visitor, "redeem_pairing", payload().put("pairing_id", pairing.id())
                .put("code", pairing.body().path("code").asText()));
        assertThat(redeemed.applied()).as(redeemed.reasonText()).isTrue();
        Receipt confirmed = send(OPERATOR, "confirm_pairing", payload().put("pairing_id", pairing.id()));
        assertThat(confirmed.applied()).as(confirmed.reasonText()).isTrue();
        return visitor;
    }

    /** The visitor or operator acquires the lease; returns the lease id. */
    protected String acquire(Session session, String missionId) {
        Receipt receipt = send(session, "acquire_lease", missionVehicle(missionId));
        assertThat(receipt.applied()).as(receipt.reasonText()).isTrue();
        return world.lease().orElseThrow().leaseId();
    }

    /** Sends one input message as {@code actor}, like the socket would. */
    protected FieldWorld.InputResult input(Session session, String leaseId, long sequence, Vec3 velocity) {
        return world.acceptInput(session.actorLabel(), leaseId, sequence, velocity == null, velocity);
    }

    protected double speed() {
        return world.drone().speed();
    }

    protected AttemptSnapshot snapshot() {
        return world.captureSnapshot().orElseThrow();
    }
}
