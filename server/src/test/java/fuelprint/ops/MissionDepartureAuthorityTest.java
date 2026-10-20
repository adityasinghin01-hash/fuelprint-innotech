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
@Import({OperationsWorkflowTest.Config.class, MissionDepartureAuthorityTest.Config.class})
class MissionDepartureAuthorityTest {
    static final Path DATA = CoreSpringTest.TempFolders.create("fp-departure-authority-");
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
    @Autowired Gate gate;
    Cookie supervisor;
    String run;
    Cookie analyst;
    Cookie presenter;

    static String id() { return UUID.randomUUID().toString(); }
    ObjectNode obj() { return json.createObjectNode(); }
    @BeforeEach void fresh() throws Exception {
        settle(); mode.active = false; fixture.reset(); gate.reset();
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

    void fieldStep(double now,double dt) {
        listeners.stream().filter(l->l.getClass().getName().equals("fuelprint.field.world.FieldWorld")).findFirst().orElseThrow().onStep(now,dt);
    }
    ObjectNode departurePayload(String type) {
        var p=obj().put("vehicle_id",mission().body().path("vehicle_id").asText());
        if(!type.equals("take_over"))p.put("mission_id",mission().id());return p;
    }
    record Waiting(Thread thread,java.util.concurrent.CompletableFuture<Receipt> receipt) {}
    Waiting sendAsync(Command command,Cookie cookie) {
        var result=new java.util.concurrent.CompletableFuture<Receipt>();
        Session session=sessions.resolveToken(cookie.getValue()).orElseThrow();
        Thread thread=new Thread(()->{try{result.complete(bus.send(command,session));}catch(Throwable failure){result.completeExceptionally(failure);}},"authority-test-human-request");
        thread.start();return new Waiting(thread,result);
    }
    void awaitQueued(Waiting waiting) {
        // send() enqueues before waiting in CompletableFuture.get. Observing that exact frame/state
        // witnesses queue insertion without reflection into Core or a timing sleep.
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            boolean inBus=java.util.Arrays.stream(waiting.thread().getStackTrace()).anyMatch(f->f.getClassName().equals(CommandBus.class.getName())&&f.getMethodName().equals("send"));
            if(inBus&&waiting.thread().getState()==Thread.State.TIMED_WAITING)return;
            if(waiting.receipt().isDone())throw new AssertionError("Departure completed before the held analysis released");
            Thread.onSpinWait();Thread.yield();
        }
        throw new AssertionError("Human departure did not reach its queued receipt wait");
    }
    void refusalBeforeExpiry(Receipt receipt,String code,StoredRecord approved) {
        System.out.println("AUTHORITY_RACE_RECEIPT "+json.valueToTree(receipt));
        assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);assertThat(receipt.reasonCode()).isEqualTo(code);
        assertThat(receipt.recordRefs()).isEmpty();assertThat(store.list("lease",run)).isEmpty();
        assertThat(mission()).as("expiry is deliberately still blocked").isEqualTo(approved);
    }
    void expiredAfterRelease(StoredRecord approved,String reason) {
        gate.releaseAll();settle();assertThat(mission().body().path("state").asText()).isEqualTo("EXPIRED");
        assertThat(mission().body().path("reason").asText()).isEqualTo(reason);
        assertThat(store.get("mission",approved.id(),approved.revision()).orElseThrow()).isEqualTo(approved);
        assertThat(store.list("lease",run)).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"acquire_lease,true","acquire_lease,false","start_autopilot,true","start_autopilot,false","AUTO,true","AUTO,false","take_over,false"})
    void queuedDepartureRechecksAnalysisCommittedAheadOfIt(String departure,boolean revision) throws Exception {
        proposed();approve();boolean automatic=departure.equals("AUTO");String type=automatic?"start_autopilot":departure;
        if(automatic){((TestClock)clock).time=60;fieldStep(60,60);settle();}
        StoredRecord approved=mission();mode.active=true;gate.armAnalysis(type);
        var analysis=new Command("fp/1",id(),"run_analysis",run,investigation().revision(),obj().put("investigation_id",investigation().id()));
        Waiting analysisRequest=sendAsync(analysis,analyst);Gate.await(gate.analysisCommitted);
        java.util.concurrent.CompletableFuture<Receipt> departureReceipt;
        try {
            var payload=departurePayload(type);
            if(automatic)departureReceipt=automation.submit(type,payload,run,revision?approved.revision():null);
            else {var waiting=sendAsync(new Command("fp/1",id(),type,run,revision?approved.revision():null,payload),cookie("field_operator"));awaitQueued(waiting);departureReceipt=waiting.receipt();}
            gate.allowAnalysis.countDown();Gate.await(gate.departureDecided);
            assertThat(analysisRequest.receipt().get(10,java.util.concurrent.TimeUnit.SECONDS).applied()).isTrue();
            refusalBeforeExpiry(departureReceipt.get(10,java.util.concurrent.TimeUnit.SECONDS),"NEEDS_NEW_APPROVAL",approved);
        }finally{gate.releaseAll();}
        expiredAfterRelease(approved,"NEEDS_NEW_APPROVAL");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"WIND","TIME"})
    void fieldCallbackFirstCannotStartStaleAuthorityBeforeOpsExpiry(String cause) throws Exception {
        proposed();approve();((TestClock)clock).time=cause.equals("TIME")?299.9:59.9;
        fieldStep(((TestClock)clock).time,59.9);settle();assertThat(store.list("lease",run)).isEmpty();
        StoredRecord approved=mission();mode.active=true;gate.armDeparture("start_autopilot");gate.armBus();
        Waiting barrier=sendAsync(new Command("fp/1",id(),"test_authority_barrier",run,null,obj()),presenter);Gate.await(gate.busHeld);
        try {
            ((TestClock)clock).time=cause.equals("TIME")?300:60;if(cause.equals("WIND"))world.wind=new WindNow(275,3);
            // Force the adverse callback order while the command executor is held: Field first, Ops second.
            fieldStep(((TestClock)clock).time,.1);
            listeners.stream().filter(l->l.getClass().getPackageName().equals("fuelprint.ops")).forEach(l->l.onStep(((TestClock)clock).time,.1));
            gate.allowBus.countDown();Gate.await(gate.departureDecided);
            assertThat(barrier.receipt().get(10,java.util.concurrent.TimeUnit.SECONDS).applied()).isTrue();
            refusalBeforeExpiry(gate.departureReceipt,cause.equals("TIME")?"TIMED_OUT":"NEEDS_NEW_APPROVAL",approved);
        }finally{gate.releaseAll();}
        expiredAfterRelease(approved,cause.equals("TIME")?"TIMED_OUT":"NEEDS_NEW_APPROVAL");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"acquire_lease,true","acquire_lease,false","start_autopilot,true","start_autopilot,false","AUTO,true","AUTO,false","take_over,false"})
    void currentApprovalStillAllowsInitialDeparture(String departure,boolean revision) throws Exception {
        proposed();approve();boolean automatic=departure.equals("AUTO");String type=automatic?"start_autopilot":departure;
        if(automatic){((TestClock)clock).time=60;fieldStep(60,60);settle();mode.active=true;}
        Receipt receipt=automatic?automation.submit(type,departurePayload(type),run,revision?mission().revision():null).join():bus.send(new Command("fp/1",id(),type,run,revision?mission().revision():null,departurePayload(type)),sessions.resolveToken(cookie("field_operator").getValue()).orElseThrow());
        settle();assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);assertThat(mission().body().path("state").asText()).isEqualTo("LEASED");assertThat(store.list("lease",run)).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"acquire_lease,true","acquire_lease,false","start_autopilot,true","start_autopilot,false","take_over,false"})
    void timedOutAuthorityIsRefusedWithoutWaitingForExpiry(String type,boolean revision) throws Exception {
        proposed();approve();StoredRecord approved=mission();((TestClock)clock).time=300;
        var receipt=bus.send(new Command("fp/1",id(),type,run,revision?approved.revision():null,departurePayload(type)),sessions.resolveToken(cookie("field_operator").getValue()).orElseThrow());
        refusalBeforeExpiry(receipt,"TIMED_OUT",approved);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"dependency_hash","expires_s","survey_id"})
    void missingRecordedAuthorityNeverGetsAProductionBypass(String field) throws Exception {
        proposed();approve();var fields=OpsRecords.own(mission());fields.remove(field);update(mission(),fields);
        StoredRecord approved=mission();var receipt=bus.send(new Command("fp/1",id(),"acquire_lease",run,approved.revision(),departurePayload("acquire_lease")),sessions.resolveToken(cookie("field_operator").getValue()).orElseThrow());
        refusalBeforeExpiry(receipt,"NEEDS_NEW_APPROVAL",approved);
    }
    @Test void staleDependenciesDoNotRevokeLegitimatelyStartedAuthorityOrResume() throws Exception {
        proposed();approve();var operator=cookie("field_operator");
        assertThat(send("acquire_lease",departurePayload("acquire_lease"),2L,operator).path("status").asText()).isEqualTo("APPLIED");
        ((TestClock)clock).time=301;world.wind=new WindNow(275,3);analyze();
        var lease=store.list("lease",run).get(0);
        assertThat(send("release_lease",obj().put("lease_id",lease.id()),lease.revision(),operator).path("status").asText()).isEqualTo("APPLIED");
        assertThat(send("start_autopilot",departurePayload("start_autopilot"),mission().revision(),operator).path("status").asText()).isEqualTo("APPLIED");
        assertThat(mission().body().path("state").asText()).isEqualTo("LEASED");assertThat(mission().body().path("pilot").asText()).isEqualTo("AUTOPILOT");
    }
    @TestConfiguration static class Config {
        @Bean @Primary TestWorld missionWorld(){return new TestWorld();}
        @Bean Gate authorityGate(){return new Gate();}
        @Bean CommandHandler authorityBarrier(Gate gate){return new CommandHandler(){
            public String commandType(){return "test_authority_barrier";}public Set<Role> allowedRoles(){return Set.of(Role.PRESENTER);}
            public HandlerOutcome handle(Command c,Session s,StoreView v){if(gate.blockBus){gate.busHeld.countDown();Gate.await(gate.allowBus);}return HandlerResult.nothing();}
        };}
    }
    static ClockControl testClock(){return new TestClock();}
    static class TestClock implements ClockControl {volatile double time;public double simTimeS(){return time;}public double timeScale(){return 1;}public void requestTimeScale(String r,double s){}public void releaseTimeScale(String r){} }
    static class TestWorld implements WorldClock {volatile WindNow wind=new WindNow(270,3);public WindNow wind(){return wind;}public void step(double dt){} }
    static class Gate {
        volatile boolean blockAnalysis,blockBus,blockDeparture;volatile String departureType;volatile Receipt departureReceipt;
        java.util.concurrent.CountDownLatch analysisCommitted,allowAnalysis,departureDecided,allowExpiry,busHeld,allowBus;
        void reset(){blockAnalysis=false;blockBus=false;blockDeparture=false;departureReceipt=null;analysisCommitted=new java.util.concurrent.CountDownLatch(1);allowAnalysis=new java.util.concurrent.CountDownLatch(1);departureDecided=new java.util.concurrent.CountDownLatch(1);allowExpiry=new java.util.concurrent.CountDownLatch(1);busHeld=new java.util.concurrent.CountDownLatch(1);allowBus=new java.util.concurrent.CountDownLatch(1);}
        void armAnalysis(String type){blockAnalysis=true;armDeparture(type);}
        void armDeparture(String type){departureType=type;blockDeparture=true;}
        void armBus(){blockBus=true;}
        void releaseAll(){blockAnalysis=false;blockBus=false;blockDeparture=false;if(allowAnalysis!=null)allowAnalysis.countDown();if(allowExpiry!=null)allowExpiry.countDown();if(allowBus!=null)allowBus.countDown();}
        static void await(java.util.concurrent.CountDownLatch latch){try{if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Controlled command barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
        @org.springframework.context.event.EventListener @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        void analysis(RecordSaved event){if(blockAnalysis&&event.recordType().equals("analysis")){blockAnalysis=false;analysisCommitted.countDown();await(allowAnalysis);}}
        @org.springframework.context.event.EventListener @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        void departure(CommandCommitted event){if(blockDeparture&&event.commandType().equals(departureType)){blockDeparture=false;departureReceipt=event.receipt();departureDecided.countDown();await(allowExpiry);}}
    }
}
