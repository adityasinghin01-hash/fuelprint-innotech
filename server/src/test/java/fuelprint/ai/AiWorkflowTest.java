package fuelprint.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.api.*;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/** Actual cookies/bus/after-commit handlers; evidence records are explicitly controlled fixtures. */
@SpringBootTest(properties={"fp.loop.auto=false","fp.field.safety-loop=false"})
@AutoConfigureMockMvc @ActiveProfiles("real") @Import(AiWorkflowTest.Fixtures.class)
class AiWorkflowTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-ai-workflow-");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("fp.data-dir",DATA::toString);
        r.add("fp.alerts.sms-config",()->DATA.resolve("absent-sms-settings.json").toString());
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CookieSessionService sessions;
    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired RecordStore store;
    @Autowired ApplicationEventPublisher events;
    @Autowired CommandLog commandLog;
    @Autowired ObjectProvider<AutomationSettings> settings;
    @Autowired ServerMode mode;
    @Autowired TestMode testMode;
    @Autowired org.springframework.context.ConfigurableApplicationContext context;
    @Autowired ObjectProvider<LearnedRanker> learned;
    @Autowired ObjectProvider<NormalAirModel> normal;
    String run;
    Cookie presenter, analyst, guest;
    List<String> modelsBefore;
    @BeforeEach void setup() throws Exception {
        testMode.active=true;
        presenter=cookie("presenter");analyst=cookie("analyst");guest=cookie("guest");
        var receipt=send(presenter,"reset_run",null,null,object().put("preset_id","SC1-NIGHT-PLUME").put("mode","TEACHING"));
        assertThat(receipt.applied()).isTrue();idle();
        run=store.listAll("run").stream().filter(r->r.body().path("state").asText().equals("RUNNING")).findFirst().orElseThrow().id();
        for(String setting:List.of("auto_retrain","auto_propose","auto_autopilot","auto_alerts"))
            assertThat(send(presenter,"change_automation",null,null,object().put("setting",setting).put("value",false)).applied()).isTrue();
        modelsBefore=modelInventory();
    }
    @AfterEach void retireOnlyTheControlledActiveModelFixture() throws Exception {
        for(var model:store.list("model_version",null)) {
            if(!"fixture-only".equals(model.body().path("version").asText()) || !"ACTIVE".equals(model.body().path("state").asText()))continue;
            var body=model.body();RecordChange.COMMON_FIELDS.forEach(body::remove);body.put("state","RETIRED");
            var p=object().put("type","model_version").put("id",model.id());p.set("body",body);
            assertThat(send(presenter,"ai_fixture",null,null,p).applied()).isTrue();
        }
        idle();
    }
    List<String> modelInventory(){return store.list("model_version",null).stream().map(r->r.body().toString()).toList();}
    Cookie cookie(String role){return new Cookie(CookieSessionService.COOKIE,sessions.createStation("ai-"+Ids.newId(),Set.of(role)).sessionId());}
    Receipt send(Cookie cookie,String type,String run,Long revision,ObjectNode body) throws Exception {return send(cookie,new Command("fp/1",Ids.newId(),type,run,revision,body));}
    Receipt send(Cookie cookie,Command command) throws Exception {
        var response=mvc.perform(post("/api/commands").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(command))).andExpect(status().isOk()).andReturn().getResponse();
        return json.readValue(response.getContentAsString(),Receipt.class);
    }
    Receipt auto(String type,ObjectNode body) throws Exception{return automation.submit(type,body,run,null).get(30,TimeUnit.SECONDS);}
    void idle(){for(int i=0;i<8;i++)bus.awaitIdle();}
    StoredRecord fixture(String type,ObjectNode body) throws Exception {
        String id=Ids.newId();var p=object().put("type",type).put("id",id);p.set("body",body);
        assertThat(send(presenter,"ai_fixture",type.equals("model_version")?null:run,null,p).applied()).isTrue();idle();return store.get(type,id).orElseThrow();
    }
    ObjectNode ref(StoredRecord r){return object().put("type",r.type()).put("id",r.id()).put("revision",r.revision());}
    ObjectNode request(StoredRecord r,String purpose){var p=object().put("purpose",purpose);p.set("target",ref(r));return p;}
    @Test void unavailableTrainingDoesNotRetainPrivateFreeTextInTheCommandLog()throws Exception {
        String phone="+919612345679";var request=object().put("model_kind","LEARNED_RANKER").put("reason","Call "+phone);
        var command=new Command("fp/1",Ids.newId(),"retrain_model",run,null,request);var receipt=send(analyst,command);
        assertThat(receipt.reasonCode()).isEqualTo("TRAINING_UNAVAILABLE");
        String stored=jdbc.queryForObject("SELECT payload FROM commands WHERE command_id = ?",String.class,command.commandId());
        assertThat(stored.contains(phone)).as("private training request text must not remain in durable command payload").isFalse();
    }
    @Test void actualServiceBeanUsesMissingModelAndNoNormalBand() {
        assertThat(learned.getIfAvailable()).isNotNull();
        var physics=Analysis.physics(List.of(),Outcome.PROVISIONAL_SINGLE,Qualification.SYNTHETIC_SCOPE_ONLY,
                List.of(new Candidate("UNKNOWN",List.of(),null,CandidateStatus.SURVIVES,null)),List.of(),List.of(),"Fixture","FIXTURE");
        assertThat(learned.getObject().order(new AnalysisInput(run,Ids.newId(),0,List.of(),new WindNow(270,3)),physics).learnedStatus()).isEqualTo(LearnedStatus.MODEL_MISSING);
        assertThat(normal.getIfAvailable()).isNotNull();
        assertThat(normal.getObject().expected("N2","SO2_ppb",0,new WindNow(270,3))).isEmpty();
    }
    @Test void automaticWordingIsSavedWithExactPublicTargetAndActor() throws Exception {
        var a=fixture("analysis",object().put("outcome","INVALID").put("explanation_text","No usable observations. Synthetic scope only."));
        var wordings=store.list("wording",run);
        assertThat(wordings).hasSize(1);
        var wording=wordings.get(0);
        assertThat(wording.createdBy()).isEqualTo("automation");
        assertThat(wording.body().path("target").toString()).isEqualTo(ref(a).toString());
        assertThat(wording.body().path("source").asText()).isEqualTo("TEMPLATE");
        assertThat(wording.body().path("text").asText()).isEqualTo("No usable observations. Synthetic scope only.");
    }
    @Test void targetReplayAndNewCommandsDoNotDuplicateWording() throws Exception {
        var a=fixture("analysis",object().put("explanation_text","No usable observations."));
        var p=request(a,"EXPLANATION");
        assertThat(auto("write_wording",p).applied()).isTrue();
        assertThat(auto("write_wording",p).applied()).isTrue();
        events.publishEvent(new RecordSaved(a.type(),a.id(),a.revision(),run,"fixture",false));idle();
        assertThat(store.list("wording",run)).hasSize(1);
    }
    @Test void oldExactTargetRevisionIsNotReplacedByLaterText() throws Exception {
        var a=fixture("analysis",object().put("explanation_text","Original 12.5 ppb, limited synthetic evidence."));
        var p=object().put("type",a.type()).put("id",a.id());p.set("body",object().put("explanation_text","Later 999 ppb."));
        assertThat(send(presenter,"ai_fixture",run,null,p).applied()).isTrue();idle();
        assertThat(auto("write_wording",request(a,"EXPLANATION")).applied()).isTrue();
        var old=store.list("wording",run).stream().filter(r->r.body().path("target").path("revision").asInt()==1).findFirst().orElseThrow();
        assertThat(old.body().path("text").asText()).contains("12.5").doesNotContain("999");
        assertThat(store.list("wording",run)).hasSize(2);
    }
    @ParameterizedTest @ValueSource(strings={"presenter","analyst","case_reviewer","guest"})
    void wordingIsAutomationOnly(String role) throws Exception {
        var a=fixture("analysis",object().put("explanation_text","Public evidence."));
        var result=send(cookie(role),"write_wording",run,null,request(a,"EXPLANATION"));
        assertThat(result.reasonCode()).isEqualTo("NOT_ALLOWED");
    }
    @ParameterizedTest @ValueSource(strings={"contact","reading","run","model_version","notification","wording"})
    void privateOrUnsupportedTargetTypesAreRefused(String type) throws Exception {
        var p=object().put("purpose","EXPLANATION");p.set("target",object().put("type",type).put("id",Ids.newId()).put("revision",1));
        assertThat(auto("write_wording",p).reasonCode()).isEqualTo("INVALID");
    }
    @Test void crossRunTargetIsRefused() throws Exception {
        var a=fixture("analysis",object().put("explanation_text","Public evidence."));
        var result=automation.submit("write_wording",request(a,"EXPLANATION"),Ids.newId(),null).get(30,TimeUnit.SECONDS);
        assertThat(result.reasonCode()).isEqualTo("INVALID");
    }
    @Test void missingOrFractionalRevisionIsRefused() throws Exception {
        var a=fixture("analysis",object().put("explanation_text","Public evidence."));
        for(JsonNode revision:List.of(NullNode.instance,DoubleNode.valueOf(1.5),LongNode.valueOf(0))){
            var p=request(a,"EXPLANATION");((ObjectNode)p.path("target")).set("revision",revision);
            assertThat(auto("write_wording",p).reasonCode()).isEqualTo("INVALID");
        }
    }
    @Test void payloadCannotInjectPublicTextOrChangeTargetStatus() throws Exception {
        var a=fixture("analysis",object().put("outcome","INVALID").put("explanation_text","No usable data."));
        var p=request(a,"EXPLANATION").put("public_text","Secret +919812345678 guilty S02");
        assertThat(auto("write_wording",p).reasonCode()).isEqualTo("INVALID");
        assertThat(store.get(a.type(),a.id()).orElseThrow().body().path("outcome").asText()).isEqualTo("INVALID");
        assertThat(store.list("wording",run).toString()).doesNotContain("9812345678","guilty");
    }
    @Test void wrongPurposeForTargetIsRefused() throws Exception {
        var a=fixture("analysis",object().put("explanation_text","No usable data."));
        assertThat(auto("write_wording",request(a,"FINDING_SUMMARY")).reasonCode()).isEqualTo("INVALID");
    }
    @ParameterizedTest @ValueSource(strings={"presenter","analyst"})
    void retrainingIsHonestlyUnavailableAndCreatesNoModel(String role) throws Exception {
        var p=object().put("model_kind","LEARNED_RANKER").put("reason","Manual assessment");
        var c=new Command("fp/1",Ids.newId(),"retrain_model",run,null,p);
        var result=send(cookie(role),c);
        assertThat(result.reasonCode()).isEqualTo("TRAINING_UNAVAILABLE");
        assertThat(result.reasonText()).contains("no model", "lessons");
        assertThat(modelInventory()).isEqualTo(modelsBefore);
    }
    @Test void retrainingRefusesUnspecifiedKindsAndWrongRoles() throws Exception {
        assertThat(send(guest,"retrain_model",run,null,object().put("model_kind","LEARNED_RANKER").put("reason","test")).reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(send(presenter,"retrain_model",run,null,object().put("model_kind","NORMAL_AIR").put("reason","test")).reasonCode()).isEqualTo("INVALID");
    }
    @Test void disabledAutomaticRetrainingIsRefused() throws Exception {
        assertThat(auto("retrain_model",object().put("model_kind","LEARNED_RANKER").put("reason","test")).reasonCode()).isEqualTo("AUTOMATION_DISABLED");
        assertThat(modelInventory()).isEqualTo(modelsBefore);
    }
    @Test void unavailableRetrainingPreservesActiveParentAndExactRetryReceipt() throws Exception {
        var parent=fixture("model_version",object().put("state","ACTIVE").put("model_kind","LEARNED_RANKER").put("version","fixture-only"));
        var c=new Command("fp/1",Ids.newId(),"retrain_model",run,null,object().put("model_kind","LEARNED_RANKER").put("reason","Manual assessment"));
        var first=send(analyst,c);var retry=send(analyst,c);
        assertThat(first.reasonCode()).isEqualTo("TRAINING_UNAVAILABLE");
        assertThat(json.valueToTree(first).toString()).isEqualTo(json.valueToTree(retry).toString());
        assertThat(store.get("model_version",parent.id()).orElseThrow().body()).isEqualTo(parent.body());
        assertThat(store.list("model_version",null)).hasSize(modelsBefore.size()+1);
    }
    @Test void missingNormalModelRefusesAnomalyEvenWithPayloadBand() throws Exception {
        var p=object().put("sensor","N2").put("channel","SO2_ppb").put("start_s",0).put("end_s",300).put("observed_mean",100).put("expected_low",0).put("expected_high",10);
        assertThat(auto("record_anomaly",p).reasonCode()).isEqualTo("MODEL_MISSING");
        assertThat(store.list("anomaly",run)).isEmpty();
    }
    @Test void sealedCaseAssessesOnceAndFreshAdapterReplayDoesNotRetryUnavailableTraining() throws Exception {
        assertThat(send(presenter,"change_automation",null,null,object().put("setting","auto_retrain").put("value",true)).applied()).isTrue();
        var c=fixture("case",object().put("state","SEALED").put("purpose","Controlled eligibility fixture"));
        var attempts=commandLog.automationEntries(run,1000).stream().filter(e->e.commandType().equals("retrain_model")).toList();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).receipt().reasonCode()).isEqualTo("TRAINING_UNAVAILABLE");
        assertThat(attempts.get(0).receipt().reasonText()).contains(c.id(),"SKIPPED","Exact case finding is unavailable");
        // Fresh listener instance only: this is durable adapter dedup, not process-restart evidence.
        var fresh=new AiAutomation(automation,store,settings,mode,commandLog);
        fresh.saved(new RecordSaved(c.type(),c.id(),c.revision(),run,c.createdBy(),false));idle();
        assertThat(commandLog.automationEntries(run,1000).stream().filter(e->e.commandType().equals("retrain_model"))).hasSize(1);
        assertThat(modelInventory()).isEqualTo(modelsBefore);
    }
    @Test void unsealedCaseDoesNotStartAutomaticTraining() throws Exception {
        assertThat(send(presenter,"change_automation",null,null,object().put("setting","auto_retrain").put("value",true)).applied()).isTrue();
        fixture("case",object().put("state","FINDING"));
        assertThat(commandLog.automationEntries(run,1000).stream().filter(e->e.commandType().equals("retrain_model"))).isEmpty();
    }
    @Test void activeNormalMetadataStillRefusesUnverifiedAnomaly() throws Exception {
        var parent=fixture("model_version",object().put("state","ACTIVE").put("model_kind","NORMAL_AIR").put("version","fixture-only"));
        var result=auto("record_anomaly",object().put("sensor","N2").put("channel","SO2_ppb"));
        assertThat(result.reasonCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(result.reasonText()).contains("ARTIFACT_METADATA_MISSING");
        assertThat(store.list("anomaly",run)).isEmpty();
        assertThat(store.get("model_version",parent.id()).orElseThrow().body()).isEqualTo(parent.body());
    }
    @Test void manualSendWaitsForItsOrdinaryWordingInsteadOfSendingWithoutAnAuditRecord()throws Exception {
        testMode.active=false;
        var sign=object().put("phone_number","+919612345670").put("consent",true).put("consent_text_version","FP-CONSENT-1");
        sign.putArray("area_ids").add("AREA-E");assertThat(send(guest,"subscribe_alerts",null,null,sign).applied()).isTrue();
        var body=object().put("decision","MORE_INVESTIGATION");body.putObject("notice").putArray("area_ids").add("AREA-E");
        var decision=fixture("board_decision",body);assertThat(store.list("wording",run)).isEmpty();
        var request=object().put("kind","BOARD_UPDATE");request.putArray("area_ids").add("AREA-E");request.set("cause",ref(decision));
        var receipt=send(presenter,"send_alert",run,null,request);
        assertThat(receipt.reasonCode()).isEqualTo("WORDING_PENDING");
        assertThat(store.list("notification",run)).isEmpty();
        testMode.active=true;
    }
    @Test void startupPreparesHistoricalSmsRevisionsWithoutSendingOrDuplicating() throws Exception {
        // Simulate public history written while automatic services were absent; no process-restart claim.
        testMode.active=false;
        var first=fixture("board_decision",object().put("decision","MORE_INVESTIGATION"));
        var next=object().put("type","board_decision").put("id",first.id());
        next.set("body",object().put("decision","NO_SUPPORTED_SOURCE"));
        assertThat(send(presenter,"ai_fixture",run,null,next).applied()).isTrue();idle();
        assertThat(store.list("wording",run)).isEmpty();testMode.active=true;
        var ready=new org.springframework.boot.context.event.ApplicationReadyEvent(
                new org.springframework.boot.SpringApplication(fuelprint.FuelPrintApplication.class),new String[0],context,java.time.Duration.ZERO);
        events.publishEvent(ready);idle();
        var wordings=store.list("wording",run);assertThat(wordings).hasSize(2);
        assertThat(wordings.stream().map(r->r.body().path("target").path("revision").asLong())).containsExactlyInAnyOrder(1L,2L);
        assertThat(wordings).allSatisfy(r->assertThat(r.body().path("purpose").asText()).isEqualTo("SMS_BOARD_UPDATE"));
        assertThat(store.list("notification",run)).isEmpty();
        long before=commandLog.automationEntries(run,1000).stream().filter(e->e.commandType().equals("write_wording")).count();
        events.publishEvent(ready);idle();assertThat(store.list("wording",run)).isEqualTo(wordings);
        assertThat(commandLog.automationEntries(run,1000).stream().filter(e->e.commandType().equals("write_wording")).count()).isEqualTo(before);
    }
    static class TestMode implements ServerMode {
        volatile boolean active=true;
        public boolean isReplay(){return false;}
        public boolean automationActive(){return active;}
    }
    @TestConfiguration static class Fixtures {
        @Bean @org.springframework.context.annotation.Primary TestMode aiTestMode(){return new TestMode();}
        @Bean CommandHandler publicAiFixture(){return new CommandHandler(){
            public String commandType(){return "ai_fixture";}
            public Set<Role> allowedRoles(){return Set.of(Role.PRESENTER);}
            public HandlerOutcome handle(Command c,Session s,StoreView view){
                var p=c.payload();String type=p.path("type").asText(),id=p.path("id").asText();
                var previous=view.get(type,id).orElse(null);var body=(ObjectNode)p.path("body");
                var change=previous==null?RecordChange.create(type,id,c.runId(),body):RecordChange.next(previous,body);
                return HandlerResult.builder().save(change,"CONTROLLED_AI_FIXTURE",null).build();
            }
        };}
    }
    static ObjectNode object(){return JsonNodeFactory.instance.objectNode();}
}
