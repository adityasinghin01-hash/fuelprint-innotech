package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.api.ServerMode;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.*;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** Explicit public-record fixtures supplied only through a test command and the real bus. */
@SpringBootTest(properties={"fp.loop.auto=false","fp.field.safety-loop=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("real") @AutoConfigureMockMvc @Import(CasesWorkflowTest.Config.class)
class CasesWorkflowTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-cases-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r){r.add("fp.data-dir",DATA::toString);}
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired RecordStore store;
    @Autowired CommandBus bus; @Autowired CookieSessionService sessions;
    @Autowired AutomationBus automation; @Autowired TestMode mode;
    @org.springframework.test.context.bean.override.convention.TestBean(name="clockControl",methodName="testClock") ClockControl clock;
    String run; Cookie presenter,reviewer,inspector,lab,other;
    static ClockControl testClock(){return new ClockControl(){public double simTimeS(){return 42.5;}public double timeScale(){return 1;}public void requestTimeScale(String r,double s){}public void releaseTimeScale(String r){}};}
    static String id(){return UUID.randomUUID().toString();}
    ObjectNode obj(){return json.createObjectNode();}
    Cookie cookie(String station,String... roles){return new Cookie(CookieSessionService.COOKIE,sessions.createStation(station,Set.of(roles)).sessionId());}
    @BeforeEach void fresh() throws Exception {
        bus.awaitIdle();mode.active=false;presenter=cookie("presenter","presenter");reviewer=cookie("cases","case_reviewer");
        inspector=cookie("collector","inspector_demo");lab=cookie("lab","lab_demo");other=cookie("other","inspector_demo");
        var r=send("reset_run",obj().put("preset_id","SC1-NIGHT-PLUME").put("mode","TEACHING"),null,presenter);
        assertThat(r.path("status").asText()).isEqualTo("APPLIED");
        run=store.listAll("run").stream().filter(x->x.body().path("state").asText().equals("RUNNING")).reduce((a,b)->b).orElseThrow().id();
    }
    JsonNode send(String type,ObjectNode p,Long rev,Cookie who) throws Exception {return send(new Command("fp/1",id(),type,run,rev,p),who);}
    JsonNode send(Command c,Cookie who) throws Exception {
        var response=mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c))).andExpect(status().isOk()).andReturn().getResponse();
        bus.awaitIdle();var r=json.readTree(response.getContentAsString());System.out.println("CASES_RECEIPT "+r);return r;
    }
    String seed(String type,ObjectNode b) throws Exception{return seed(type,b,run);}
    String seed(String type,ObjectNode b,String runId) throws Exception {
        String key=id();var p=obj().put("type",type).put("id",key);p.set("fields",b);
        assertThat(send(new Command("fp/1",id(),"test_cases_fixture",runId,null,p),presenter).path("status").asText()).isEqualTo("APPLIED");return key;
    }
    void update(StoredRecord r,ObjectNode b) throws Exception {
        RecordChange.COMMON_FIELDS.forEach(b::remove);var p=obj().put("type",r.type()).put("id",r.id()).put("based",r.revision());p.set("fields",b);
        assertThat(send("test_cases_fixture",p,null,presenter).path("status").asText()).isEqualTo("APPLIED");
    }
    String investigation() throws Exception{return seed("investigation",obj().put("state","EVIDENCE_BACK"));}
    String evidence(String inv,String decision) throws Exception {
        String survey=seed("survey",obj().put("investigation_id",inv));String mission=seed("mission",obj().put("survey_id",survey).put("state","COMPLETE"));
        return screening(mission,seed("attempt",obj().put("mission_id",mission).put("state","COMPLETE")),decision);
    }
    String screening(String mission,String attempt,String decision) throws Exception {
        var b=obj().put("mission_id",mission).put("attempt_id",attempt).put("channel","SO2_ppb").put("named_use","SYNTHETIC_ONLINE_SCREENING").put("decision",decision);
        b.putArray("reasons").add(decision.equals("VALID")?"WITHIN_TEACHING_SCOPE":"FLOW_OR_SCOPE_LIMIT");b.putObject("summary").putNull("mean_enhancement_ppb");return seed("screening_record",b);
    }
    ObjectNode intake(String... evidence){var p=obj().put("purpose","Teaching investigation only");p.set("screening_record_ids",json.valueToTree(List.of(evidence)));return p;}
    StoredRecord caseRecord(){return store.list("case",run).get(0);}
    StoredRecord specimen(){return store.list("specimen",run).get(0);}
    List<StoredRecord> custody(){return store.list("custody_event",run);}
    void createCase() throws Exception {assertThat(send("create_case",intake(evidence(investigation(),"VALID")),null,reviewer).path("status").asText()).isEqualTo("APPLIED");}
    ObjectNode material(){return obj().put("case_id",caseRecord().id()).put("site_id","S02").put("material","SYNTHETIC_COAL_COKE_LIKE").put("method_id","TEACH-FUEL-S-1").put("container","jar-1").put("seal_id","seal-1").put("mass_mg",50);}
    void createSpecimen() throws Exception {createCase();assertThat(send("create_specimen",material(),caseRecord().revision(),inspector).path("status").asText()).isEqualTo("APPLIED");}
    ObjectNode transfer(String holder,String seal,String... ex){var p=obj().put("specimen_id",specimen().id()).put("to_holder",holder).put("seal_state",seal).put("event_time_utc","2026-10-19T10:00:00.000Z");p.set("exceptions",json.valueToTree(List.of(ex)));return p;}
    @Test void intakeRetainsValidLimitedAndRejectedEvidenceWithoutChangingIt() throws Exception {
        String inv=investigation(),a=evidence(inv,"VALID"),b=evidence(inv,"LIMITED"),c=evidence(inv,"REJECTED");var original=store.get("screening_record",c).orElseThrow().body().toString();
        assertThat(send("create_case",intake(a,b,c),null,reviewer).path("status").asText()).isEqualTo("APPLIED");var saved=caseRecord().body();
        assertThat(saved.path("investigation_id").asText()).isEqualTo(inv);assertThat(saved.path("state").asText()).isEqualTo("INTAKE");assertThat(saved.path("screening_record_ids")).hasSize(3);assertThat(saved.path("screening_refs")).hasSize(3);
        assertThat(saved.path("limitations").toString()).contains("LIMITED","REJECTED","FLOW_OR_SCOPE_LIMIT");assertThat(saved.path("origin").asText()).isEqualTo("SYNTHETIC_TEACHING");
        assertThat(store.get("screening_record",c).orElseThrow().body().toString()).isEqualTo(original);
    }
    @Test void intakeResolvesProposalAnalysisMissionGraph() throws Exception {
        String inv=investigation(),analysis=seed("analysis",obj().put("investigation_id",inv)),proposal=seed("proposal",obj().put("analysis_id",analysis));
        String mission=seed("mission",obj().put("proposal_id",proposal)),attempt=seed("attempt",obj().put("mission_id",mission));
        assertThat(send("create_case",intake(screening(mission,attempt,"REJECTED")),null,reviewer).path("status").asText()).isEqualTo("APPLIED");assertThat(caseRecord().body().path("investigation_id").asText()).isEqualTo(inv);
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","MIXED_RUN","MIXED_INVESTIGATION","ATTEMPT_MISMATCH","FOREIGN_ATTEMPT","FOREIGN_SURVEY","FOREIGN_ANALYSIS","TWO_BASES","UNKNOWN_DECISION","ORIGIN","DOMAIN","EMPTY","DUPLICATE","NULL","BAD_ID","NO_PURPOSE"})
    void invalidIntakeCannotCreateCase(String variant) throws Exception {
        String inv=investigation(),e=evidence(inv,"VALID");var p=intake(e);var r=store.get("screening_record",e).orElseThrow();
        switch(variant){
            case "MISSING"->p=intake(id());case "MIXED_RUN"->p=intake(e,seed("screening_record",r.body().without(RecordChange.COMMON_FIELDS),id()));
            case "MIXED_INVESTIGATION"->p=intake(e,evidence(investigation(),"VALID"));case "EMPTY"->p=intake();case "DUPLICATE"->p=intake(e,e);case "NULL"->p.putNull("screening_record_ids");case "BAD_ID"->p=intake("S02");case "NO_PURPOSE"->p.putNull("purpose");
            case "ATTEMPT_MISMATCH"->{var b=r.body().put("attempt_id",seed("attempt",obj().put("mission_id",id())));update(r,b);}
            case "FOREIGN_ATTEMPT"->{var b=r.body().put("attempt_id",seed("attempt",obj().put("mission_id",r.body().path("mission_id").asText()),id()));update(r,b);}
            case "FOREIGN_SURVEY"->{var m=store.get("mission",r.body().path("mission_id").asText()).orElseThrow();update(m,m.body().put("survey_id",seed("survey",obj().put("investigation_id",inv),id())));}
            case "FOREIGN_ANALYSIS"->{String a=seed("analysis",obj().put("investigation_id",inv),id()),pr=seed("proposal",obj().put("analysis_id",a));var m=store.get("mission",r.body().path("mission_id").asText()).orElseThrow();var b=m.body().put("proposal_id",pr);b.remove("survey_id");update(m,b);}
            case "TWO_BASES"->{var m=store.get("mission",r.body().path("mission_id").asText()).orElseThrow();update(m,m.body().put("proposal_id",id()));}
            case "UNKNOWN_DECISION"->update(r,r.body().put("decision","PASS"));case "ORIGIN"->update(r,r.body().put("origin","PHYSICAL_BENCH"));case "DOMAIN"->update(r,r.body().put("domain","MATERIAL_ASSAY"));
        }
        assertThat(send("create_case",p,null,reviewer).path("status").asText()).as(variant).isEqualTo("REJECTED");assertThat(store.list("case",run)).isEmpty();
    }
    @Test void createCaseRetryAndWrongRoleAndAutomation() throws Exception {
        var p=intake(evidence(investigation(),"VALID"));var c=new Command("fp/1",id(),"create_case",run,null,p);var first=send(c,reviewer);
        assertThat(first.path("status").asText()).isEqualTo("APPLIED");assertThat(send(c,reviewer)).isEqualTo(first);assertThat(store.list("case",run)).hasSize(1);
        assertThat(send("create_case",p.put("actor","case_reviewer@cases"),null,inspector).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
    }
    @Test void specimenHasIndependentIdentityAuthorityInitialCustodyAndTime() throws Exception {
        createCase();var old=caseRecord();var p=material().put("authority_basis","POLICE").put("actor","lab_demo@lab");assertThat(send("create_specimen",p,old.revision(),inspector).path("status").asText()).isEqualTo("APPLIED");
        var s=specimen().body();assertThat(s.path("authority_basis").asText()).isEqualTo("INSPECTOR_DEMO");assertThat(s.path("current_holder").asText()).isEqualTo("inspector_demo@collector");assertThat(s.path("sealed_sim_time_s").asDouble()).isEqualTo(42.5);assertThat(s.path("sealed_sim_time_reason").isNull()).isTrue();
        assertThat(UUID.fromString(specimen().id()).version()).isEqualTo(4);assertThat(caseRecord().body().path("screening_record_ids").toString()).doesNotContain(specimen().id());assertThat(s.path("mass_mg").asDouble()).isEqualTo(50);
        assertThat(caseRecord().body().path("state").asText()).isEqualTo("SPECIMEN");assertThat(store.get("case",old.id(),old.revision()).orElseThrow().body().path("state").asText()).isEqualTo("INTAKE");
        assertThat(custody()).hasSize(1);var e=custody().get(0).body();assertThat(e.path("seal_state").asText()).isEqualTo("INTACT");assertThat(e.path("to_holder").asText()).isEqualTo("inspector_demo@collector");assertThat(e.path("previous_event_id").isNull()).isTrue();assertThat(e.path("exceptions")).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"BELOW","NULL_MASS","TEXT_MASS","NEGATIVE","MATERIAL","METHOD","MISSING_MATERIAL","MISSING_METHOD","SITE","CONTAINER","SEAL","MISSING_CASE","FOREIGN_CASE","DRAFT","UNKNOWN_STATE"})
    void invalidSpecimenLeavesCaseAndCustodyUnchanged(String variant) throws Exception {
        createCase();var p=material();switch(variant){case "BELOW"->p.put("mass_mg",49.999);case "NULL_MASS"->p.putNull("mass_mg");case "TEXT_MASS"->p.put("mass_mg","50");case "NEGATIVE"->p.put("mass_mg",-1);case "MATERIAL"->p.put("material","AIR");case "METHOD"->p.put("method_id","TEACH-AEROSOL-S-1");case "MISSING_MATERIAL"->p.remove("material");case "MISSING_METHOD"->p.remove("method_id");case "SITE"->p.put("site_id","S99");case "CONTAINER"->p.putNull("container");case "SEAL"->p.put("seal_id","");case "MISSING_CASE"->p.put("case_id",id());case "FOREIGN_CASE"->p.put("case_id",seed("case",obj().put("state","INTAKE"),id()));default->update(caseRecord(),caseRecord().body().put("state",variant));}
        var before=caseRecord();assertThat(send("create_specimen",p,null,inspector).path("status").asText()).as(variant).isEqualTo("REJECTED");assertThat(caseRecord()).isEqualTo(before);assertThat(store.list("specimen",run)).isEmpty();assertThat(custody()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"INTAKE","SPECIMEN","LAB","FINDING","SEALED"})
    void specimenAtOrAfterIntakePreservesForwardState(String state) throws Exception {createCase();update(caseRecord(),caseRecord().body().put("state",state));assertThat(send("create_specimen",material().put("mass_mg",100),caseRecord().revision(),inspector).path("status").asText()).isEqualTo("APPLIED");assertThat(caseRecord().body().path("state").asText()).isEqualTo(state.equals("INTAKE")?"SPECIMEN":state);}
    @Test void specimenStaleRetryAndRoleAreGuarded() throws Exception {createCase();var c=new Command("fp/1",id(),"create_specimen",run,caseRecord().revision(),material());var first=send(c,inspector);assertThat(first.path("status").asText()).isEqualTo("APPLIED");assertThat(send(c,inspector)).isEqualTo(first);assertThat(send("create_specimen",material(),1L,inspector).path("reason_code").asText()).isEqualTo("STALE_REVISION");assertThat(send("create_specimen",material(),null,reviewer).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");assertThat(store.list("specimen",run)).hasSize(1);assertThat(custody()).hasSize(1);}
    @Test void holderTransfersCollectorCourierLabWithImmutableHistory() throws Exception {
        createSpecimen();var initial=custody().get(0);var s1=specimen();var p=transfer("inspector_demo@courier","INTACT");var c=new Command("fp/1",id(),"append_custody",run,s1.revision(),p);var first=send(c,inspector);
        assertThat(first.path("status").asText()).isEqualTo("APPLIED");assertThat(send(c,inspector)).isEqualTo(first);assertThat(send("append_custody",p,s1.revision(),inspector).path("reason_code").asText()).isEqualTo("STALE_REVISION");
        assertThat(send("append_custody",transfer("lab_demo@lab","INTACT"),specimen().revision(),cookie("courier","inspector_demo")).path("status").asText()).isEqualTo("APPLIED");
        assertThat(specimen().body().path("current_holder").asText()).isEqualTo("lab_demo@lab");assertThat(custody()).hasSize(3);assertThat(custody().get(2).body().path("previous_event_id").asText()).isEqualTo(custody().get(1).id());assertThat(store.get("custody_event",initial.id()).orElseThrow()).isEqualTo(initial);assertThat(store.get("specimen",s1.id(),1).orElseThrow()).isEqualTo(s1);
    }
    @Test void otherHolderAndPayloadSpoofCannotTransfer() throws Exception {createSpecimen();var p=transfer("lab_demo@lab","INTACT").put("from_holder","inspector_demo@collector").put("actor","inspector_demo@collector");var before=specimen();assertThat(send("append_custody",p,null,other).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");assertThat(specimen()).isEqualTo(before);assertThat(custody()).hasSize(1);}
    @Test void multiRoleStationUsesItsActualHeldCustodianRole() throws Exception {createSpecimen();assertThat(send("append_custody",transfer("lab_demo@dual","INTACT"),null,inspector).path("status").asText()).isEqualTo("APPLIED");assertThat(send("append_custody",transfer("lab_demo@lab","INTACT"),null,cookie("dual","inspector_demo","lab_demo")).path("status").asText()).isEqualTo("APPLIED");assertThat(custody().get(2).body().path("from_holder").asText()).isEqualTo("lab_demo@dual");}
    @ParameterizedTest @ValueSource(strings={"BROKEN","MISSING"})
    void sealFailureRequiresAndRetainsMatchingException(String seal) throws Exception {createSpecimen();var p=transfer("lab_demo@lab",seal);assertThat(send("append_custody",p,null,inspector).path("status").asText()).isEqualTo("REJECTED");String ex=seal.equals("BROKEN")?"BROKEN_SEAL":"MISSING_SEAL";assertThat(send("append_custody",transfer("lab_demo@lab",seal,ex),null,other).path("status").asText()).isEqualTo("APPLIED");assertThat(custody().get(1).body().path("exceptions").toString()).contains(ex);assertThat(specimen().body().path("custody_exceptions").toString()).contains(ex);}
    @ParameterizedTest @ValueSource(strings={"RECIPIENT_ABSENT","REFUSED"})
    void absentOrRefusingRecipientDoesNotBecomeHolder(String ex) throws Exception {createSpecimen();assertThat(send("append_custody",transfer("lab_demo@lab","INTACT",ex),null,inspector).path("status").asText()).isEqualTo("APPLIED");assertThat(specimen().body().path("current_holder").asText()).isEqualTo("inspector_demo@collector");assertThat(custody().get(1).body().path("to_holder").asText()).isEqualTo("inspector_demo@collector");assertThat(custody().get(1).body().path("attempted_to_holder").asText()).isEqualTo("lab_demo@lab");}
    @Test void correctionAppendsReferenceAndDoesNotRewritePriorTransfer() throws Exception {createSpecimen();send("append_custody",transfer("lab_demo@lab","INTACT"),null,inspector);var previous=custody().get(1);var bytes=previous.body().toString();assertThat(send("append_custody",transfer("lab_demo@lab","BROKEN","CORRECTION","BROKEN_SEAL"),null,lab).path("status").asText()).isEqualTo("APPLIED");var correction=custody().get(2);assertThat(correction.body().path("previous_event_id").asText()).isEqualTo(previous.id());assertThat(correction.body().path("exceptions").toString()).contains("CORRECTION");assertThat(store.get("custody_event",previous.id()).orElseThrow().body().toString()).isEqualTo(bytes);assertThat(store.revisions("custody_event",previous.id())).hasSize(1);}
    @ParameterizedTest @ValueSource(strings={"TIME","NULL_TIME","TEXT_EXCEPTIONS","UNKNOWN_EXCEPTION","DUPLICATE_EXCEPTION","BAD_SEAL","NO_HOLDER","AUTOMATION_HOLDER","FOREIGN_SPECIMEN","BAD_CORRECTION_TARGET","SEALED_CASE"})
    void invalidCustodyDoesNotAppendOrAdvance(String variant) throws Exception {createSpecimen();var p=transfer("lab_demo@lab","INTACT");switch(variant){case "TIME"->p.put("event_time_utc","tomorrow");case "NULL_TIME"->p.putNull("event_time_utc");case "TEXT_EXCEPTIONS"->p.put("exceptions","LATE_TRANSFER");case "UNKNOWN_EXCEPTION"->p.putArray("exceptions").add("HOLDER_UNAVAILABLE");case "DUPLICATE_EXCEPTION"->p.putArray("exceptions").add("REFUSED").add("REFUSED");case "BAD_SEAL"->p.put("seal_state","OK");case "NO_HOLDER"->p.putNull("to_holder");case "AUTOMATION_HOLDER"->p.put("to_holder","automation");case "FOREIGN_SPECIMEN"->p.put("specimen_id",seed("specimen",specimen().body().without(RecordChange.COMMON_FIELDS),id()));case "BAD_CORRECTION_TARGET"->{p.putArray("exceptions").add("CORRECTION");p.put("previous_event_id",id());}case "SEALED_CASE"->update(caseRecord(),caseRecord().body().put("state","SEALED"));}var before=specimen();assertThat(send("append_custody",p,null,inspector).path("status").asText()).as(variant).isEqualTo("REJECTED");assertThat(specimen()).isEqualTo(before);assertThat(custody()).hasSize(1);}
    @Test void labCannotCreateAndReviewerCannotAppend() throws Exception {createSpecimen();assertThat(send("create_specimen",material(),null,lab).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");assertThat(send("append_custody",transfer("lab_demo@lab","INTACT"),null,reviewer).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");}
    @Test void oldRunDoesNotUseNewRunClockForSpecimenSeal() throws Exception {createCase();send("reset_run",obj().put("preset_id","SC2-BLOCKED-INLET").put("mode","TEACHING"),null,presenter);assertThat(send("create_specimen",material(),null,inspector).path("status").asText()).isEqualTo("APPLIED");assertThat(specimen().body().path("sealed_sim_time_s").isNull()).isTrue();assertThat(specimen().body().path("sealed_sim_time_reason").asText()).isNotBlank();}
    @Test void automationRefusedForEveryTaskFourCommand() throws Exception {
        createSpecimen(); mode.active=true;
        try {
            for (var pair : List.of(Map.entry("create_case", intake(caseRecord().body().path("screening_record_ids").get(0).asText())),
                    Map.entry("create_specimen", material()), Map.entry("append_custody", transfer("lab_demo@lab", "INTACT")))) {
                assertThat(automation.submit(pair.getKey(), pair.getValue().put("actor", "inspector_demo@collector"), run, null).join().reasonCode()).isEqualTo("NOT_ALLOWED");
            }
        } finally { mode.active=false; }
        assertThat(custody()).hasSize(1); assertThat(store.list("specimen", run)).hasSize(1);
    }
    @Test void explicitLateTransferRecordsActualRecorderAndRetainsException() throws Exception {
        createSpecimen(); var initial=custody().get(0);
        var p=transfer("lab_demo@lab", "INTACT", "LATE_TRANSFER");
        assertThat(send("append_custody", p, specimen().revision(), other).path("status").asText()).isEqualTo("APPLIED");
        var step=custody().get(1).body();
        assertThat(step.path("from_holder").asText()).isEqualTo("inspector_demo@collector");
        assertThat(step.path("recorded_by").asText()).isEqualTo("inspector_demo@other");
        assertThat(step.path("event_time_utc").asText()).isEqualTo(p.path("event_time_utc").asText());
        assertThat(step.path("recorded_utc").asText()).isNotEqualTo(p.path("event_time_utc").asText());
        assertThat(specimen().body().path("custody_exceptions").toString()).contains("LATE_TRANSFER");
        assertThat(store.get("custody_event", initial.id()).orElseThrow()).isEqualTo(initial);
    }
    @Test void custodyHistoryAndReceiptSurviveIndependentDatabasePoolAndStoreReopen() throws Exception {
        createSpecimen(); send("append_custody", transfer("lab_demo@lab", "BROKEN", "BROKEN_SEAL"), null, inspector);
        var correction=new Command("fp/1", id(), "append_custody", run, specimen().revision(), transfer("lab_demo@lab", "BROKEN", "CORRECTION", "BROKEN_SEAL"));
        var receipt=send(correction, lab); assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");
        var expectedSpecimen=store.revisions("specimen", specimen().id()); var expectedCustody=custody();
        var expectedCase=store.revisions("case", caseRecord().id());
        // Genuine SQLite connections and RecordStore objects are closed/reopened. The test's Spring
        // context remains alive; this is deliberately a bounded persistence check, not process restart.
        for (int reopen=0; reopen<2; reopen++) {
            try (var pool=fuelprint.core.db.DatabaseConfig.create(new fuelprint.core.db.DataDirectory(DATA.toString()))) {
                var jdbc=new org.springframework.jdbc.core.JdbcTemplate(pool);
                var reopened=new RecordStore(jdbc, json, java.time.Clock.systemUTC());
                assertThat(reopened.revisions("specimen", specimen().id())).isEqualTo(expectedSpecimen);
                assertThat(reopened.revisions("case", caseRecord().id())).isEqualTo(expectedCase);
                assertThat(reopened.list("custody_event", run)).isEqualTo(expectedCustody);
                for (var e : expectedCustody) assertThat(reopened.revisions("custody_event", e.id())).containsExactly(e);
                JsonNode savedReceipt=json.valueToTree(new CommandLog(jdbc,json,java.time.Clock.systemUTC()).receipt(correction.commandId()).orElseThrow());
                // Round-trip both JSON trees: Jackson may represent revision 1 as LongNode or IntNode.
                assertThat(json.readTree(json.writeValueAsBytes(savedReceipt)))
                        .isEqualTo(json.readTree(json.writeValueAsBytes(receipt)));
            }
        }
        assertThat(send(correction,lab)).isEqualTo(receipt); assertThat(custody()).isEqualTo(expectedCustody);
    }
    @ParameterizedTest @ValueSource(strings={"49.99999999999999999999999999","49.999999999999999"})
    void exactSubMinimumMassCannotRoundUpIntoAnAcceptedSpecimen(String literal)throws Exception {
        createCase();var before=caseRecord();
        var p=material().put("mass_mg",new java.math.BigDecimal(literal));
        var receipt=send("create_specimen",p,before.revision(),inspector);
        assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");
        assertThat(store.list("specimen",run)).isEmpty();assertThat(store.list("custody_event",run)).isEmpty();
        assertThat(caseRecord()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"50.00000000000000000000001","100.0000000000000001"})
    void acceptedSpecimenMassKeepsEverySuppliedDecimalDigit(String literal)throws Exception {
        createCase();var p=material().put("mass_mg",new java.math.BigDecimal(literal));
        assertThat(send("create_specimen",p,caseRecord().revision(),inspector).path("status").asText()).isEqualTo("APPLIED");
        assertThat(specimen().body().path("mass_mg").decimalValue()).isEqualByComparingTo(new java.math.BigDecimal(literal));
    }
    static class TestMode implements ServerMode { volatile boolean active; public boolean isReplay(){return false;} public boolean automationActive(){return active;} }
    Command privateTextCommand(String field) throws Exception {
        String phone="Call +919612345678";
        if(field.equals("purpose"))return new Command("fp/1",id(),"create_case",run,null,intake(evidence(investigation(),"VALID")).put("purpose",phone));
        createCase();var p=material().put(field,phone);return new Command("fp/1",id(),"create_specimen",run,caseRecord().revision(),p);
    }
    @ParameterizedTest @ValueSource(strings={"purpose","container","seal_id"})
    void caseFormsRefuseContactTextBeforeSavingPublicEvidence(String field)throws Exception {
        var command=privateTextCommand(field);var receipt=send(command,field.equals("purpose")?reviewer:inspector);
        assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");
        assertThat(receipt.path("record_refs")).isEmpty();
        assertThat(store.list(field.equals("purpose")?"case":"specimen",run)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"purpose","container","seal_id"})
    void rejectedCaseFormContactTextDoesNotRemainInTheCommandPayload(String field)throws Exception {
        var command=privateTextCommand(field);send(command,field.equals("purpose")?reviewer:inspector);
        String stored=jdbc.queryForObject("SELECT payload FROM commands WHERE command_id = ?",String.class,command.commandId());
        assertThat(stored.contains("+919612345678")).as("contact text absent from retained payload").isFalse();
    }
    @TestConfiguration static class Config {
        @Bean @Primary TestMode casesMode(){return new TestMode();}
        @Bean CommandHandler casesFixture(){return new CommandHandler(){public String commandType(){return "test_cases_fixture";}public Set<Role> allowedRoles(){return Set.of(Role.PRESENTER);}public HandlerOutcome handle(Command c,Session s,StoreView v){var p=c.payload();return HandlerResult.builder().save(new RecordChange(p.path("type").asText(),p.path("id").asText(),c.runId(),p.path("based").asLong(0),(ObjectNode)p.path("fields")),"TEST_PUBLIC_RECORD_FIXTURE",0.0).build();}};}
    }
}
