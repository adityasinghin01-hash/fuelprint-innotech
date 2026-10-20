package fuelprint.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.*;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Public service fixtures exercise orchestration branches, not calibrated scientific performance. */
@SpringBootTest(properties = {"fp.loop.auto=false", "fp.field.safety-loop=false"})
@ActiveProfiles("real")
@AutoConfigureMockMvc
@Import(OperationsWorkflowTest.Config.class)
class OperationsWorkflowTest {
    static final Path DATA = CoreSpringTest.TempFolders.create("fp-ops-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r) { r.add("fp.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordStore store;
    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired CommandLog commands;
    @Autowired CookieSessionService sessions;
    @Autowired ApplicationEventPublisher events;
    @Autowired Fixtures fixture;
    @Autowired TestMode mode;
    String run;
    Cookie analyst;
    Cookie presenter;

    static String id() { return UUID.randomUUID().toString(); }
    ObjectNode obj() { return json.createObjectNode(); }
    @BeforeEach void fresh() throws Exception {
        settle(); mode.active = false; fixture.reset();
        analyst = cookie("analyst"); presenter = cookie("presenter");
        var receipt = send("reset_run", obj().put("preset_id", "SC1-NIGHT-PLUME").put("mode", "TEACHING"), null, presenter);
        assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");
        run = receipt.path("record_refs").findValuesAsText("id").stream()
                .filter(x -> store.get("run", x).map(s -> "RUNNING".equals(s.body().path("state").asText())).orElse(false))
                .findFirst().orElseThrow();
    }
    Cookie cookie(String role) { return new Cookie(CookieSessionService.COOKIE, sessions.createStation(id(), Set.of(role)).sessionId()); }
    void settle() { for (int i=0;i<5;i++) bus.awaitIdle(); }
    JsonNode send(String type, ObjectNode payload, Long rev, Cookie cookie) throws Exception {
        return send(new Command("fp/1", id(), type, run, rev, payload), cookie);
    }
    JsonNode send(Command c, Cookie cookie) throws Exception {
        var response = mvc.perform(post("/api/commands").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(c))).andExpect(status().isOk()).andReturn().getResponse();
        settle();
        JsonNode receipt = json.readTree(response.getContentAsString());
        System.out.println("OPS_RECEIPT " + json.writeValueAsString(receipt));
        return receipt;
    }
    String seed(String type, ObjectNode fields) { return seed(type, fields, run); }
    String seed(String type, ObjectNode fields, String runId) {
        String recordId = id();
        ObjectNode p = obj().put("type", type).put("id", recordId); p.set("fields", fields);
        var r = bus.send(new Command("fp/1", id(), "test_ops_seed", runId, null, p),
                Session.human("test", "presenter@test", Set.of(Role.PRESENTER)));
        assertThat(r.applied()).isTrue(); settle(); return recordId;
    }
    String trigger(String sensor) { return seed("trigger", obj().put("sensor", sensor).put("channel", "SO2_ppb").put("rule", "TEACH-DETECT-1")); }
    String survey() throws Exception {
        JsonNode r = send("schedule_survey", obj().put("action_id", "SURVEY-DRONE-0-100-35").put("reason", "Independent teaching survey"), null, analyst);
        assertThat(r.path("status").asText()).isEqualTo("APPLIED");
        return store.list("survey", run).get(store.list("survey", run).size()-1).id();
    }
    StoredRecord investigation() { assertThat(store.list("investigation",run)).as("production investigation").hasSize(1); return store.list("investigation", run).get(0); }
    JsonNode analyze() throws Exception { return send("run_analysis", obj().put("investigation_id", investigation().id()), investigation().revision(), analyst); }

