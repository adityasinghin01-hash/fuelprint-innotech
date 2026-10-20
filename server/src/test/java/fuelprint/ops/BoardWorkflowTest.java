package fuelprint.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/** Human board + actual Alerts/AI handlers. Case bodies are controlled fixtures, not natural scenarios. */
@SpringBootTest(properties={"fp.loop.auto=false","fp.field.safety-loop=false","fp.alerts.retry-delay-ms=0"})
@AutoConfigureMockMvc @ActiveProfiles("real") @Import(BoardWorkflowTest.Fixtures.class)
class BoardWorkflowTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-board-workflow-");
    static final AtomicInteger PHONES=new AtomicInteger(1);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("fp.data-dir",DATA::toString);
        r.add("fp.alerts.sms-config",()->DATA.resolve("absent-sms-settings.json").toString());
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordStore store;
    @Autowired EventLog events;
    @Autowired CommandLog commands;
    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired CookieSessionService sessions;
    String run;
    Cookie board,presenter;
    @BeforeEach void setup() throws Exception {
        board=cookie("board");presenter=cookie("presenter");
        assertThat(send(presenter,"reset_run",null,null,object().put("preset_id","SC1-NIGHT-PLUME").put("mode","TEACHING")).applied()).isTrue();idle();
        run=store.listAll("run").stream().filter(r->"RUNNING".equals(r.body().path("state").asText())).findFirst().orElseThrow().id();
        for(String setting:List.of("auto_alerts","auto_retrain","auto_propose","auto_autopilot"))setting(setting,false);
    }
    Cookie cookie(String role){return new Cookie(CookieSessionService.COOKIE,sessions.createStation("board-fixture-"+Ids.newId(),Set.of(role)).sessionId());}
    Receipt send(Cookie who,String type,String run,Long revision,ObjectNode p)throws Exception{return send(who,new Command("fp/1",Ids.newId(),type,run,revision,p));}
    Receipt send(Cookie who,Command c)throws Exception {
        var r=mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c))).andExpect(status().isOk()).andReturn().getResponse();
        return json.readValue(r.getContentAsString(),Receipt.class);
    }
    void idle(){for(int i=0;i<10;i++)bus.awaitIdle();}
    void setting(String name,boolean on)throws Exception{assertThat(send(presenter,"change_automation",null,null,object().put("setting",name).put("value",on)).applied()).isTrue();idle();}
    StoredRecord fixture(String type,String id,String scope,ObjectNode fields)throws Exception {
        var p=object().put("type",type).put("id",id);p.set("body",fields);
        assertThat(send(presenter,"board_fixture",scope,null,p).applied()).isTrue();idle();return store.get(type,id).orElseThrow();
    }
    StoredRecord caseWithFinding()throws Exception { return caseWithFinding("INCONCLUSIVE"); }
    StoredRecord caseWithFinding(String outcome)throws Exception {
        String investigation=Ids.newId(),caseId=Ids.newId();
        var i=object().put("state","OPEN");i.putArray("warned_area_ids").add("AREA-E");
        fixture("investigation",investigation,run,i);
        var f=object().put("case_id",caseId).put("outcome",outcome).put("legal_suitability","NOT_ASSESSED");
        f.putArray("cited_refs");f.putArray("alternatives").add("Further sampling needed");f.putArray("limitations").add("Synthetic scope only");
        var finding=fixture("finding",Ids.newId(),run,f);
        var c=object().put("state","FINDING").put("investigation_id",investigation).put("purpose","Controlled board fixture");
        c.putArray("screening_record_ids");c.set("finding_ref",ref(finding));
        return fixture("case",caseId,run,c);
    }
    ObjectNode payload(StoredRecord c,String decision){
        var p=object().put("case_id",c.id()).put("decision",decision).put("rationale","Review of the synthetic evidence and its limits.");
        var n=p.putObject("notice").put("area","East colony").put("interval","Current simulation interval").put("status_text","Investigation continues.");n.putArray("area_ids").add("AREA-E");return p;
    }
    List<StoredRecord> notifications(String cause){return store.list("notification",null).stream().filter(r->cause.equals(r.body().path("cause").path("id").asText())).toList();}
    @ParameterizedTest @ValueSource(strings={"MORE_INVESTIGATION","NO_SUPPORTED_SOURCE","CANDIDATE_SUPPORTED_SIMULATED","SAMPLE_INVALID"})
    void allDeclaredHumanDecisionsPreserveExactFindingAndActor(String decision)throws Exception {
        String outcome=decision.equals("CANDIDATE_SUPPORTED_SIMULATED")?"SUPPORTED_WITHIN_SCOPE":decision.equals("SAMPLE_INVALID")?"REJECTED_FOR_USE":"INCONCLUSIVE";
        var c=caseWithFinding(outcome);var before=c.body();var command=new Command("fp/1",Ids.newId(),"record_board_decision",run,c.revision(),payload(c,decision));
        var receipt=send(board,command);assertThat(receipt.applied()).as(receipt.reasonText()).isTrue();idle();
        var decisions=store.list("board_decision",run);assertThat(decisions).hasSize(1);var saved=decisions.get(0);
        assertThat(saved.body().path("decision").asText()).isEqualTo(decision);
        assertThat(saved.body().path("case_ref").toString()).isEqualTo(ref(c).toString());
        assertThat(saved.body().path("finding_ref").toString()).isEqualTo(c.body().path("finding_ref").toString());
        assertThat(saved.createdBy()).startsWith("board@");assertThat(receipt.automatic()).isFalse();
        assertThat(events.forRecord("board_decision",saved.id())).singleElement().satisfies(e->assertThat(e.actor()).isEqualTo(saved.createdBy()));
        assertThat(store.get("case",c.id()).orElseThrow().body()).isEqualTo(before);
        assertThat(json.valueToTree(send(board,command)).toString()).isEqualTo(json.valueToTree(receipt).toString());
        assertThat(store.list("board_decision",run)).hasSize(1);assertThat(notifications(saved.id())).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"presenter","analyst","case_reviewer","guest","visitor"})
    void wrongRoleCannotClaimBoardIdentity(String role)throws Exception {
        var c=caseWithFinding();var p=payload(c,"MORE_INVESTIGATION").put("actor","board");
        assertThat(send(cookie(role),"record_board_decision",run,c.revision(),p).reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(store.list("board_decision",run)).isEmpty();
    }
    @Test void automationCannotMakeTheHumanBoardDecision()throws Exception {
        var c=caseWithFinding();var result=automation.submit("record_board_decision",payload(c,"MORE_INVESTIGATION"),run,c.revision()).get(30,TimeUnit.SECONDS);
        assertThat(result.reasonCode()).isEqualTo("NOT_ALLOWED");assertThat(store.list("board_decision",run)).isEmpty();
    }
    @Test void missingFindingRefusesWithoutRecordEventOrNotification()throws Exception {
        var c=fixture("case",Ids.newId(),run,object().put("state","INTAKE"));long cursor=events.lastCursor();
        var result=send(board,"record_board_decision",run,c.revision(),payload(c,"MORE_INVESTIGATION"));
        assertThat(result.applied()).isFalse();idle();assertThat(events.lastCursor()).isEqualTo(cursor);
        assertThat(store.list("board_decision",run)).isEmpty();
    }
    @Test void staleCaseSubjectIsRejected()throws Exception {
        var c=caseWithFinding();assertThat(send(board,"record_board_decision",run,999L,payload(c,"MORE_INVESTIGATION")).reasonCode()).isEqualTo("STALE_REVISION");
    }
    @Test void crossRunCaseCannotBeUsed()throws Exception {
        var c=caseWithFinding();assertThat(send(board,"record_board_decision",Ids.newId(),c.revision(),payload(c,"MORE_INVESTIGATION")).applied()).isFalse();
    }
    @Test void anotherCasesFindingCannotBeSubstituted()throws Exception {
        var c=caseWithFinding();var f=fixture("finding",Ids.newId(),run,object().put("case_id",Ids.newId()).put("outcome","INCONCLUSIVE"));
        var body=c.body();RecordChange.COMMON_FIELDS.forEach(body::remove);body.set("finding_ref",ref(f));c=fixture("case",c.id(),run,body);
        assertThat(send(board,"record_board_decision",run,c.revision(),payload(c,"MORE_INVESTIGATION")).applied()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"S01","s06","S01+S02","SourceS02"})
    void publicNoticeNeverNamesASourceInAnyTextField(String source)throws Exception {
        var c=caseWithFinding();
        for(String field:List.of("area","interval","status_text")){
            var p=payload(c,"MORE_INVESTIGATION");((ObjectNode)p.path("notice")).put(field,"Notice about "+source);
            assertThat(send(board,"record_board_decision",run,c.revision(),p).applied()).isFalse();
        }
        assertThat(store.list("board_decision",run)).isEmpty();
    }
    @Test void unknownAreaAndUnknownDecisionAreRefused()throws Exception {
        var c=caseWithFinding();var p=payload(c,"MORE_INVESTIGATION");((ObjectNode)p.path("notice")).putArray("area_ids").add("AREA-UNKNOWN");
        assertThat(send(board,"record_board_decision",run,c.revision(),p).applied()).isFalse();
        assertThat(send(board,"record_board_decision",run,c.revision(),payload(c,"GUILTY")).applied()).isFalse();
    }
    @Test void omittedAreasDefaultOnlyToRecordedInvestigationWarningAreas()throws Exception {
        var c=caseWithFinding();var p=payload(c,"MORE_INVESTIGATION");((ObjectNode)p.path("notice")).remove("area_ids");
        var result=send(board,"record_board_decision",run,c.revision(),p);assertThat(result.applied()).as(result.reasonText()).isTrue();
        assertThat(store.list("board_decision",run).get(0).body().path("notice").path("area_ids").toString()).isEqualTo("[\"AREA-E\"]");
    }
    @Test void boardEventCreatesLogOnlyNotificationWithExactPersistedWordingAndNoPhoneLeak()throws Exception {
        var c=caseWithFinding();String phone=String.format("+9198%08d",PHONES.getAndIncrement());
        var sign=object().put("phone_number",phone).put("consent",true).put("consent_text_version","FP-CONSENT-1");sign.putArray("area_ids").add("AREA-E");
        assertThat(send(cookie("guest"),"subscribe_alerts",null,null,sign).applied()).isTrue();setting("auto_alerts",true);
        var result=send(board,"record_board_decision",run,c.revision(),payload(c,"NO_SUPPORTED_SOURCE"));assertThat(result.applied()).as(result.reasonText()).isTrue();idle();
        var decision=store.list("board_decision",run).get(0);var alerts=notifications(decision.id());assertThat(alerts).hasSize(1);var n=alerts.get(0).body();
        assertThat(n.path("gateway_mode").asText()).isEqualTo("LOG_ONLY");assertThat(n.path("recipients").asInt()).isGreaterThanOrEqualTo(1);
        // Existing Alerts counts LOGGED_ONLY successes in sent; gateway_mode distinguishes physical delivery.
        assertThat(n.path("sent").asInt()).isEqualTo(n.path("recipients").asInt());assertThat(n.path("text").asText()).contains("no source was confirmed");
        assertThat(n.path("wording_id").isTextual()).isTrue();var wording=store.get("wording",n.path("wording_id").asText()).orElseThrow();
        assertThat(wording.body().path("text")).isEqualTo(n.path("text"));assertThat(wording.body().path("target").toString()).isEqualTo(ref(decision).toString());
        assertThat(alerts.get(0).createdBy()).isEqualTo("automation");
        assertThat(commands.automationEntries(run,1000).stream().filter(e->e.commandType().equals("write_wording")&&e.receipt().recordRefs().stream().anyMatch(r->r.id().equals(wording.id())))).hasSize(1);
        for(String type:List.of("case","finding","board_decision","notification","wording","alert_subscription"))
            assertThat(store.listAll(type).toString()).doesNotContain(phone,phone.substring(3));
    }
    @Test void existingBoardEventPathNeedsAnOrdinarySavedWordingBeforeSms()throws Exception {
        String phone=String.format("+9198%08d",PHONES.getAndIncrement());
        var sign=object().put("phone_number",phone).put("consent",true).put("consent_text_version","FP-CONSENT-1");sign.putArray("area_ids").add("AREA-E");
        assertThat(send(cookie("guest"),"subscribe_alerts",null,null,sign).applied()).isTrue();setting("auto_alerts",true);
        var body=object().put("case_id",Ids.newId()).put("decision","MORE_INVESTIGATION").put("rationale","Controlled pre-existing board event");
        body.putObject("notice").putArray("area_ids").add("AREA-E");
        var decision=fixture("board_decision",Ids.newId(),run,body);var alerts=notifications(decision.id());
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).body().path("wording_id").isTextual()).as("actual pre-existing event path must persist ordinary wording before send").isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"rationale","notice"})
    void refusedPrivateBoardTextDoesNotSurviveInTheCommandLog(String field)throws Exception {
        var c=caseWithFinding();String phone="+919612345678";var p=payload(c,"MORE_INVESTIGATION");
        if(field.equals("rationale"))p.put("rationale","Call "+phone);else ((ObjectNode)p.path("notice")).put("status_text","Call "+phone);
        var command=new Command("fp/1",Ids.newId(),"record_board_decision",run,c.revision(),p);
        assertThat(send(board,command).applied()).isFalse();idle();
        String stored=jdbc.queryForObject("SELECT payload FROM commands WHERE command_id = ?",String.class,command.commandId());
        assertThat(stored.contains(phone)).as("refused private text must be absent from durable command payload").isFalse();
        assertThat(store.list("board_decision",run)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"BOARD_UPDATE","SMOKE_WARNING"})
    void manualAlertsWithAutomationOffUseAnOrdinarySavedWording(String kind)throws Exception {
        String phone=String.format("+9198%08d",PHONES.getAndIncrement());
        var sign=object().put("phone_number",phone).put("consent",true).put("consent_text_version","FP-CONSENT-1");sign.putArray("area_ids").add("AREA-E");
        assertThat(send(cookie("guest"),"subscribe_alerts",null,null,sign).applied()).isTrue();
        var c=caseWithFinding();StoredRecord cause;
        if(kind.equals("BOARD_UPDATE")){
            assertThat(send(board,"record_board_decision",run,c.revision(),payload(c,"MORE_INVESTIGATION")).applied()).isTrue();idle();cause=store.list("board_decision",run).get(0);
        }else cause=store.get("investigation",c.body().path("investigation_id").asText()).orElseThrow();
        assertThat(notifications(cause.id())).isEmpty();
        var request=object().put("kind",kind);request.putArray("area_ids").add("AREA-E");request.set("cause",ref(cause));
        assertThat(send(presenter,"send_alert",run,null,request).applied()).isTrue();idle();
        var saved=notifications(cause.id());assertThat(saved).hasSize(1);var n=saved.get(0).body();
        assertThat(n.path("gateway_mode").asText()).isEqualTo("LOG_ONLY");assertThat(n.path("sent").asInt()).isGreaterThan(0);
        assertThat(n.path("wording_id").isTextual()).as("manual SMS must refer to persisted ordinary wording too").isTrue();
        var wording=store.get("wording",n.path("wording_id").asText()).orElseThrow();assertThat(wording.body().path("text")).isEqualTo(n.path("text"));
        assertThat(commands.automationEntries(run,1000).stream().filter(entry->entry.commandType().equals("write_wording")&&entry.receipt().recordRefs().stream().anyMatch(r->r.id().equals(wording.id())))).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"Call 98123.45678","Call +91.98123.45678","Call +1 (202) 555-0123","Call ＋９１９６１２３４５６７８"})
    void boardTextRefusesContactFormatsInEveryPublicField(String contact)throws Exception {
        var c=caseWithFinding();
        for(String field:List.of("rationale","area","interval","status_text")){
            var p=payload(c,"MORE_INVESTIGATION");
            if(field.equals("rationale"))p.put(field,contact);else ((ObjectNode)p.path("notice")).put(field,contact);
            var command=new Command("fp/1",Ids.newId(),"record_board_decision",run,c.revision(),p);
            assertThat(send(board,command).applied()).as("contact text refused before public write: "+field).isFalse();
            String retained=jdbc.queryForObject("SELECT payload FROM commands WHERE command_id = ?",String.class,command.commandId());
            assertThat(retained.contains(contact)).isFalse();
        }
        assertThat(store.list("board_decision",run)).isEmpty();
    }
    @Test void ordinaryIsoDatesAreAcceptedInBoardNoticeIntervals()throws Exception {
        var c=caseWithFinding();var p=payload(c,"MORE_INVESTIGATION");
        ((ObjectNode)p.path("notice")).put("interval","2026-10-22 to 2026-10-23");
        assertThat(send(board,"record_board_decision",run,c.revision(),p).applied()).isTrue();
        assertThat(store.list("board_decision",run).get(0).body().path("notice").path("interval").asText())
                .isEqualTo("2026-10-22 to 2026-10-23");
    }
    @TestConfiguration static class Fixtures {
        @Bean CommandHandler boardFixture(){return new CommandHandler(){
            public String commandType(){return "board_fixture";}
            public Set<Role> allowedRoles(){return Set.of(Role.PRESENTER);}
            public HandlerOutcome handle(Command c,Session s,StoreView store){
                var p=c.payload();String type=p.path("type").asText(),id=p.path("id").asText();var prior=store.get(type,id).orElse(null);var body=(ObjectNode)p.path("body");
                return HandlerResult.builder().save(prior==null?RecordChange.create(type,id,c.runId(),body):RecordChange.next(prior,body),"CONTROLLED_BOARD_FIXTURE",null).build();
            }
        };}
    }
    static ObjectNode object(){return JsonNodeFactory.instance.objectNode();}
    static ObjectNode ref(StoredRecord r){return object().put("type",r.type()).put("id",r.id()).put("revision",r.revision());}
}
