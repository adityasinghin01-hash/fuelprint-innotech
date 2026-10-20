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
@Import({OperationsWorkflowTest.Config.class, MissionWorkflowTest.Config.class})
class MissionWorkflowTest {
    static final Path DATA = CoreSpringTest.TempFolders.create("fp-missions-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r) { r.add("fp.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordStore store;
    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired CommandLog commands;
    @Autowired CookieSessionService sessions;
    @Autowired ApplicationEventPublisher events;
    @Autowired OperationsWorkflowTest.Fixtures fixture;
    @Autowired OperationsWorkflowTest.TestMode mode;
    @org.springframework.test.context.bean.override.convention.TestBean(name="clockControl", methodName="testClock") ClockControl clock;
    @Autowired TestWorld world;
    @Autowired List<WorldStepListener> listeners;
    Cookie supervisor;
    String run;
    Cookie analyst;
    Cookie presenter;

    static String id() { return UUID.randomUUID().toString(); }
    ObjectNode obj() { return json.createObjectNode(); }
    @BeforeEach void fresh() throws Exception {
        settle(); mode.active = false; fixture.reset();
        analyst = cookie("analyst"); presenter = cookie("presenter"); supervisor = cookie("supervisor");
        ((TestClock)clock).time=0;world.wind=new WindNow(270,3);
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


    StoredRecord mission() { assertThat(store.list("mission",run)).as("real proposed mission").hasSize(1);return store.list("mission",run).get(0); }
    JsonNode proposeSurvey(String s) throws Exception { return send("propose_mission",obj().put("survey_id",s).put("action_id","SURVEY-DRONE-0-100-35"),1L,analyst); }
    String proposed() throws Exception { assertThat(proposeSurvey(survey()).path("status").asText()).isEqualTo("APPLIED");return mission().id(); }
    ObjectNode approval() {return obj().put("mission_id",mission().id()).put("dependency_hash",mission().body().path("dependency_hash").asText());}
    void approve() throws Exception {assertThat(send("approve_mission",approval(),mission().revision(),supervisor).path("status").asText()).isEqualTo("APPLIED");}
    void step() { listeners.stream().filter(l->l.getClass().getPackageName().equals("fuelprint.ops")).forEach(l->l.onStep(((TestClock)clock).time,.1));settle(); }
    void update(StoredRecord record,ObjectNode fields){
        var p=obj().put("type",record.type()).put("id",record.id()).put("based_on_revision",record.revision());p.set("fields",fields);
        assertThat(bus.send(new Command("fp/1",id(),"test_ops_seed",record.runId(),null,p),Session.human("s","presenter@test",Set.of(Role.PRESENTER))).applied()).isTrue();settle();
    }
    StoredRecord plan() throws Exception {survey();fixture.result=ProposalResult.BEST_ACTION;analyze();return store.list("proposal",run).get(0);}
    ObjectNode planPayload(StoredRecord p){return obj().put("proposal_id",p.id()).put("action_id","fixture-action");}
    @Test void independentSurveyCreatesFullServerMissionWithoutInventingPlannerBenefit() throws Exception {
        String s=survey();analyze();assertThat(store.list("proposal",run).get(0).body().path("result").asText()).isEqualTo("NO_USEFUL_MEASUREMENT");
        assertThat(proposeSurvey(s).path("status").asText()).isEqualTo("APPLIED");var b=mission().body();
        assertThat(UUID.fromString(mission().id()).version()).isEqualTo(4);
        assertThat(b.path("state").asText()).isEqualTo("PROPOSED");assertThat(b.path("pilot").asText()).isEqualTo("NONE");
        assertThat(b.path("target").toString()).isEqualTo("{\"east\":0.0,\"north\":100.0,\"up\":35.0}");
        assertThat(b.path("route")).isEqualTo(json.readTree("[{\"east\":0.0,\"north\":100.0,\"up\":35.0}]"));
        assertThat(b.path("expires_s").asDouble()).isEqualTo(300);assertThat(b.path("dependency_hash").asText()).matches("[0-9a-f]{64}");
        assertThat(b.path("method_id").asText()).isEqualTo("TEACH-NODE-ONLINE-2");assertThat(b.path("channel_ids")).hasSize(2);
        assertThat(investigation().body().path("state").asText()).isEqualTo("MISSION_PROPOSED");
    }
    @Test void supervisorApprovalFeedsRealFieldLeaseAndRetainsHistory() throws Exception {
        proposed();var before=mission();approve();assertThat(investigation().body().path("state").asText()).isEqualTo("MISSION_APPROVED");
        assertThat(store.get("mission",before.id(),1).orElseThrow().body().path("state").asText()).isEqualTo("PROPOSED");
        assertThat(send("acquire_lease",obj().put("mission_id",mission().id()).put("vehicle_id",mission().body().path("vehicle_id").asText()),null,cookie("field_operator")).path("status").asText()).isEqualTo("APPLIED");
        assertThat(mission().body().path("state").asText()).isEqualTo("LEASED");
    }
    @Test void feasibleLatestManualPlanCreatesMission() throws Exception {var p=plan();assertThat(send("propose_mission",planPayload(p),p.revision(),analyst).path("status").asText()).isEqualTo("APPLIED");}
    @ParameterizedTest @ValueSource(strings={"PENDING","NO_USEFUL_MEASUREMENT","INFEASIBLE","UNKNOWN","STALE","STALE_WIND","FOREIGN"})
    void invalidManualPlanCannotCreateMission(String variant) throws Exception {
        var p=plan();var payload=planPayload(p);
        if(variant.equals("STALE"))analyze();
        else if(variant.equals("STALE_WIND"))world.wind=new WindNow(275,3);
        else if(variant.equals("FOREIGN"))payload.put("proposal_id",seed("proposal",OpsRecords.own(p),id()));
        else {var b=OpsRecords.own(p);if(variant.equals("INFEASIBLE"))((ObjectNode)b.path("actions").get(0)).put("feasible",false);
            else if(variant.equals("UNKNOWN"))payload.put("action_id","unknown");else b.put("result",variant);update(p,b);}
        var r=send("propose_mission",payload,null,analyst);assertThat(r.path("status").asText()).isEqualTo("REJECTED");assertThat(r.path("reason_text").asText()).isNotBlank();assertThat(store.list("mission",run)).isEmpty();
    }
    @Test void automationProposesBestOnceAndReplayDoesNotDuplicate() throws Exception {
        mode.active=true;fixture.result=ProposalResult.BEST_ACTION;survey();assertThat(mission().body().path("state").asText()).isEqualTo("PROPOSED");
        var p=store.list("proposal",run).get(0);events.publishEvent(new RecordSaved("proposal",p.id(),p.revision(),run,"automation",true));settle();assertThat(store.list("mission",run)).hasSize(1);
    }
    @Test void automaticProposalReplayCannotRecreateAHumanRejectedMission() throws Exception {
        mode.active=true;fixture.result=ProposalResult.BEST_ACTION;survey();var p=store.list("proposal",run).get(0);
        assertThat(send("reject_mission",obj().put("mission_id",mission().id()).put("reason","Supervisor declined this action"),1L,supervisor).path("status").asText()).isEqualTo("APPLIED");
        var before=store.list("mission",run);long commandsBefore=commands.automationEntries(run,5000).stream().filter(e->e.commandType().equals("propose_mission")).count();
        events.publishEvent(new RecordSaved("proposal",p.id(),p.revision(),run,"automation",true));settle();
        assertThat(store.list("mission",run)).as("replayed proposal after human rejection").isEqualTo(before);
        assertThat(commands.automationEntries(run,5000).stream().filter(e->e.commandType().equals("propose_mission")).count()).isEqualTo(commandsBefore);
        var retry=automation.submit("propose_mission",planPayload(p),run,null).join();settle();
        assertThat(retry.status()).isEqualTo(Receipt.Status.APPLIED);assertThat(retry.recordRefs()).isEmpty();assertThat(store.list("mission",run)).isEqualTo(before);
    }
    @Test void automationSettingAndBestOnlyAreAuthoritativeInsideHandler() throws Exception {
        var p=plan();mode.active=true;
        send("change_automation",obj().put("setting","auto_propose").put("value",false),null,presenter);
        assertThat(automation.submit("propose_mission",planPayload(p),run,null).join().reasonCode()).isEqualTo("AUTOMATION_DISABLED");
        send("change_automation",obj().put("setting","auto_propose").put("value",true),null,presenter);
        var b=OpsRecords.own(p);var other=b.path("actions").get(0).deepCopy();((ObjectNode)other).put("action_id","other");((com.fasterxml.jackson.databind.node.ArrayNode)b.path("actions")).add(other);update(p,b);
        assertThat(automation.submit("propose_mission",obj().put("proposal_id",p.id()).put("action_id","other"),run,null).join().reasonCode()).isEqualTo("NOT_BEST_ACTION");
    }
    @Test void automationNeverTurnsSurveyIntoClaimedBestAction() throws Exception {String s=survey();mode.active=true;assertThat(automation.submit("propose_mission",obj().put("survey_id",s).put("action_id","SURVEY-DRONE-0-100-35"),run,null).join().reasonCode()).isEqualTo("NOT_BEST_ACTION");}
    @ParameterizedTest @ValueSource(strings={"approve_mission","reject_mission"})
    void missionDecisionsRefuseAutomationAndPayloadIdentitySpoofing(String type) throws Exception {
        proposed();var p=approval().put("reason","Declined").put("actor","supervisor").put("role","supervisor");mode.active=true;
        assertThat(automation.submit(type,p,run,null).join().reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(send(type,p,null,analyst).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");assertThat(mission().revision()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"HASH","WIND_DIRECTION","WIND_SPEED","ANALYSIS","BASIS_REVISION","ANALYSIS_REVISION","PROPOSAL_REVISION","ROUTE","NULL_ROUTE","NULL_WIND","TARGET","VEHICLE","METHOD","CHANNELS","PILOT","FOREIGN","ENDED"})
    void approvalRechecksEveryDependency(String change) throws Exception {
        String s=survey();analyze();proposeSurvey(s);var payload=approval();
        switch(change){
            case "HASH" -> payload.put("dependency_hash","0".repeat(64));
            case "WIND_DIRECTION" -> world.wind=new WindNow(275,3);
            case "WIND_SPEED" -> world.wind=new WindNow(270,3.25);
            case "NULL_WIND" -> world.wind=null;
            case "ANALYSIS" -> analyze();
            case "BASIS_REVISION" -> update(store.get("survey",s).orElseThrow(),OpsRecords.own(store.get("survey",s).orElseThrow()));
            case "ANALYSIS_REVISION" -> {var a=store.list("analysis",run).get(0);update(a,OpsRecords.own(a));}
            case "PROPOSAL_REVISION" -> {var p=store.list("proposal",run).get(0);update(p,OpsRecords.own(p));}
            case "FOREIGN" -> {var b=OpsRecords.own(mission());payload.put("mission_id",seed("mission",b,id()));}
            case "ENDED" -> send("reset_run",obj().put("preset_id","SC1-NIGHT-PLUME").put("mode","TEACHING"),null,presenter);
            default -> {var b=OpsRecords.own(mission());switch(change){case "ROUTE" -> ((ObjectNode)b.path("route").get(0)).put("east",1);case "NULL_ROUTE" -> ((ObjectNode)b.path("route").get(0)).putNull("east");case "TARGET" -> ((ObjectNode)b.path("target")).put("east",1);case "VEHICLE" -> b.put("vehicle_id",id());case "METHOD" -> b.put("method_id","changed");case "CHANNELS" -> b.putArray("channel_ids").add("CO2_ppm");case "PILOT" -> b.put("pilot","VISITOR");}update(mission(),b);}
        }
        var before=store.list("mission",run);var investigations=store.list("investigation",run);
        var refused=send("approve_mission",payload,null,supervisor);
        assertThat(refused.path("status").asText()).as(change).isEqualTo("REJECTED");
        assertThat(refused.path("reason_code").asText()).isEqualTo(switch(change){case "HASH"->"STALE_DEPENDENCY";case "FOREIGN"->"INVALID";case "ENDED","PILOT"->"WRONG_STATE";default->"NEEDS_NEW_APPROVAL";});
        assertThat(store.list("mission",run)).isEqualTo(before);assertThat(store.list("investigation",run)).isEqualTo(investigations);
    }
    @ParameterizedTest @ValueSource(strings={"BEFORE","AT","AFTER"})
    void approvalExpiresAtExactly300SimulationSeconds(String boundary) throws Exception {proposed();((TestClock)clock).time=switch(boundary){case "BEFORE"->299.999;case "AT"->300;default->300.001;};var result=send("approve_mission",approval(),1L,supervisor);assertThat(result.path("status").asText()).isEqualTo(boundary.equals("BEFORE")?"APPLIED":"REJECTED");if(!boundary.equals("BEFORE")){assertThat(result.path("reason_code").asText()).isEqualTo("TIMED_OUT");assertThat(mission().revision()).isEqualTo(1);}}
    @Test void proposeRetryReturnsOriginalReceiptAndFreshDuplicateDoesNotWrite() throws Exception {String s=survey();var p=obj().put("survey_id",s).put("action_id","SURVEY-DRONE-0-100-35");var c=new Command("fp/1",id(),"propose_mission",run,1L,p);var first=send(c,analyst);assertThat(first.path("status").asText()).isEqualTo("APPLIED");assertThat(send(c,analyst)).isEqualTo(first);assertThat(send("propose_mission",p,null,analyst).path("reason_code").asText()).isEqualTo("MISSION_PENDING");assertThat(store.list("mission",run)).hasSize(1);}
    @Test void rejectedStaleApprovalRetryCannotChangeTheMissionEvenAfterFreshApproval() throws Exception {
        proposed();var p=approval().put("dependency_hash","stale");
        var command=new Command("fp/1",id(),"approve_mission",run,mission().revision(),p);
        var rejected=send(command,supervisor);
        assertThat(rejected.path("reason_code").asText()).isEqualTo("STALE_DEPENDENCY");
        assertThat(mission().body().path("state").asText()).isEqualTo("PROPOSED");
        assertThat(send(command,supervisor)).isEqualTo(rejected);
        approve();var approved=mission();var revisions=store.revisions("mission",approved.id());
        assertThat(send(command,supervisor)).isEqualTo(rejected);
        assertThat(store.revisions("mission",approved.id())).isEqualTo(revisions);
        assertThat(mission().body().path("state").asText()).isEqualTo("APPROVED");
    }
    @Test void sameWindBucketsAllowApproval() throws Exception {proposed();world.wind=new WindNow(274.999,3.24999);approve();}
    @Test void retriesAndStaleRevisionCannotApproveTwice() throws Exception {proposed();var c=new Command("fp/1",id(),"approve_mission",run,1L,approval());var first=send(c,supervisor);assertThat(first.path("status").asText()).isEqualTo("APPLIED");assertThat(send(c,supervisor)).isEqualTo(first);assertThat(send("approve_mission",approval(),1L,supervisor).path("reason_code").asText()).isEqualTo("STALE_REVISION");assertThat(send("approve_mission",approval(),null,supervisor).path("reason_code").asText()).isEqualTo("WRONG_STATE");}
    @Test void rejectionNeedsReasonAndProposedStateAndRetainsIt() throws Exception {proposed();var p=obj().put("mission_id",mission().id());assertThat(send("reject_mission",p,null,supervisor).path("status").asText()).isEqualTo("REJECTED");p.put("reason","Need a different location");assertThat(send("reject_mission",p,1L,supervisor).path("status").asText()).isEqualTo("APPLIED");assertThat(mission().body().path("reason").asText()).isEqualTo("Need a different location");assertThat(send("reject_mission",p,null,supervisor).path("reason_code").asText()).isEqualTo("WRONG_STATE");}
    @ParameterizedTest @ValueSource(strings={"ANALYSIS","DIRECTION","SPEED","TIME"})
    void approvedUnstartedMissionExpiresAutomatically(String cause) throws Exception {proposed();approve();mode.active=true;switch(cause){case "ANALYSIS"->analyze();case "DIRECTION"->world.wind=new WindNow(275,3);case "SPEED"->world.wind=new WindNow(270,3.25);case "TIME"->((TestClock)clock).time=300;}step();assertThat(mission().body().path("state").asText()).isEqualTo("EXPIRED");assertThat(mission().body().path("reason").asText()).isEqualTo(cause.equals("TIME")?"TIMED_OUT":"NEEDS_NEW_APPROVAL");assertThat(store.get("mission",mission().id(),2).orElseThrow().body().path("state").asText()).isEqualTo("APPROVED");}
    @Test void expireHandlerRefusesFreshAuthorityAndStartedFlight() throws Exception {proposed();approve();mode.active=true;var p=obj().put("mission_id",mission().id()).put("reason","TIMED_OUT");assertThat(automation.submit("expire_mission",p,run,null).join().status()).isEqualTo(Receipt.Status.REJECTED);send("acquire_lease",obj().put("mission_id",mission().id()).put("vehicle_id",mission().body().path("vehicle_id").asText()),null,cookie("field_operator"));((TestClock)clock).time=301;step();assertThat(mission().body().path("state").asText()).isEqualTo("LEASED");assertThat(automation.submit("expire_mission",p,run,null).join().status()).isEqualTo(Receipt.Status.REJECTED);}
    @Test void expiryRespectsBucketAndTimeInteriorAndReplayDisabled() throws Exception {proposed();approve();mode.active=true;world.wind=new WindNow(274.999,3.24999);((TestClock)clock).time=299.999;step();assertThat(mission().body().path("state").asText()).isEqualTo("APPROVED");mode.active=false;((TestClock)clock).time=300;step();assertThat(mission().body().path("state").asText()).isEqualTo("APPROVED");}
    @Test void surveyGuardsUnknownActionVehicleAndAmbiguousBasis() throws Exception {String s=survey();for(var p:List.of(obj().put("survey_id",s).put("action_id","unknown"),obj().put("survey_id",s).put("action_id","SURVEY-DRONE-0-100-35").put("vehicle_id",id()),obj().put("survey_id",s).put("proposal_id",id()).put("action_id","SURVEY-DRONE-0-100-35"))){assertThat(send("propose_mission",p,null,analyst).path("status").asText()).isEqualTo("REJECTED");}assertThat(store.list("mission",run)).isEmpty();}
    @Test void missionRejectionReasonRefusesPrivateContactTextAndKeepsTheProposal()throws Exception {
        proposed();var before=mission();
        var receipt=send("reject_mission",obj().put("mission_id",before.id()).put("reason","Call +919612345671"),before.revision(),supervisor);
        assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");assertThat(receipt.path("record_refs")).isEmpty();assertThat(mission()).isEqualTo(before);
    }
    @TestConfiguration static class Config {
        @Bean @Primary TestWorld missionWorld(){return new TestWorld();}
    }
    static ClockControl testClock(){return new TestClock();}
    static class TestClock implements ClockControl {double time;public double simTimeS(){return time;}public double timeScale(){return 1;}public void requestTimeScale(String r,double s){}public void releaseTimeScale(String r){} }
    static class TestWorld implements WorldClock {WindNow wind=new WindNow(270,3);public WindNow wind(){return wind;}public void step(double dt){} }
}