    @Test void triggerOpensOneInvestigationAttachesLaterTriggerAndIgnoresRepeatedDelivery() {
        mode.active = true;
        String t1 = trigger("N2");
        StoredRecord first = investigation();
        assertThat(first.body().path("trigger_id").asText()).isEqualTo(t1);
        assertThat(first.body().path("warned_area_ids")).isEqualTo(json.valueToTree(List.of("AREA-E")));
        assertThat(store.list("analysis", run)).hasSize(1);
        String t2 = trigger("N3");
        assertThat(store.list("investigation", run)).hasSize(1);
        assertThat(investigation().body().path("trigger_ids")).isEqualTo(json.valueToTree(List.of(t1,t2)));
        int analyses = store.list("analysis", run).size();
        events.publishEvent(new RecordSaved("trigger", t1, 1, run, "automation", true)); settle();
        events.publishEvent(new RecordSaved("investigation", first.id(), 1, run, "automation", true)); settle();
        assertThat(store.list("analysis", run)).hasSize(analyses);
        assertThat(investigation().body().path("trigger_ids")).hasSize(2);
    }
    record WorkflowSnapshot(List<StoredRecord> investigations, List<StoredRecord> analyses,
                            List<StoredRecord> proposals, List<StoredRecord> notifications) {}

    WorkflowSnapshot workflowSnapshot() {
        return new WorkflowSnapshot(store.list("investigation", run), store.list("analysis", run),
                store.list("proposal", run), store.list("notification", null));
    }

