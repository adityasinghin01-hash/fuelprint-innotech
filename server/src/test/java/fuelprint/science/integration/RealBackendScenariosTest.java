package fuelprint.science.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.*;
import fuelprint.core.settings.AutomationSettingsService;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import fuelprint.core.world.WorldLoop;
import fuelprint.field.world.FieldWorld;
import fuelprint.science.rank.ScienceRanker;
import fuelprint.science.sensor.SensorField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * Isolated real physics/Field lifecycle, with explicitly test-only mission creation/departure authority and an isolated Ops automation adapter.
 * This preserves the historical capture regression; it does not establish genuine Ops approval or the
 * complete Operations/Cases scenarios. New full-path scenarios must use the actual Ops authority.
 */
@SpringBootTest(properties = "fp.field.safety-loop=false")
@ActiveProfiles("real")
@Import(RealBackendScenariosTest.MissionOrchestration.class)
class RealBackendScenariosTest extends CoreSpringTest {
    // This historical test has no genuine proposal dependencies. Isolate the newer Ops expiry/proposal
    // adapter too; otherwise it correctly expires the synthetic APPROVED fixture before Field auto-start.
    // New complete-workflow scenarios retain the real Ops adapter and authority.
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name="missionAutomation")
    WorldStepListener isolatedOpsAutomation;
    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired CookieSessionService sessions;
    @Autowired RecordStore store;
    @Autowired ObjectMapper mapper;
    @Autowired WorldLoop loop;
    @Autowired FieldWorld field;
    @Autowired ObservationProvider observations;
    @Autowired CaptureRules rules;
    @Autowired AutomationSettings settings;
    @Autowired ClockControl clock;
    @Autowired WorldClock scientificClock;
    @Autowired ScienceRanker ranker;
    @Autowired Planner planner;

    static Stream<Arguments> scenarios() {
        return Stream.of("SC1-NIGHT-PLUME", "SC2-BLOCKED-INLET", "SC3-ABOVE-THE-BOXES")
                .flatMap(preset -> Stream.of(Arguments.of(preset, false), Arguments.of(preset, true)));
    }

    @ParameterizedTest(name = "{0}, automation={1}")
    @MethodSource("scenarios")
    void realStagesRawRecordsHumanReviewAndMeasuredOutcomes(String preset, boolean automatic) throws Exception {
        assertThat(observations).isInstanceOf(SensorField.class);
        assertThat(rules.getClass().getName()).isEqualTo("fuelprint.science.sensor.TeachCaptureRules");
        assertThat(settings).isInstanceOf(AutomationSettingsService.class);
        assertThat(clock.getClass().getName()).doesNotContain("Fake");
        Session presenter = session("science-presenter", "presenter");
        Session analyst = session("science-analyst", "analyst");
        Session operator = session("science-field", "field_operator");
        Session qc = session("science-qc", "qc_reviewer");
        for (String setting : AutomationSettingsService.SWITCHES) {
            assertApplied(send(presenter, "change_automation", null, json().put("setting", setting).put("value", automatic), null));
        }
        Receipt started = send(presenter, "reset_run", null, json().put("preset_id", preset).put("mode", "BLIND"), null);
        assertApplied(started);
        String run = started.recordRefs().stream().filter(r -> r.type().equals("run") && r.revision() == 1).findFirst().orElseThrow().id();
        try {
            stepUntil(() -> loop.simTimeS() >= 300, 6200, "fixed observation period");
            List<Window> fixed = windows(run).stream().filter(w -> !w.sensorOrVehicleId().equals(field.droneId())).toList();
            long initialBegan = System.nanoTime();
            Analysis initial = ranker.analyze(new AnalysisInput(run, "real-initial", loop.simTimeS(), fixed, scientificClock.wind()));
            double initialSeconds = (System.nanoTime()-initialBegan)/1e9;
            long planBegan = System.nanoTime();
            Proposal proposal = planner.plan(initial,new PlanContext(run,"real-initial",loop.simTimeS(),
                    scientificClock.wind(),field.vehicles()));
            double planSeconds = (System.nanoTime()-planBegan)/1e9;
            if (preset.startsWith("SC3")) assertThat(store.list("trigger", run)).isEmpty();
            else assertThat(store.list("trigger", run)).isNotEmpty();

            String mission = UUID.randomUUID().toString();
            ObjectNode fields = missionFields(mission, preset);
            assertApplied(send(analyst, "test_science_mission", run, json().put("mission_id", mission).set("fields", fields), null));
            ObjectNode missionVehicle = json().put("mission_id", mission).put("vehicle_id", field.droneId());
            if (!automatic) assertApplied(send(operator, "start_autopilot", run, missionVehicle, null));
            stepUntil(() -> "AT_TARGET".equals(missionState(mission)) || "COLLECTING".equals(missionState(mission)), 6500, "arrival at target");
            if (!automatic) {
                assertThat(store.list("attempt", run)).isEmpty();
                ObjectNode payload = json().put("mission_id", mission).put("method_id", "TEACH-NODE-ONLINE-2");
                payload.putArray("channel_ids").add("SO2_ppb").add("CO2_ppm");
                assertApplied(send(operator, "start_capture", run, payload, null));
            }
            stepUntil(() -> !store.list("attempt", run).isEmpty()
                    && !"COLLECTING".equals(store.list("attempt", run).get(0).body().path("state").asText()),
                    16000, "completed real capture");
            StoredRecord attempt = store.list("attempt", run).get(0);
            assertThat(field.captureSnapshot(attempt.id())).isEmpty();
            assertThat(vehicleWindows(run)).isEmpty();
            ObjectNode review = review(attempt);
            Receipt refused = automation.submit("review_capture", review, run, attempt.revision()).join();
            assertThat(refused.reasonCode()).isEqualTo("NOT_ALLOWED");
            assertThat(send(analyst, "review_capture", run, review, attempt.revision()).reasonCode()).isEqualTo("NOT_ALLOWED");
            assertThat(vehicleWindows(run)).isEmpty();
            Receipt reviewed = send(qc, "review_capture", run, review, attempt.revision());
            assertApplied(reviewed);
            List<Window> vehicle = vehicleWindows(run);
            List<Window> collection = vehicle.stream().filter(w -> w.backgroundWindowId() != null).toList();
            assertThat(collection).isNotEmpty();
            if (preset.startsWith("SC2")) {
                assertThat(channel(attempt, "SO2_ppb").path("status").asText()).isEqualTo("REJECTED");
                assertThat(channel(attempt, "SO2_ppb").path("reasons").toString()).contains("FLOW_FAULT");
                assertThat(collection).extracting(Window::channel).containsExactly("CO2_ppm");
            } else {
                assertThat(collection).extracting(Window::channel).containsExactlyInAnyOrder("SO2_ppb", "CO2_ppm");
            }
            assertThat(store.list("screening_record", run)).hasSize(2);
            List<Window> combined = new ArrayList<>(fixed);
            combined.addAll(vehicle);
            long afterBegan = System.nanoTime();
            Analysis after = ranker.analyze(new AnalysisInput(run, "real-after-qc", loop.simTimeS(), combined, scientificClock.wind()));
            assertThat(after.inputWindowIds()).containsAll(collection.stream().map(Window::id).toList());
            double afterSeconds = (System.nanoTime()-afterBegan)/1e9;
            writeResult(preset, automatic, run, attempt, fixed, vehicle, initial, after, proposal,
                    initialSeconds,afterSeconds,planSeconds);
        } finally {
            loop.stop(run);
            drain();
        }
    }

    private Session session(String station, String role) {
        return sessions.resolveToken(sessions.createStation(station, Set.of(role)).sessionId()).orElseThrow();
    }

    private Receipt send(Session session, String type, String run, ObjectNode payload, Long revision) {
        Receipt result = bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), type, run, revision, payload), session);
        drain();
        return result;
    }

    private void drain() { for (int i = 0; i < 4; i++) bus.awaitIdle(); }

    private void stepUntil(BooleanSupplier done, int maxTicks, String label) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) { loop.wallStep(); drain(); }
        assertThat(done.getAsBoolean()).as(label + " at sim " + loop.simTimeS()).isTrue();
    }

    private void assertApplied(Receipt receipt) { assertThat(receipt.applied()).as(receipt.reasonCode() + ": " + receipt.reasonText()).isTrue(); }
    private static ObjectNode json() { return JsonNodeFactory.instance.objectNode(); }
    private String missionState(String mission) { return store.get("mission", mission).orElseThrow().body().path("state").asText(); }

    private ObjectNode missionFields(String mission, String preset) {
        ObjectNode fields = json().put("proposal_id", UUID.randomUUID().toString()).putNull("survey_id")
                .put("action_id", "REAL-SCENARIO-TEST").put("vehicle_id", field.droneId())
                .put("method_id", "TEACH-NODE-ONLINE-2").put("dependency_hash", "real-backend-test")
                .put("state", "APPROVED").put("expires_s", 10000).put("pilot", "NONE");
        boolean high = preset.startsWith("SC3");
        ObjectNode target = json().put("east", high ? 280 : 0).put("north", high ? 140 : 100).put("up", high ? 45 : 35);
        fields.set("target", target);
        fields.putArray("route").add(target);
        fields.putArray("channel_ids").add("SO2_ppb").add("CO2_ppm");
        return fields;
    }

    private com.fasterxml.jackson.databind.JsonNode channel(StoredRecord attempt, String channel) {
        for (var entry : attempt.body().path("channels")) if (channel.equals(entry.path("channel").asText())) return entry;
        throw new AssertionError("Missing attempt channel " + channel);
    }

    private ObjectNode review(StoredRecord attempt) {
        ObjectNode payload = json().put("attempt_id", attempt.id());
        ArrayNode decisions = payload.putArray("channel_decisions");
        for (var entry : attempt.body().path("channels")) decisions.addObject().put("channel", entry.path("channel").asText())
                .put("decision", entry.path("status").asText("REJECTED")).putArray("reasons").add("REAL_BACKEND_TEST_HUMAN_QC");
        return payload;
    }

    private List<Window> windows(String run) {
        return store.list("window", run).stream().map(record -> {
            ObjectNode fields = record.body();
            for (String common : RecordChange.COMMON_FIELDS) if (!"id".equals(common)) fields.remove(common);
            try { return mapper.treeToValue(fields, Window.class); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }).toList();
    }

    private List<Window> vehicleWindows(String run) {
        return windows(run).stream().filter(w -> w.sensorOrVehicleId().equals(field.droneId())).toList();
    }

    private void writeResult(String preset, boolean automatic, String run, StoredRecord attempt, List<Window> fixed,
                             List<Window> vehicle, Analysis initial, Analysis after, Proposal proposal,
                             double initialSeconds, double afterSeconds, double planSeconds) throws Exception {
        ObjectNode report = json().put("preset", preset).put("automation_on", automatic).put("run_id", run)
                .put("sim_time_s", loop.simTimeS()).put("cutoff", ranker.engine().cutoff().value())
                .put("orchestration_limit", "Test-only analyst command creates APPROVED mission and a labelled test adapter permits its initial departure; genuine Ops proposal/approval and Cases workflows are not exercised. Same sim300 fixed snapshot retained after QC.");
        report.put("initial_analysis_seconds",initialSeconds).put("after_qc_analysis_seconds",afterSeconds)
                .put("planner_seconds",planSeconds);
        report.set("initial_proposal",mapper.valueToTree(proposal));
        report.set("attempt", attempt.body());
        report.set("fixed_windows", mapper.valueToTree(fixed));
        report.set("vehicle_windows", mapper.valueToTree(vehicle));
        report.set("initial", analysisReport(initial));
        report.set("after_qc", analysisReport(after));
        Path path = Path.of("target", "real-backend-scenarios", preset + "-auto-" + automatic + ".json");
        Files.createDirectories(path.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        System.out.println("REAL_BACKEND " + preset + " auto=" + automatic + " initial=" + initial.outcome() + " after=" + after.outcome() + " report=" + path);
    }

    private ObjectNode analysisReport(Analysis analysis) {
        ObjectNode report = mapper.valueToTree(analysis);
        report.set("raw_cutoff_compatible", mapper.valueToTree(analysis.candidates().stream()
                .filter(c -> !c.isUnknown() && c.fit() != null && c.fit() <= ranker.engine().cutoff().value()).map(Candidate::explanationId).toList()));
        report.set("final_survivors", mapper.valueToTree(analysis.candidates().stream().filter(c -> c.status() == CandidateStatus.SURVIVES)
                .map(Candidate::explanationId).toList()));
        return report;
    }

    @TestConfiguration
    static class MissionOrchestration {
        @Bean @Primary MissionAuthority isolatedScienceAuthority(StoreView store) {
            return (run, mission) -> store.get("mission", mission)
                    .filter(r -> run.equals(r.runId()) && r.body().path("isolated_science_fixture").asBoolean(false)
                            && "APPROVED".equals(r.body().path("state").asText()))
                    .map(r -> MissionAuthority.Decision.permit())
                    .orElseGet(() -> MissionAuthority.Decision.refuse("NEEDS_NEW_APPROVAL", "Only the labelled isolated-science fixture is permitted by this test adapter."));
        }
        @Bean CommandHandler scienceMissionCommand() {
            return new CommandHandler() {
                public String commandType() { return "test_science_mission"; }
                public Set<Role> allowedRoles() { return Set.of(Role.ANALYST); }
                public HandlerOutcome handle(Command command, Session session, StoreView store) {
                    ObjectNode fields = (ObjectNode) command.payload().path("fields");
                    fields.put("isolated_science_fixture", true);
                    return HandlerResult.builder().save(RecordChange.create("mission", command.payload().path("mission_id").asText(),
                            command.runId(), fields), "MISSION_TEST_ORCHESTRATION", null).build();
                }
            };
        }
    }
}