    long automaticOpenCount() {
        return commands.automationEntries(run, 500).stream()
                .filter(entry -> "open_investigation".equals(entry.commandType())).count();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED_ONLY_ARRAY", "CLOSED_ONLY_PRIMARY", "ACTIVE_B_ARRAY", "ACTIVE_B_PRIMARY"})
    void consumedTriggerRemainsConsumedAfterClosureRegardlessOfTheActiveInvestigation(String scenario) throws Exception {
        mode.active = true;
        String consumed = trigger("N2");
        StoredRecord first = investigation();
        ObjectNode closed = first.body();
        closed.remove(List.of("id", "run_id", "revision", "created_utc", "created_by"));
        closed.put("state", "CLOSED");
        if (scenario.endsWith("PRIMARY")) closed.remove("trigger_ids");
        ObjectNode close = obj().put("type", "investigation").put("id", first.id())
                .put("based_on_revision", first.revision());
        close.set("fields", closed);
        assertThat(send("test_ops_seed", close, null, presenter).path("status").asText()).isEqualTo("APPLIED");
        assertThat(store.get("investigation", first.id()).orElseThrow().body().path("state").asText()).isEqualTo("CLOSED");

        boolean hasActiveB = scenario.startsWith("ACTIVE_B");
        if (hasActiveB) survey();
        WorkflowSnapshot before = workflowSnapshot();
        long opensBefore = automaticOpenCount();

        // Listener redelivery must neither enqueue intake nor alter any record/side effect.
        events.publishEvent(new RecordSaved("trigger", consumed, 1, run, "automation", true));
        settle();
        assertThat(workflowSnapshot()).as("closed trigger replay via committed event").isEqualTo(before);
        assertThat(automaticOpenCount()).as("no redundant listener submission").isEqualTo(opensBefore);

        // A new command ID bypasses core command-id replay: the handler itself must enforce membership.
        Receipt replay = automation.submit("open_investigation", obj().put("trigger_id", consumed), run, 1L).join();
        settle();
        assertThat(replay.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(replay.recordRefs()).isEmpty();
        assertThat(workflowSnapshot()).as("closed trigger replay directly through production handler").isEqualTo(before);

        String fresh = trigger("N2");
        List<StoredRecord> all = store.list("investigation", run);
        assertThat(all).hasSize(2);
        StoredRecord next = all.stream().filter(record -> !record.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(next.body().path("trigger_ids")).isEqualTo(json.valueToTree(List.of(fresh)));
        assertThat(next.body().path("trigger_id").asText()).isEqualTo(fresh);
        assertThat(next.body().path("warned_area_ids")).isEqualTo(json.valueToTree(List.of("AREA-E")));
        assertThat(store.get("investigation", first.id()).orElseThrow()).isEqualTo(before.investigations().get(0));
        assertThat(store.list("analysis", run)).hasSize(before.analyses().size() + (hasActiveB ? 0 : 1));
        assertThat(store.list("proposal", run)).hasSize(before.proposals().size() + (hasActiveB ? 0 : 1));
    }

    @Test void membershipInAnotherRunDoesNotConsumeAFreshSameRunTrigger() {
        String fresh = trigger("N2");
        seed("investigation", obj().put("trigger_id", fresh).put("state", "CLOSED"), id());
        mode.active = true;
        events.publishEvent(new RecordSaved("trigger", fresh, 1, run, "automation", true));
        settle();
        assertThat(investigation().body().path("trigger_id").asText()).isEqualTo(fresh);
        assertThat(store.list("analysis", run)).hasSize(1);
        assertThat(store.list("proposal", run)).hasSize(1);
    }

    @Test void concurrentCommittedTriggersLeaveOneAnalysisOfTheLatestIntake() {
        mode.active=true;
        var p=obj().put("type","trigger").put("id",id());
        p.set("fields",obj().put("sensor","N2").put("channel","SO2_ppb").put("rule","TEACH-DETECT-1"));
        p.set("second_fields",obj().put("sensor","N3").put("channel","CO2_ppm").put("rule","TEACH-DETECT-1"));
        assertThat(bus.send(new Command("fp/1",id(),"test_ops_seed",run,null,p),Session.human("s","presenter@test",Set.of(Role.PRESENTER))).applied()).isTrue();
        settle();
        assertThat(store.list("investigation",run)).hasSize(1);
        assertThat(investigation().body().path("trigger_ids")).hasSize(2);
        assertThat(store.list("analysis",run)).hasSize(1);
        assertThat(investigation().body().path("state").asText()).isEqualTo("ANALYSED");
    }
    @Test void noSignalAndInvalidDoNotInvokeAThrowingLearnedModel() throws Exception {
        survey();fixture.learnedFailure=true;
        for(var o:List.of(Outcome.INVALID,Outcome.NO_SIGNAL)){
            fixture.outcome=o;analyze();
            var all=store.list("analysis",run);var a=all.get(all.size()-1).body();
            assertThat(a.path("outcome").asText()).isEqualTo(o.name());
            assertThat(a.path("learned_status").asText()).isEqualTo("NOT_APPLICABLE");
        }
    }
    @Test void surveyWithoutAlarmSavesDeclaredPublicBasisAndNeverWarns() throws Exception {
        String s = survey();
        var body = store.get("survey",s).orElseThrow().body();
        assertThat(body.path("target").toString()).isEqualTo("{\"east\":0.0,\"north\":100.0,\"up\":35.0}");
        assertThat(body.path("method_id").asText()).isEqualTo("TEACH-NODE-ONLINE-2");
        assertThat(body.path("channel_ids")).isEqualTo(json.valueToTree(List.of("SO2_ppb","CO2_ppm")));
        assertThat(body.path("vehicle_id").asText()).isNotBlank();
        assertThat(body.path("investigation_id").asText()).isEqualTo(investigation().id());
        assertThat(investigation().body().path("survey_id").asText()).isEqualTo(s);
        assertThat(investigation().body().path("warned_area_ids")).isEmpty();
    }
    @Test void acceptsSameRunAnomalyButRejectsForeignOrMissingReferencesAndUnknownActions() throws Exception {
        String a = seed("anomaly",obj().put("state","OPEN"));
        assertThat(send("schedule_survey",obj().put("action_id","SURVEY-DRONE-280-140-45").put("reason","Inspect unusual air").put("anomaly_id",a),null,analyst).path("status").asText()).isEqualTo("APPLIED");
        for (String invalid : List.of(id(), seed("anomaly",obj(),id()))) {
            assertThat(send("schedule_survey",obj().put("action_id","SURVEY-DRONE-0-100-35").put("reason","Check").put("anomaly_id",invalid),null,analyst).path("status").asText()).isEqualTo("REJECTED");
        }
        assertThat(send("schedule_survey",obj().put("action_id","hidden-target").put("reason","Check"),null,analyst).path("status").asText()).isEqualTo("REJECTED");
        assertThat(send("schedule_survey",obj().put("action_id","SURVEY-DRONE-0-100-35").put("reason"," "),null,analyst).path("status").asText()).isEqualTo("REJECTED");
    }
    @ParameterizedTest @EnumSource(Outcome.class)
    void preservesAllFivePhysicsOutcomesAndHonestLearnedStatus(Outcome outcome) throws Exception {
        survey(); fixture.outcome=outcome;
        assertThat(analyze().path("status").asText()).isEqualTo("APPLIED");
        JsonNode a = store.list("analysis",run).get(0).body();
        assertThat(a.path("outcome").asText()).isEqualTo(outcome.name());
        assertThat(a.path("candidates").toString()).isEqualTo(json.valueToTree(Fixtures.candidates()).toString());
        assertThat(a.path("exclusions")).isEqualTo(json.valueToTree(List.of(new Exclusion("excluded-window","STALE"))));
        assertThat(a.path("limitations")).isEqualTo(json.valueToTree(List.of("Fixture scope only")));
        assertThat(a.path("learned_status").asText()).isEqualTo(outcome==Outcome.INVALID || outcome==Outcome.NO_SIGNAL ? "NOT_APPLICABLE":"MODEL_MISSING");
        assertThat(a.path("learned_model_version_id").isNull()).isTrue();
        assertThat(a.path("history_counts").isNull()).isTrue();
    }
    @ParameterizedTest @EnumSource(ProposalResult.class)
    void storesEveryPlannerResultWithoutInventingRecommendation(ProposalResult result) throws Exception {
        survey(); fixture.result=result;
        assertThat(analyze().path("status").asText()).isEqualTo("APPLIED");
        var a=store.list("analysis",run).get(0); var p=store.list("proposal",run).get(0).body();
        assertThat(p.path("result").asText()).isEqualTo(result.name());
        assertThat(p.path("analysis_id").asText()).isEqualTo(a.id());
        assertThat(p.path("best_action_id").asText(null)).isEqualTo(result==ProposalResult.BEST_ACTION ? "fixture-action":null);
        assertThat(p.path("actions")).hasSize(1);
        assertThat(p.path("investigation_id").asText()).isEqualTo(investigation().id());
    }
    @Test void missingModelAndNoUsefulMeasurementRemainHonestTogether() throws Exception {
        survey();fixture.outcome=Outcome.UNKNOWN_OR_MISMATCH;fixture.result=ProposalResult.NO_USEFUL_MEASUREMENT;
        assertThat(analyze().path("status").asText()).isEqualTo("APPLIED");
        var a=store.list("analysis",run).get(0).body();var p=store.list("proposal",run).get(0).body();
        assertThat(a.path("outcome").asText()).isEqualTo("UNKNOWN_OR_MISMATCH");
        assertThat(a.path("learned_status").asText()).isEqualTo("MODEL_MISSING");
        assertThat(a.path("learned_order")).isEmpty();
        assertThat(p.path("result").asText()).isEqualTo("NO_USEFUL_MEASUREMENT");
        assertThat(p.path("best_action_id").isNull()).isTrue();
        assertThat(store.list("mission",run)).isEmpty();
    }
    @Test void buildsAnalysisFromSameRunPublicWindowsClockWindAndVehicles() throws Exception {
        survey();
        String window=seed("window",obj().put("sensor_or_vehicle_id","N2").put("channel","SO2_ppb").put("start_s",0).put("end_s",30).put("valid_seconds",30).put("mean_enhancement",12).put("std_uncertainty",2));
        seed("window",obj().put("channel","CO2_ppm"),id());
        analyze();
        var a=store.list("analysis",run).get(0).body();
        assertThat(a.path("input_window_ids")).isEqualTo(json.valueToTree(List.of(window)));
        assertThat(a.path("input_window_refs").get(0).path("revision").asInt()).isEqualTo(1);
        assertThat(fixture.lastInput.runId()).isEqualTo(run);
        assertThat(fixture.lastInput.windows()).hasSize(1);
        assertThat(fixture.lastInput.wind()).isNotNull();
        assertThat(fixture.lastContext.analysisId()).isEqualTo(a.path("id").asText());
        assertThat(fixture.lastContext.vehicles()).isNotEmpty();
        assertThat(fixture.planned.inputWindowIds()).containsExactly(window);
    }
    @Test void failedOrIllegalLearnedOrderCannotChangePhysics() throws Exception {
        survey(); fixture.learnedFailure=true;
        analyze();
        var a=store.list("analysis",run).get(0).body();
        assertThat(a.path("outcome").asText()).isEqualTo("AMBIGUOUS_OR_OPTIONAL_MIXTURE");
        assertThat(a.path("learned_status").asText()).isEqualTo("FAILED");
        assertThat(a.path("candidates").toString()).isEqualTo(json.valueToTree(Fixtures.candidates()).toString());
        assertThat(a.path("limitations")).isEqualTo(json.valueToTree(List.of("Fixture scope only")));
        assertThat(a.path("learned_failure_reason").asText()).isNotBlank();
    }
    @Test void rejectedCandidateCannotBeRestoredByLearnedRanker() throws Exception {
        survey(); fixture.illegalOrder=true; analyze();
        var a=store.list("analysis",run).get(0).body();
        assertThat(a.path("learned_status").asText()).isEqualTo("FAILED");
        assertThat(a.path("learned_order")).isEmpty();
        assertThat(a.path("candidates").toString()).isEqualTo(json.valueToTree(Fixtures.candidates()).toString());
    }
    @Test void allowedLearnedOrderAndModelMetadataAreSavedExactly() throws Exception {
        survey(); fixture.validOrder=true; analyze();
        var a=store.list("analysis",run).get(0).body();
        assertThat(a.path("learned_status").asText()).isEqualTo("OK");
        assertThat(a.path("learned_model_version_id").asText()).isEqualTo("fixture-model");
        assertThat(a.path("learned_order").toString()).isEqualTo(json.valueToTree(List.of(new LearnedRank("S01",1,0.4))).toString());
        assertThat(a.path("history_counts").path("S01").asInt()).isEqualTo(2);
    }
    @Test void rejectsWrongRolesStaleRevisionAndForeignInvestigation() throws Exception {
        survey();
        assertThat(send("run_analysis",obj().put("investigation_id",investigation().id()),0L,analyst).path("reason_code").asText()).isEqualTo("STALE_REVISION");
        assertThat(send("run_analysis",obj().put("investigation_id",investigation().id()),null,presenter).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(send("schedule_survey",obj().put("action_id","SURVEY-DRONE-0-100-35").put("reason","Check"),null,presenter).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(send("open_investigation",obj().put("trigger_id",trigger("N2")),null,analyst).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
        String foreign=seed("investigation",obj().put("state","OPEN"),id());
        assertThat(send("run_analysis",obj().put("investigation_id",foreign),null,analyst).path("status").asText()).isEqualTo("REJECTED");
        assertThat(store.list("analysis",run)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"BEST","CHEAPER"})
    void malformedPendingPlannerRecommendationLeavesNoPartialWorkflow(String recommendation) throws Exception {
        survey();fixture.result=ProposalResult.PENDING;fixture.pendingRecommendation=recommendation;
        WorkflowSnapshot before=workflowSnapshot();
        var receipt=send("run_analysis",obj().put("investigation_id",investigation().id()),investigation().revision(),analyst);
        assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");
        assertThat(receipt.path("reason_code").asText()).isEqualTo("INVALID");
        assertThat(receipt.path("record_refs")).isEmpty();
        assertThat(workflowSnapshot()).as("malformed PENDING must roll back analysis, proposal, investigation and downstream notification").isEqualTo(before);
    }
    @Test void missingWindowTimesAreRejectedInsteadOfBecomingZero() throws Exception {
        survey();seed("window",obj().put("sensor_or_vehicle_id","N2").put("channel","SO2_ppb").put("valid_seconds",30));
        assertThat(analyze().path("status").asText()).isEqualTo("REJECTED");
        assertThat(store.list("analysis",run)).isEmpty();
    }
    @Test void openInvestigationRejectsUnknownForeignAndStaleTriggerReferences() {
        String current=trigger("N2");String foreign=seed("trigger",obj().put("sensor","N2"),id());mode.active=true;
        assertThat(automation.submit("open_investigation",obj().put("trigger_id",id()),run,null).join().status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(automation.submit("open_investigation",obj().put("trigger_id",foreign),run,null).join().status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(automation.submit("open_investigation",obj().put("trigger_id",current),run,0L).join().reasonCode()).isEqualTo("STALE_REVISION");
        settle();assertThat(store.list("investigation",run)).isEmpty();
    }
    @Test void retryReturnsOriginalReceiptAndDoesNotDuplicateSurveyOrAnalysis() throws Exception {
        var command=new Command("fp/1",id(),"schedule_survey",run,null,obj().put("action_id","SURVEY-DRONE-0-100-35").put("reason","Teaching"));
        JsonNode first=send(command,analyst); assertThat(send(command,analyst)).isEqualTo(first);
        var analysisCommand=new Command("fp/1",id(),"run_analysis",run,investigation().revision(),obj().put("investigation_id",investigation().id()));
        JsonNode analysis=send(analysisCommand,analyst); assertThat(send(analysisCommand,analyst)).isEqualTo(analysis);
        assertThat(store.list("survey",run)).hasSize(1); assertThat(store.list("analysis",run)).hasSize(1);
    }
    @Test void automaticIntakeAnalyzesOnceAndReplayDoesNotCreateWork() throws Exception {
        mode.active=true; survey();
        assertThat(store.list("analysis",run)).hasSize(1);
        var inv=investigation(); events.publishEvent(new RecordSaved("investigation",inv.id(),1,run,"analyst",false)); settle();
        assertThat(store.list("analysis",run)).hasSize(1);
        mode.active=false; trigger("N2");
        assertThat(investigation().body().path("trigger_ids")).isEmpty();
        assertThat(store.list("analysis",run)).hasSize(1);
    }
    @Test void evidenceUsesCorrectMissionAndRunAndTwoChannelsCauseOneNewAnalysis() throws Exception {
        mode.active=true; survey();
        var proposal=store.list("proposal",run).get(0);
        String mission=seed("mission",obj().put("proposal_id",proposal.id()).put("state","COMPLETE"));
        String attempt=seed("attempt",obj().put("mission_id",mission).put("state","COMPLETE"));
        ObjectNode p=obj().put("type","screening_record").put("id",id());
        ObjectNode one=obj().put("mission_id",mission).put("attempt_id",attempt).put("channel","SO2_ppb").put("decision","VALID");
        p.set("fields",one); p.set("second_fields",one.deepCopy().put("channel","CO2_ppm").put("decision","REJECTED"));
        var receipt=bus.send(new Command("fp/1",id(),"test_ops_seed",run,null,p),Session.human("s","presenter@test",Set.of(Role.PRESENTER))); settle();
        assertThat(receipt.applied()).isTrue();
        assertThat(store.list("analysis",run)).hasSize(2);
        assertThat(investigation().body().path("state").asText()).isEqualTo("EVIDENCE_BACK");
        assertThat(investigation().body().path("screening_record_ids")).hasSize(2);
        var screening=store.list("screening_record",run).get(0);
        events.publishEvent(new RecordSaved("screening_record",screening.id(),1,run,"qc",false)); settle();
        seed("screening_record",one.deepCopy(),id());
        seed("screening_record",one.deepCopy().put("mission_id",id()));
        assertThat(store.list("analysis",run)).hasSize(2);
    }
    @Test void resetRejectsOldRunCommandsAndDoesNotAttachOldTriggers() throws Exception {
        survey(); String old=run; String inv=investigation().id();
        send("reset_run",obj().put("preset_id","SC2-BLOCKED-INLET").put("mode","TEACHING"),null,presenter);
        assertThat(send("run_analysis",obj().put("investigation_id",inv),null,analyst).path("status").asText()).isEqualTo("REJECTED");
        mode.active=true; trigger("N2"); assertThat(store.list("analysis",old)).isEmpty();
    }

    @Test void surveyReasonRefusesPrivateContactTextBeforeSavingPublicRecords()throws Exception {
        var receipt=send("schedule_survey",obj().put("action_id","SURVEY-DRONE-0-100-35").put("reason","Call +919612345670"),null,analyst);
        assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");assertThat(receipt.path("record_refs")).isEmpty();
        assertThat(store.list("survey",run)).isEmpty();assertThat(store.list("investigation",run)).isEmpty();
    }
    @TestConfiguration static class Config {
        @Bean @Primary Fixtures opsFixture() { return new Fixtures(); }
        @Bean @Primary TestMode opsMode() { return new TestMode(); }
        @Bean CommandHandler opsSeeder() { return new CommandHandler() {
            public String commandType() { return "test_ops_seed"; }
            public Set<Role> allowedRoles() { return Set.of(Role.PRESENTER); }
            public HandlerOutcome handle(Command c,Session s,StoreView v) {
                var p=c.payload(); var b=HandlerResult.builder().save(new RecordChange(p.path("type").asText(),p.path("id").asText(),c.runId(),p.path("based_on_revision").asLong(0),(ObjectNode)p.path("fields")),"TEST_SEEDED",0.0);
                if (p.has("second_fields")) b.save(RecordChange.create(p.path("type").asText(),id(),c.runId(),(ObjectNode)p.path("second_fields")),"TEST_SEEDED",0.0);
                return b.build();
            }
        }; }
    }
    static class TestMode implements ServerMode { volatile boolean active; public boolean isReplay(){return false;} public boolean automationActive(){return active;} }
    static class Fixtures implements Ranker,Planner,LearnedRanker {
        Outcome outcome; ProposalResult result; boolean learnedFailure,illegalOrder,validOrder; String pendingRecommendation;
        AnalysisInput lastInput; PlanContext lastContext; Analysis planned;
        void reset(){pendingRecommendation=null;outcome=Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE;result=ProposalResult.NO_USEFUL_MEASUREMENT;learnedFailure=false;illegalOrder=false;validOrder=false;}
        static List<Candidate> candidates(){return List.of(new Candidate("S01",List.of("S01"),1.2,CandidateStatus.SURVIVES,null),new Candidate("S02",List.of("S02"),5.0,CandidateStatus.REJECTED,"Poor fit"),new Candidate("UNKNOWN",List.of(),null,CandidateStatus.SURVIVES,"Unregistered source remains possible"));}
        public Analysis analyze(AnalysisInput input){ lastInput=input;return Analysis.physics(input.windows().stream().map(Window::id).toList(),outcome,Qualification.SYNTHETIC_SCOPE_ONLY,candidates(),List.of(new Exclusion("excluded-window","STALE")),List.of("Fixture scope only"),"Fixture explanation","FIXTURE-1"); }
        public LearnedOrder order(AnalysisInput input,Analysis a){
            if(learnedFailure)throw new IllegalStateException("fixture failure");
            if(illegalOrder)return new LearnedOrder(List.of(new LearnedRank("S02",1,0.5)),LearnedStatus.OK,"fixture-model",null);
            if(validOrder)return new LearnedOrder(List.of(new LearnedRank("S01",1,0.4)),LearnedStatus.OK,"fixture-model",java.util.Map.of("S01",2));
            return LearnedOrder.none(a.outcome()==Outcome.INVALID||a.outcome()==Outcome.NO_SIGNAL?LearnedStatus.NOT_APPLICABLE:LearnedStatus.MODEL_MISSING,null,null);
        }
        public Proposal plan(Analysis a,PlanContext c){lastContext=c;planned=a;return new Proposal(c.analysisId(),List.of(new ProposalAction("fixture-action","DISCRIMINATE","DRONE",new Enu(0,100,35),result==ProposalResult.BEST_ACTION?1.0:null,result==ProposalResult.BEST_ACTION?7.0:null,result==ProposalResult.BEST_ACTION,result==ProposalResult.BEST_ACTION?null:"Fixture unavailable")),result,result==ProposalResult.BEST_ACTION || "BEST".equals(pendingRecommendation)?"fixture-action":null,"CHEAPER".equals(pendingRecommendation)?"fixture-action":null);}
    }
}
