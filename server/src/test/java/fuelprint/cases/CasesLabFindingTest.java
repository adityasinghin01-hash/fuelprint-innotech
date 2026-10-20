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
@ActiveProfiles("real") @AutoConfigureMockMvc @Import(CasesLabFindingTest.Config.class)
class CasesLabFindingTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-cases-lab-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r){r.add("fp.data-dir",DATA::toString);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired RecordStore store;
    @Autowired CommandBus bus; @Autowired CookieSessionService sessions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired AutomationBus automation; @Autowired TestMode mode;
    @org.springframework.test.context.bean.override.convention.TestBean(name="clockControl",methodName="testClock") ClockControl clock;
    String run; Cookie presenter,reviewer,inspector,lab,other;
    static double now=42.5;
    static ClockControl testClock(){return new ClockControl(){public double simTimeS(){return now;}public double timeScale(){return 1;}public void requestTimeScale(String r,double s){}public void releaseTimeScale(String r){}};}
    static String id(){return UUID.randomUUID().toString();}
    ObjectNode obj(){return json.createObjectNode();}
    Cookie cookie(String station,String... roles){return new Cookie(CookieSessionService.COOKIE,sessions.createStation(station,Set.of(roles)).sessionId());}
    @BeforeEach void fresh() throws Exception {
        bus.awaitIdle();now=42.5;mode.active=false;presenter=cookie("presenter","presenter");reviewer=cookie("cases","case_reviewer");
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
        assertThat(send(new Command("fp/1",id(),"test_lab_fixture",runId,null,p),presenter).path("status").asText()).isEqualTo("APPLIED");return key;
    }
    void update(StoredRecord r,ObjectNode b) throws Exception {
        RecordChange.COMMON_FIELDS.forEach(b::remove);var p=obj().put("type",r.type()).put("id",r.id()).put("based",r.revision());p.set("fields",b);
        assertThat(send("test_lab_fixture",p,null,presenter).path("status").asText()).isEqualTo("APPLIED");
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
    ObjectNode material(){return obj().put("case_id",caseRecord().id()).put("site_id","S02").put("material","SYNTHETIC_COAL_COKE_LIKE").put("method_id","TEACH-FUEL-S-1").put("container","jar-1").put("seal_id","seal-1").put("mass_mg",100);}
    void createSpecimen() throws Exception {createCase();assertThat(send("create_specimen",material(),caseRecord().revision(),inspector).path("status").asText()).isEqualTo("APPLIED");}
    ObjectNode transfer(String holder,String seal,String... ex){var p=obj().put("specimen_id",specimen().id()).put("to_holder",holder).put("seal_state",seal).put("event_time_utc","2026-10-19T10:00:00.000Z");p.set("exceptions",json.valueToTree(List.of(ex)));return p;}
    ObjectNode qc(){return obj().put("gross_ug",2010).put("blank_ug",10).put("dup_a_pct",2.0).put("dup_b_pct",2.2).put("spike_added_ug",100).put("spike_recovered_ug",90);}
    ObjectNode labRequest(ObjectNode q){var p=obj().put("specimen_id",specimen().id()).put("method_id","TEACH-FUEL-S-1");p.set("qc_inputs",q);return p;}
    StoredRecord result(){return store.list("lab_result",run).get(0);}
    void analyze(ObjectNode q) throws Exception {assertThat(send("request_lab",labRequest(q),specimen().revision(),lab).path("status").asText()).isEqualTo("APPLIED");}
    ObjectNode finding(String outcome,StoredRecord... records){var p=obj().put("case_id",caseRecord().id()).put("outcome",outcome);var refs=p.putArray("cited_refs");for(var r:records)refs.add(obj().put("type",r.type()).put("id",r.id()).put("revision",r.revision()));p.putArray("alternatives");p.putArray("limitations").add("Synthetic observation only");return p;}
    StoredRecord screen(){return store.get("screening_record",caseRecord().body().path("screening_record_ids").get(0).asText()).orElseThrow();}
    @Test void referenceArithmeticRawLineageRetryAndState() throws Exception {
        createSpecimen();var oldCase=caseRecord();var oldSpec=specimen();var q=qc();var c=new Command("fp/1",id(),"request_lab",run,oldSpec.revision(),labRequest(q));var receipt=send(c,lab);
        assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");assertThat(send(c,lab)).isEqualTo(receipt);assertThat(store.list("lab_result",run)).hasSize(1);
        var b=result().body();assertThat(b.path("raw").path("gross_ug").asDouble()).isEqualTo(2010);assertThat(b.path("raw").path("mass_mg").asDouble()).isEqualTo(100);assertThat(b.path("qc_inputs")).isEqualTo(q);
        assertThat(b.path("computed").path("net_ug").asDouble()).isEqualTo(2000);assertThat(b.path("computed").path("percent_w_w").asDouble()).isEqualTo(2.0);
        assertThat(b.path("computed").path("rpd_pct").asDouble()).isCloseTo(9.523809523809524,org.assertj.core.data.Offset.offset(1e-12));assertThat(b.path("computed").path("recovery_pct").asDouble()).isEqualTo(90);
        assertThat(b.path("qc_status").asText()).isEqualTo("PASS");assertThat(b.path("reporting").asText()).isEqualTo("REPORTED");assertThat(b.path("specimen_ref").path("revision").asLong()).isEqualTo(oldSpec.revision());assertThat(b.path("case_ref").path("revision").asLong()).isEqualTo(oldCase.revision());
        assertThat(b.path("analysis_sim_time_s").asDouble()).isEqualTo(42.5);assertThat(b.path("hold_time_s").asDouble()).isEqualTo(0);assertThat(caseRecord().body().path("state").asText()).isEqualTo("LAB");assertThat(specimen()).isEqualTo(oldSpec);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"19.999,PASS","20,PASS","20.001,FAIL"})
    void blankBoundary(double blank,String status) throws Exception {createSpecimen();analyze(qc().put("blank_ug",blank));assertThat(result().body().path("qc_status").asText()).isEqualTo(status);}
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"1.800001,2.2,PASS","1.8,2.2,PASS","1.799999,2.2,FAIL"})
    void exactDecimalRpdBoundary(double a,double b,String status) throws Exception {createSpecimen();analyze(qc().put("dup_a_pct",a).put("dup_b_pct",b));assertThat(result().body().path("qc_status").asText()).isEqualTo(status);}
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"79.999,FAIL","80,PASS","80.001,PASS","119.999,PASS","120,PASS","120.001,FAIL"})
    void recoveryBoundaries(double recovery,String status) throws Exception {createSpecimen();analyze(qc().put("spike_recovered_ug",recovery));assertThat(result().body().path("qc_status").asText()).isEqualTo(status);}
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"299.999,PASS","300,PASS","300.001,FAIL"})
    void holdUsesRecordedSimulationTimes(double elapsed,String status) throws Exception {createSpecimen();now=42.5+elapsed;analyze(qc());assertThat(result().body().path("hold_time_s").asDouble()).isCloseTo(elapsed,org.assertj.core.data.Offset.offset(1e-10));assertThat(result().body().path("qc_status").asText()).isEqualTo(status);}
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"19.999,BELOW_LOQ","20,REPORTED","20.001,REPORTED","9,BELOW_LOQ"})
    void reportingNeverErasesMeasuredNet(double gross,String reporting) throws Exception {createSpecimen();analyze(qc().put("gross_ug",gross));var b=result().body();assertThat(b.path("reporting").asText()).isEqualTo(reporting);assertThat(b.path("computed").path("net_ug").asDouble()).isCloseTo(gross-10,org.assertj.core.data.Offset.offset(1e-12));assertThat(b.path("computed").path("percent_w_w").asDouble()).isNotZero();}
    @ParameterizedTest @ValueSource(strings={"gross_ug","blank_ug","dup_a_pct","dup_b_pct","spike_added_ug","spike_recovered_ug"})
    void everyMissingQcInputIsPending(String field) throws Exception {createSpecimen();var q=qc();q.remove(field);analyze(q);assertThat(result().body().path("qc_status").asText()).isEqualTo("PENDING");assertThat(result().body().path("qc_reasons").toString()).contains(field);assertThat(result().body().path("raw").path(field).isNull()).isTrue();}
    @ParameterizedTest @ValueSource(strings={"DUP_ZERO","SPIKE_ZERO","UNKNOWN_TIME","FUTURE_SEAL","OLD_RUN","NULL_QC"})
    void undefinedValuesStayUnavailable(String variant) throws Exception {createSpecimen();var q=qc();String value="rpd_pct";
        switch(variant){case "DUP_ZERO"->{q.put("dup_a_pct",0).put("dup_b_pct",0);}case "SPIKE_ZERO"->{q.put("spike_added_ug",0);value="recovery_pct";}case "UNKNOWN_TIME"->{update(specimen(),specimen().body().putNull("sealed_sim_time_s").put("sealed_sim_time_reason","UNAVAILABLE"));value="hold_time_s";}case "FUTURE_SEAL"->{now=42.4;value="hold_time_s";}case "OLD_RUN"->{send("reset_run",obj().put("preset_id","SC2-BLOCKED-INLET").put("mode","TEACHING"),null,presenter);value="hold_time_s";}case "NULL_QC"->{var p=labRequest(q).putNull("qc_inputs");assertThat(send("request_lab",p,null,lab).path("status").asText()).isEqualTo("APPLIED");assertThat(result().body().path("qc_status").asText()).isEqualTo("PENDING");return;}}
        analyze(q);var b=result().body();assertThat(b.path("qc_status").asText()).isEqualTo("PENDING");assertThat(value.equals("hold_time_s")?b.path(value).isNull():b.path("computed").path(value).isNull()).isTrue();assertThat(b.path("qc_reasons")).isNotEmpty();
    }
    @Test void pendingStillRetainsEveryKnownFailure() throws Exception {createSpecimen();var q=qc().put("blank_ug",30).put("dup_a_pct",1).put("dup_b_pct",3);q.remove("spike_added_ug");now=343;analyze(q);var b=result().body();assertThat(b.path("qc_status").asText()).isEqualTo("PENDING");assertThat(b.path("qc_reasons").toString()).contains("BLANK","DUPLICATE","HOLD","spike_added_ug");assertThat(b.path("qc_checks").path("blank").path("status").asText()).isEqualTo("FAIL");}
    @ParameterizedTest @ValueSource(strings={"NEGATIVE","TEXT","SEALED","DRAFT","FOREIGN_SPECIMEN","METHOD","MATERIAL","MISSING_SPECIMEN"})
    void labInvalidRequestsCannotMutateCase(String variant) throws Exception {createSpecimen();var p=labRequest(qc());switch(variant){case "NEGATIVE"->((ObjectNode)p.path("qc_inputs")).put("gross_ug",-1);case "TEXT"->((ObjectNode)p.path("qc_inputs")).put("gross_ug","2010");case "METHOD"->p.put("method_id","OTHER");case "MATERIAL"->update(specimen(),specimen().body().put("material","AIR"));case "FOREIGN_SPECIMEN"->p.put("specimen_id",seed("specimen",specimen().body().without(RecordChange.COMMON_FIELDS),id()));case "MISSING_SPECIMEN"->p.put("specimen_id",id());default->update(caseRecord(),caseRecord().body().put("state",variant));}var before=caseRecord();var receipt=send("request_lab",p,null,lab);if(Set.of("NEGATIVE","TEXT").contains(variant)){assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");assertThat(result().body().path("qc_status").asText()).isEqualTo("FAIL");assertThat(result().body().path("computed").path("net_ug").isNull()).isTrue();}else{assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");assertThat(caseRecord()).isEqualTo(before);assertThat(store.list("lab_result",run)).isEmpty();}}
    @Test void labRoleStaleAndAutomationAreRefused() throws Exception {createSpecimen();var p=labRequest(qc());assertThat(send("request_lab",p,null,reviewer).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");assertThat(send("request_lab",p,0L,lab).path("reason_code").asText()).isEqualTo("STALE_REVISION");mode.active=true;try{assertThat(automation.submit("request_lab",p,run,null).join().reasonCode()).isEqualTo("NOT_ALLOWED");}finally{mode.active=false;}assertThat(store.list("lab_result",run)).isEmpty();}
    @Test void labPreservesLaterFindingStateAndCustodyLimitations() throws Exception {createSpecimen();send("append_custody",transfer("lab_demo@lab","BROKEN","BROKEN_SEAL"),null,inspector);update(caseRecord(),caseRecord().body().put("state","FINDING"));analyze(qc());assertThat(caseRecord().body().path("state").asText()).isEqualTo("FINDING");assertThat(result().body().path("limitations").toString()).contains("BROKEN_SEAL","plume");}
    @ParameterizedTest @ValueSource(strings={"SUPPORTED_WITHIN_SCOPE","REJECTED_FOR_USE","INCONCLUSIVE"})
    void threeHumanOutcomesPreserveExactCitations(String outcome) throws Exception {String e=evidence(investigation(),outcome.equals("REJECTED_FOR_USE")?"REJECTED":"VALID");assertThat(send("create_case",intake(e),null,reviewer).path("status").asText()).isEqualTo("APPLIED");var p=finding(outcome,screen());assertThat(send("revise_finding",p,caseRecord().revision(),reviewer).path("status").asText()).isEqualTo("APPLIED");var f=store.list("finding",run).get(0);assertThat(json.readTree(json.writeValueAsString(f.body().path("cited_refs")))).isEqualTo(json.readTree(json.writeValueAsString(p.path("cited_refs"))));assertThat(f.body().path("legal_suitability").asText()).isEqualTo("NOT_ASSESSED");assertThat(f.body().path("created_by").asText()).isEqualTo("case_reviewer@cases");assertThat(f.body().path("limitations").toString()).contains("plume");assertThat(caseRecord().body().path("finding_ref").path("id").asText()).isEqualTo(f.id());assertThat(caseRecord().body().path("finding_ref").path("revision").asLong()).isEqualTo(1);}
    @Test void passLabSupportsButDoesNotAssertPlumeAndFindingRevisionsStayLinked() throws Exception {createSpecimen();analyze(qc());var p=finding("SUPPORTED_WITHIN_SCOPE",result());var c=new Command("fp/1",id(),"revise_finding",run,caseRecord().revision(),p);var first=send(c,reviewer);assertThat(first.path("status").asText()).isEqualTo("APPLIED");assertThat(send(c,reviewer)).isEqualTo(first);var firstCase=caseRecord();var f=store.list("finding",run).get(0);var old=f.body().toString();assertThat(send("revise_finding",finding("INCONCLUSIVE"),caseRecord().revision(),reviewer).path("status").asText()).isEqualTo("APPLIED");assertThat(store.list("finding",run)).hasSize(1);assertThat(store.revisions("finding",f.id())).hasSize(2);assertThat(store.get("finding",f.id(),1).orElseThrow().body().toString()).isEqualTo(old);assertThat(store.get("case",firstCase.id(),firstCase.revision()).orElseThrow().body().path("finding_ref").path("revision").asLong()).isEqualTo(1);assertThat(caseRecord().body().path("finding_ref").path("revision").asLong()).isEqualTo(2);}
    @ParameterizedTest @ValueSource(strings={"SUPPORTED_EMPTY","REJECTED_EMPTY","LIMITED_SUPPORT","FAIL_LAB_SUPPORT","MISSING_LIMIT","MISSING_ALTERNATIVES","LEGAL","GUILTY","ILLEGAL","PROVED","COAL","CAUSATION","FOREIGN_CASE","FOREIGN_RUN","BAD_TYPE","MISSING_REV","UNKNOWN_REV","DUP_REFS","SEALED","DRAFT"})
    void invalidFindingRollsBackWithoutInventingEvidence(String variant) throws Exception {createSpecimen();var p=finding("SUPPORTED_WITHIN_SCOPE",screen());switch(variant){case "SUPPORTED_EMPTY"->p=finding("SUPPORTED_WITHIN_SCOPE");case "REJECTED_EMPTY"->p=finding("REJECTED_FOR_USE");case "LIMITED_SUPPORT"->{update(screen(),screen().body().put("decision","LIMITED"));p=finding("SUPPORTED_WITHIN_SCOPE",screen());}case "FAIL_LAB_SUPPORT"->{analyze(qc().put("blank_ug",30));p=finding("SUPPORTED_WITHIN_SCOPE",result());}case "MISSING_LIMIT"->p.putArray("limitations");case "MISSING_ALTERNATIVES"->{String a=seed("analysis",obj().put("investigation_id",caseRecord().body().path("investigation_id").asText()).put("outcome","AMBIGUOUS_OR_OPTIONAL_MIXTURE"));p.withArray("cited_refs").add(obj().put("type","analysis").put("id",a).put("revision",1));}case "LEGAL"->p.put("legal_suitability","NOT_ASSESSED");case "GUILTY","ILLEGAL","PROVED","COAL","CAUSATION"->p.withArray("limitations").add(switch(variant){case "GUILTY"->"GUILTY";case "ILLEGAL"->"illegal";case "PROVED"->"proved";case "COAL"->"coal used";default->"This specimen caused the plume";});case "FOREIGN_CASE"->{var e=evidence(investigation(),"VALID");p=finding("SUPPORTED_WITHIN_SCOPE",store.get("screening_record",e).orElseThrow());}case "FOREIGN_RUN"->{String a=seed("analysis",obj().put("investigation_id",caseRecord().body().path("investigation_id").asText()).put("outcome","PROVISIONAL_SINGLE"),id());p=finding("INCONCLUSIVE",store.get("analysis",a).orElseThrow());}case "BAD_TYPE"->((ObjectNode)p.path("cited_refs").get(0)).put("type","run");case "MISSING_REV"->((ObjectNode)p.path("cited_refs").get(0)).remove("revision");case "UNKNOWN_REV"->((ObjectNode)p.path("cited_refs").get(0)).put("revision",999);case "DUP_REFS"->p.withArray("cited_refs").add(p.path("cited_refs").get(0).deepCopy());default->update(caseRecord(),caseRecord().body().put("state",variant));}var before=caseRecord();assertThat(send("revise_finding",p,null,reviewer).path("status").asText()).as(variant).isEqualTo("REJECTED");assertThat(caseRecord()).isEqualTo(before);assertThat(store.list("finding",run)).isEmpty();}
    @Test void humanFindingRoleStaleAndAutomationCannotBeSpoofed() throws Exception {createCase();var p=finding("INCONCLUSIVE").put("actor","case_reviewer@cases");assertThat(send("revise_finding",p,null,lab).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");assertThat(send("revise_finding",p,0L,reviewer).path("reason_code").asText()).isEqualTo("STALE_REVISION");mode.active=true;try{assertThat(automation.submit("revise_finding",p,run,null).join().reasonCode()).isEqualTo("NOT_ALLOWED");}finally{mode.active=false;}assertThat(store.list("finding",run)).isEmpty();}
    @Test void inconclusiveAndAmbiguousAnalysisDoNotNeedPositiveFabrication() throws Exception {createCase();String a=seed("analysis",obj().put("investigation_id",caseRecord().body().path("investigation_id").asText()).put("outcome","AMBIGUOUS_OR_OPTIONAL_MIXTURE"));var p=finding("INCONCLUSIVE",store.get("analysis",a).orElseThrow());p.withArray("alternatives").add("Unresolved competing synthetic explanation");assertThat(send("revise_finding",p,null,reviewer).path("status").asText()).isEqualTo("APPLIED");}

    // ---- Task 5 additional coverage (written after the saved RED; each names the production rule it guards) ----
    ObjectNode ref(String type,String id,long revision){return obj().put("type",type).put("id",id).put("revision",revision);}
    ObjectNode findingWith(String outcome,ObjectNode... refs){var p=finding(outcome);for(var r:refs)p.withArray("cited_refs").add(r);return p;}
    List<String> texts(JsonNode array){var l=new ArrayList<String>();array.forEach(n->l.add(n.asText()));return l;}
    String st(JsonNode receipt){return receipt.path("status").asText();}
    String analysisFor(String outcome,String investigationId) throws Exception{return seed("analysis",obj().put("investigation_id",investigationId).put("outcome",outcome));}
    @Test void findingKeepsCitedHistoricalRevisionsAfterLaterEvidenceRevision() throws Exception {
        createSpecimen();analyze(qc());var oldScreen=screen();var oldLab=result();
        update(screen(),screen().body().put("decision","REJECTED"));update(result(),result().body().put("qc_status","FAIL"));
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("screening_record",oldScreen.id(),1)),null,reviewer))).isEqualTo("APPLIED");
        assertThat(store.list("finding",run).get(0).body().path("cited_refs").get(0).path("revision").asLong()).isEqualTo(1);
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("lab_result",oldLab.id(),1)),null,reviewer))).isEqualTo("APPLIED");
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("lab_result",oldLab.id(),2)),null,reviewer))).as("revision 2 FAILed QC").isEqualTo("REJECTED");
        assertThat(st(send("revise_finding",findingWith("INCONCLUSIVE",ref("screening_record",oldScreen.id(),2)),null,reviewer))).as("not the revision pinned by the case").isEqualTo("REJECTED");
        assertThat(store.get("lab_result",oldLab.id(),1).orElseThrow().body().path("qc_status").asText()).isEqualTo("PASS");
    }
    @Test void labResultFromAnotherCaseCannotBeCited() throws Exception {
        createSpecimen();String foreignSpecimen=seed("specimen",obj().put("case_id",id()));
        String foreignLab=seed("lab_result",obj().put("specimen_id",foreignSpecimen).put("qc_status","PASS"));
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("lab_result",foreignLab,1)),null,reviewer))).isEqualTo("REJECTED");
        assertThat(store.list("finding",run)).isEmpty();
    }
    @Test void failedLabIsARejectedItemButPendingLabIsNeitherSupportNorRejection() throws Exception {
        createSpecimen();analyze(qc().put("blank_ug",30));var failed=result();
        assertThat(failed.body().path("qc_status").asText()).isEqualTo("FAIL");
        {var q=qc();q.putNull("blank_ug");analyze(q);}var pending=store.list("lab_result",run).stream().filter(r->!r.id().equals(failed.id())).findFirst().orElseThrow();
        assertThat(pending.body().path("qc_status").asText()).isEqualTo("PENDING");
        assertThat(st(send("revise_finding",findingWith("REJECTED_FOR_USE",ref("lab_result",pending.id(),1)),null,reviewer))).isEqualTo("REJECTED");
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("lab_result",pending.id(),1)),null,reviewer))).isEqualTo("REJECTED");
        assertThat(st(send("revise_finding",findingWith("INCONCLUSIVE",ref("lab_result",pending.id(),1)),null,reviewer))).isEqualTo("APPLIED");
        assertThat(st(send("revise_finding",findingWith("REJECTED_FOR_USE",ref("lab_result",failed.id(),1)),null,reviewer))).isEqualTo("APPLIED");
    }
    @Test void limitedScreeningIsNeitherSupportNorRejection() throws Exception {
        String e=evidence(investigation(),"LIMITED");assertThat(st(send("create_case",intake(e),null,reviewer))).isEqualTo("APPLIED");
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("screening_record",e,1)),null,reviewer))).isEqualTo("REJECTED");
        assertThat(st(send("revise_finding",findingWith("REJECTED_FOR_USE",ref("screening_record",e,1)),null,reviewer))).isEqualTo("REJECTED");
        assertThat(st(send("revise_finding",findingWith("INCONCLUSIVE",ref("screening_record",e,1)),null,reviewer))).isEqualTo("APPLIED");
    }
    @Test void analysisAlternativesRuleExemptsOnlyProvisionalSingleAndSameInvestigation() throws Exception {
        createCase();String inv=caseRecord().body().path("investigation_id").asText();
        String single=analysisFor("PROVISIONAL_SINGLE",inv),mixed=analysisFor("AMBIGUOUS_OR_OPTIONAL_MIXTURE",inv),other=analysisFor("PROVISIONAL_SINGLE",investigation());
        assertThat(st(send("revise_finding",findingWith("INCONCLUSIVE",ref("analysis",single,1)),null,reviewer))).isEqualTo("APPLIED");
        assertThat(st(send("revise_finding",findingWith("INCONCLUSIVE",ref("analysis",mixed,1)),null,reviewer))).isEqualTo("REJECTED");
        assertThat(st(send("revise_finding",findingWith("INCONCLUSIVE",ref("analysis",other,1)),null,reviewer))).as("another investigation").isEqualTo("REJECTED");
        var withAlternative=findingWith("INCONCLUSIVE",ref("analysis",mixed,1));withAlternative.withArray("alternatives").add("S01 and S02 both remain possible in the synthetic model");
        assertThat(st(send("revise_finding",withAlternative,null,reviewer))).isEqualTo("APPLIED");
    }
    @ParameterizedTest @ValueSource(strings={"EXTRA_KEY","FRACTION","ZERO","TEXT_REVISION","NEGATIVE","NOT_UUID","NOT_OBJECT","NOT_ARRAY","BAD_OUTCOME","MISSING_CASE","UNKNOWN_CASE"})
    void malformedFindingInputsAreRejectedWithoutChange(String variant) throws Exception {
        createCase();var p=finding("INCONCLUSIVE",screen());var first=(ObjectNode)p.path("cited_refs").get(0);
        switch(variant){case "EXTRA_KEY"->first.put("note","x");case "FRACTION"->first.put("revision",1.5);case "ZERO"->first.put("revision",0);case "TEXT_REVISION"->first.put("revision","1");case "NEGATIVE"->first.put("revision",-1);
            case "NOT_UUID"->first.put("id","not-a-uuid");case "NOT_OBJECT"->p.putArray("cited_refs").add("x");case "NOT_ARRAY"->p.put("cited_refs","x");case "BAD_OUTCOME"->p.put("outcome","GUILTY");case "MISSING_CASE"->p.remove("case_id");default->p.put("case_id",id());}
        var before=caseRecord();assertThat(st(send("revise_finding",p,null,reviewer))).as(variant).isEqualTo("REJECTED");assertThat(caseRecord()).isEqualTo(before);assertThat(store.list("finding",run)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"This source caused the smoke","The fuel is high sulphur","Responsible for the night smoke","S02 emitted the plume","Probably guilty","A chance of 80 percent","The lab proved it"})
    void unsupportedClaimsInEitherTextListAreRefused(String text) throws Exception {
        createCase();for(String list:List.of("limitations","alternatives")){var p=finding("INCONCLUSIVE");p.withArray(list).add(text);
            assertThat(st(send("revise_finding",p,null,reviewer))).as(list+": "+text).isEqualTo("REJECTED");}
        assertThat(store.list("finding",run)).isEmpty();
    }
    @Test void honestNegativeLimitsAreAcceptedAndServerAddsSyntheticAndCausationLimits() throws Exception {
        createCase();var p=finding("INCONCLUSIVE");p.withArray("limitations").add("This specimen result does not show that it caused the plume.");p.withArray("alternatives").add("Another unregistered source is not excluded");
        assertThat(st(send("revise_finding",p,null,reviewer))).isEqualTo("APPLIED");var f=store.list("finding",run).get(0).body();
        assertThat(f.path("limitations").toString()).contains("Synthetic observation only","Synthetic teaching observation only","does not show that the specimen");assertThat(f.path("legal_suitability").asText()).isEqualTo("NOT_ASSESSED");
        assertThat(f.path("alternatives")).hasSize(1);
    }
    @Test void findingRecordsExactCaseInputAndPriorFindingLineage() throws Exception {
        createCase();var c1=caseRecord();assertThat(st(send("revise_finding",finding("INCONCLUSIVE",screen()),c1.revision(),reviewer))).isEqualTo("APPLIED");
        var f1=store.get("finding",caseRecord().body().path("finding_ref").path("id").asText(),1).orElseThrow();var c2=caseRecord();
        assertThat(f1.body().path("case_ref").path("revision").asLong()).isEqualTo(c1.revision());assertThat(f1.body().path("prior_finding_ref").isNull()).isTrue();
        assertThat(st(send("revise_finding",finding("REJECTED_FOR_USE"),c2.revision(),reviewer))).as("no rejected item cited").isEqualTo("REJECTED");
        update(screen(),screen().body().put("decision","REJECTED"));assertThat(st(send("revise_finding",finding("INCONCLUSIVE"),caseRecord().revision(),reviewer))).isEqualTo("APPLIED");
        var f2=store.get("finding",f1.id(),2).orElseThrow();assertThat(f2.body().path("prior_finding_ref").path("revision").asLong()).isEqualTo(1);assertThat(f2.body().path("prior_finding_ref").path("id").asText()).isEqualTo(f1.id());
        assertThat(f2.body().path("case_ref").path("revision").asLong()).isEqualTo(caseRecord().revision()-1);assertThat(store.revisions("finding",f1.id())).hasSize(2);
    }
    @Test void repeatedLabRequestsKeepEveryResultAndTheCaseListsThem() throws Exception {
        createSpecimen();analyze(qc());analyze(qc().put("blank_ug",30));var results=store.list("lab_result",run);
        assertThat(results).hasSize(2);assertThat(results.stream().map(StoredRecord::id)).containsExactlyInAnyOrderElementsOf(texts(caseRecord().body().path("lab_result_ids")));
        assertThat(results.stream().map(r->r.body().path("qc_status").asText())).containsExactlyInAnyOrder("PASS","FAIL");assertThat(caseRecord().body().path("state").asText()).isEqualTo("LAB");
    }
    @Test void labTimingComesFromTheServerNotThePayload() throws Exception {
        createSpecimen();now=42.5+400;var p=labRequest(qc()).put("hold_time_s",0).put("analysis_sim_time_s",42.5).put("sealed_sim_time_s",400);
        assertThat(st(send("request_lab",p,specimen().revision(),lab))).isEqualTo("APPLIED");var b=result().body();
        assertThat(b.path("hold_time_s").asDouble()).isCloseTo(400,org.assertj.core.data.Offset.offset(1e-9));assertThat(b.path("qc_status").asText()).isEqualTo("FAIL");assertThat(b.path("qc_reasons").toString()).contains("HOLD_TIME_EXCEEDED");
        assertThat(b.path("analysis_sim_time_s").asDouble()).isEqualTo(now);assertThat(b.path("sealed_sim_time_s").asDouble()).isEqualTo(42.5);
    }
    @Test void labRecordsPackagedMethodLimitsCustodyAndSpecimenLineageWithoutOracleMass() throws Exception {
        createSpecimen();analyze(qc());var b=result().body();var limits=b.path("method_limits");
        assertThat(limits.path("blank_max_ug").asDouble()).isEqualTo(20);assertThat(limits.path("duplicate_rpd_max_percent").asDouble()).isEqualTo(20);assertThat(limits.path("hold_s").asDouble()).isEqualTo(300);
        assertThat(limits.path("loq_percent_w_w").asDouble()).isEqualTo(0.01);assertThat(limits.path("recovery_percent").get(0).asDouble()).isEqualTo(80);assertThat(limits.path("recovery_percent").get(1).asDouble()).isEqualTo(120);assertThat(limits.toString()).doesNotContain("oracle");
        assertThat(b.path("specimen_id").asText()).isEqualTo(specimen().id());assertThat(b.path("custody_ref").path("id").asText()).isEqualTo(specimen().body().path("current_custody_event_id").asText());
        assertThat(b.path("analyzed_by").asText()).isEqualTo("lab_demo@lab");assertThat(b.path("legal_suitability").isMissingNode()).isTrue();assertThat(b.path("limitations").toString()).contains("plume");
    }
    @ParameterizedTest @ValueSource(strings={"MASS_IN_QC","QC_ARRAY","QC_TEXT","INSPECTOR_ROLE","BOARD_ROLE"})
    void labInputThatWouldOverrideTheSpecimenOrRoleIsRefused(String variant) throws Exception {
        createSpecimen();var p=labRequest(qc());Cookie who=lab;switch(variant){case "MASS_IN_QC"->((ObjectNode)p.path("qc_inputs")).put("mass_mg",1000);case "QC_ARRAY"->p.putArray("qc_inputs");case "QC_TEXT"->p.put("qc_inputs","2010");case "INSPECTOR_ROLE"->who=inspector;default->who=cookie("board","board");}
        var before=caseRecord();assertThat(st(send("request_lab",p,null,who))).as(variant).isEqualTo("REJECTED");assertThat(caseRecord()).isEqualTo(before);assertThat(store.list("lab_result",run)).isEmpty();
    }
    @Test void absentQcInputsAreAlsoPendingWithEveryFieldNamed() throws Exception {
        createSpecimen();var p=labRequest(qc());p.remove("qc_inputs");assertThat(st(send("request_lab",p,null,lab))).isEqualTo("APPLIED");var b=result().body();
        assertThat(b.path("qc_status").asText()).isEqualTo("PENDING");assertThat(b.path("qc_inputs").isNull()).isTrue();for(String f:List.of("gross_ug","blank_ug","dup_a_pct","dup_b_pct","spike_added_ug","spike_recovered_ug"))assertThat(b.path("qc_reasons").toString()).contains(f);
        assertThat(b.path("computed").path("net_ug").isNull()).isTrue();assertThat(b.path("reporting").isNull()).isTrue();assertThat(b.path("reporting_reason").asText()).isNotBlank();
    }
    @Test void labRequestWhileAnotherPartyHoldsCustodyIsRecordedAsALimit() throws Exception {
        createSpecimen();analyze(qc());assertThat(result().body().path("limitations").toString()).contains("custody was with");
        send("append_custody",transfer("lab_demo@lab","INTACT"),null,inspector);analyze(qc());
        var latest=store.list("lab_result",run).stream().reduce((a,b)->b).orElseThrow();assertThat(latest.body().path("limitations").toString()).doesNotContain("custody was with");
    }

    // ---- Fix round 1 (review findings R1/R2/R3). Written and run RED before the fixes. ----
    /** Sends the command with the given qc_inputs members written as LITERAL JSON text (not Java doubles). */
    JsonNode sendLiteralQc(java.util.Map<String,String> literals,Long rev,Cookie who) throws Exception {
        var q=qc();for(var k:literals.keySet())q.put(k,"@@"+k+"@@");
        String body=json.writeValueAsString(new Command("fp/1",id(),"request_lab",run,rev,labRequest(q)));
        for(var e:literals.entrySet())body=body.replace("\"@@"+e.getKey()+"@@\"",e.getValue());
        var response=mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse();
        bus.awaitIdle();var r=json.readTree(response.getContentAsString());System.out.println("CASES_RECEIPT "+r);return r;
    }
    JsonNode storedRaw(){return store.get("lab_result",result().id()).orElseThrow().body();}
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"20.0000000000000001,FAIL","20.0000000000000000,PASS","19.9999999999999999,PASS","20.00,PASS"})
    void literalBlankDecimalSurvivesHttpAndStoreReload(String literal,String want) throws Exception {
        createSpecimen();assertThat(st(sendLiteralQc(java.util.Map.of("blank_ug",literal),null,lab))).isEqualTo("APPLIED");
        var b=storedRaw();assertThat(b.path("qc_status").asText()).as(literal).isEqualTo(want);
        assertThat(b.path("qc_checks").path("blank").path("status").asText()).isEqualTo(want);
        assertThat(b.path("raw").path("blank_ug").decimalValue()).isEqualByComparingTo(new java.math.BigDecimal(literal));
        assertThat(b.path("qc_inputs").path("blank_ug").decimalValue()).isEqualByComparingTo(new java.math.BigDecimal(literal));
        assertThat(b.path("raw").path("blank_ug").decimalValue().stripTrailingZeros()).as("every significant digit kept").isEqualTo(new java.math.BigDecimal(literal).stripTrailingZeros());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"dup_a_pct,1.7999999999999999999,2.2,FAIL","dup_a_pct,1.8,2.2,PASS","spike_recovered_ug,120.0000000000000001,100,FAIL","spike_recovered_ug,120.0,100,PASS","spike_recovered_ug,79.9999999999999999,100,FAIL"})
    void literalRpdAndRecoveryDecimalsAreJudgedExactly(String field,String literal,String other,String want) throws Exception {
        createSpecimen();var lit=new java.util.LinkedHashMap<String,String>();lit.put(field,literal);if(field.equals("dup_a_pct"))lit.put("dup_b_pct",other);
        assertThat(st(sendLiteralQc(lit,null,lab))).isEqualTo("APPLIED");assertThat(storedRaw().path("qc_status").asText()).as(field+"="+literal).isEqualTo(want);
        assertThat(storedRaw().path("raw").path(field).decimalValue()).isEqualByComparingTo(new java.math.BigDecimal(literal));
    }
    @Test void hugeIntegerGrossIsNeverPassAndCannotSupportAFinding() throws Exception {
        createSpecimen();String huge="1"+"0".repeat(312);assertThat(st(sendLiteralQc(java.util.Map.of("gross_ug",huge),null,lab))).isEqualTo("APPLIED");
        var b=storedRaw();assertThat(b.path("qc_status").asText()).isNotEqualTo("PASS");assertThat(b.path("raw").path("gross_ug").bigIntegerValue()).isEqualTo(new java.math.BigInteger(huge));
        assertThat(b.path("computed").path("net_ug").isNull()).isTrue();assertThat(b.path("computed").path("percent_w_w").isNull()).isTrue();assertThat(b.path("qc_reasons")).isNotEmpty();
        assertThat(b.path("reporting").asText()).isNotEqualTo("REPORTED");
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("lab_result",result().id(),1)),null,reviewer))).isEqualTo("REJECTED");assertThat(store.list("finding",run)).isEmpty();
    }
    @Test void subnormalGrossUnderflowIsUnavailableNeverZeroAndNeverPass() throws Exception {
        createSpecimen();assertThat(st(sendLiteralQc(java.util.Map.of("gross_ug","5e-324","blank_ug","0"),null,lab))).isEqualTo("APPLIED");
        var b=storedRaw();assertThat(b.path("qc_status").asText()).isEqualTo("PENDING");assertThat(b.path("computed").path("percent_w_w").isNull()).as("not a fabricated zero").isTrue();
        assertThat(b.path("computed_reasons").path("percent_w_w").asText()).contains("NOT_REPRESENTABLE");assertThat(b.path("computed").path("net_ug").asDouble()).isEqualTo(Double.MIN_VALUE);
        assertThat(b.path("raw").path("gross_ug").decimalValue()).isEqualByComparingTo(new java.math.BigDecimal("5e-324"));assertThat(b.path("reporting").asText()).isEqualTo("BELOW_LOQ");
        assertThat(st(send("revise_finding",findingWith("SUPPORTED_WITHIN_SCOPE",ref("lab_result",result().id(),1)),null,reviewer))).isEqualTo("REJECTED");
    }
    @ParameterizedTest @ValueSource(strings={"S02 caused the plume and no uncertainty remains","S02 caused the plume, no doubt","No doubt: S02 caused the plume","This specimen result is legally admissible in court","The specimen identifies diesel","The material is petrol","This result satisfies the legal standard","This does not show a link but S02 caused the plume","S02 caused it which does not show otherwise","It does not matter, S02 emitted it","S02 caused the plume this result does not show otherwise","The source that emitted the plume does not show any doubt"})
    void unrelatedNegationAndLegalOrFuelClaimsAreRefusedInBothLists(String text) throws Exception {
        createCase();for(String list:List.of("limitations","alternatives")){var p=finding("INCONCLUSIVE");p.withArray(list).add(text);
            assertThat(st(send("revise_finding",p,null,reviewer))).as(list+": "+text).isEqualTo("REJECTED");}
        assertThat(store.list("finding",run)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"This specimen result does not show that it caused the plume.","This result does not establish legal admissibility.","The specimen does not identify the fuel.","Legal suitability is not assessed.","Nothing here shows that the specimen caused the plume.","This result cannot confirm the source that emitted the plume."})
    void honestNegativeDisclaimersStillAcceptedInBothLists(String text) throws Exception {
        createCase();for(String list:List.of("limitations","alternatives")){var p=finding("INCONCLUSIVE");p.withArray(list).add(text);
            assertThat(st(send("revise_finding",p,null,reviewer))).as(list+": "+text).isEqualTo("APPLIED");}
    }

    // ---- Fix round 1 (R1 round-trip): the server's own saved disclaimers must survive a normal edit ----
    ObjectNode resubmitSaved(StoredRecord saved){var p=obj().put("case_id",caseRecord().id()).put("outcome",saved.body().path("outcome").asText());
        p.set("cited_refs",saved.body().path("cited_refs").deepCopy());p.set("alternatives",saved.body().path("alternatives").deepCopy());p.set("limitations",saved.body().path("limitations").deepCopy());return p;}
    @Test void aSavedFindingsCompleteLimitationsCanBeResubmittedAsANewRevision() throws Exception {
        createSpecimen();analyze(qc());var p=finding("INCONCLUSIVE",result());p.withArray("alternatives").add("Another source is not excluded");
        assertThat(st(send("revise_finding",p,null,reviewer))).isEqualTo("APPLIED");var saved=store.list("finding",run).get(0);
        assertThat(saved.body().path("limitations").toString()).contains("does not show that the specimen");
        var again=resubmitSaved(saved);assertThat(st(send("revise_finding",again,null,reviewer))).as("full saved limitations, alternatives and citations").isEqualTo("APPLIED");
        var second=store.list("finding",run).get(0);assertThat(second.revision()).isEqualTo(2);assertThat(second.body().path("cited_refs")).isEqualTo(saved.body().path("cited_refs"));
        assertThat(second.body().path("limitations").size()).as("server limitations are not duplicated").isEqualTo(saved.body().path("limitations").size());
        assertThat(st(send("revise_finding",resubmitSaved(second),null,reviewer))).isEqualTo("APPLIED");
    }
    @Test void labRecordsOwnLimitationsCanBeCopiedIntoAFinding() throws Exception {
        createSpecimen();send("append_custody",transfer("lab_demo@lab","BROKEN","BROKEN_SEAL"),null,inspector);analyze(qc());
        var p=finding("INCONCLUSIVE",result());for(JsonNode l:result().body().path("limitations"))p.withArray("limitations").add(l.asText());
        assertThat(st(send("revise_finding",p,null,reviewer))).isEqualTo("APPLIED");
    }
    @ParameterizedTest @ValueSource(strings={"APPEND","PREFIX","CONTAINED","EDITED_WORD","AFFIRMATIVE_ALTERNATIVE"})
    void onlyTheExactTrustedServerSentenceIsAllowlisted(String variant) throws Exception {
        createCase();String trusted=LabHandlers.CAUSATION_LIMITATION;var p=finding("INCONCLUSIVE");var lim=p.withArray("limitations");
        switch(variant){case "APPEND"->lim.add(trusted+" S02 caused the plume");case "PREFIX"->lim.add("S02 caused the plume. "+trusted);case "CONTAINED"->lim.add("Note: "+trusted);
            case "EDITED_WORD"->lim.add(trusted.replace("does not show","shows"));default->p.withArray("alternatives").add(trusted+" Yet S02 emitted it");}
        assertThat(st(send("revise_finding",p,null,reviewer))).as(variant).isEqualTo("REJECTED");assertThat(store.list("finding",run)).isEmpty();
    }

    // ---- Fix round 2: a disclaimer must negate the claim itself (anchored whole-sentence grammar) ----
    @ParameterizedTest @ValueSource(strings={"S02 caused the plume with legal suitability that is not assessed","This result does not show uncertainty about S02 causing the plume","This result does not show doubt that S02 caused the plume","This result does not show uncertainty about the fuel type","S02 emitted it with causation that is not established","The specimen is not assessed but identifies diesel","Legal suitability is not assessed and S02 caused the plume","This result does not show that it caused the plume with S02 responsible"})
    void aDisclaimerMustNegateTheClaimItselfInBothLists(String text) throws Exception {
        createCase();for(String list:List.of("limitations","alternatives")){var p=finding("INCONCLUSIVE");p.withArray(list).add(text);
            assertThat(st(send("revise_finding",p,null,reviewer))).as(list+": "+text).isEqualTo("REJECTED");}
        assertThat(store.list("finding",run)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"The specimen does not show that S02 caused the plume.","Legal admissibility has not been established.","Causation is not established.","This lab result cannot confirm that the specimen emitted the plume.","The sample does not identify the fuel type.","No evidence here shows that any named source caused the plume.","This result does not determine legal suitability.","Neutral note: synthetic scope only"})
    void groundedNegativeDisclaimersAndNeutralNotesAreStillAccepted(String text) throws Exception {
        createCase();for(String list:List.of("limitations","alternatives")){var p=finding("INCONCLUSIVE");p.withArray(list).add(text);
            assertThat(st(send("revise_finding",p,null,reviewer))).as(list+": "+text).isEqualTo("APPLIED");}
    }
    @Test void exactRecordedMassControlsTheReportingBoundaryWithoutRoundingItToTheLimit()throws Exception {
        createCase();String literal="100.0000000000000001";
        assertThat(send("create_specimen",material().put("mass_mg",new java.math.BigDecimal(literal)),caseRecord().revision(),inspector)
                .path("status").asText()).isEqualTo("APPLIED");
        analyze(qc().put("gross_ug",20).put("blank_ug",10).put("dup_a_pct",2).put("dup_b_pct",2));
        // 100*net(10) < LOQ(.01)*mass(100.0000000000000001)*1000, independent exact oracle.
        assertThat(result().body().path("reporting").asText()).isEqualTo("BELOW_LOQ");
        assertThat(result().body().path("raw").path("mass_mg").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal(literal));
        assertThat(result().body().path("qc_status").asText()).isEqualTo("PASS");
    }
    static class TestMode implements ServerMode { volatile boolean active; public boolean isReplay(){return false;} public boolean automationActive(){return active;} }
    @ParameterizedTest @ValueSource(strings={"limitations","alternatives","qc_inputs"})
    void findingsAndRawLabInputsRefusePrivateContactText(String field)throws Exception {
        JsonNode receipt;
        if(field.equals("qc_inputs")){
            createSpecimen();receipt=send("request_lab",labRequest(qc().put("gross_ug","Call +919612345679")),specimen().revision(),lab);
        }else{
            createCase();var p=finding("INCONCLUSIVE");p.withArray(field).add("Call +919612345679");receipt=send("revise_finding",p,caseRecord().revision(),reviewer);
        }
        assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");assertThat(receipt.path("record_refs")).isEmpty();
        assertThat(store.list(field.equals("qc_inputs")?"lab_result":"finding",run)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"phone_number","phoneNumber","ＭＯＢＩＬＥ","contact-number","msisdn"})
    void numericValuesInExplicitContactFieldsCannotEnterPublicLabEvidence(String key)throws Exception {
        createSpecimen();var beforeCase=caseRecord();var beforeSpec=specimen();
        var command=new Command("fp/1",id(),"request_lab",run,beforeSpec.revision(),labRequest(qc().put(key,9812345678L)));
        var receipt=send(command,lab);
        assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");
        assertThat(receipt.path("record_refs")).isEmpty();
        assertThat(caseRecord()).isEqualTo(beforeCase);assertThat(specimen()).isEqualTo(beforeSpec);
        assertThat(store.list("lab_result",run)).isEmpty();
        String saved=jdbc.queryForObject("SELECT payload FROM commands WHERE command_id = ?",String.class,command.commandId());
        assertThat(saved.contains("9812345678")).as("explicit contact value absent from durable command payload").isFalse();
    }
    @TestConfiguration static class Config {
        @Bean @Primary TestMode casesMode(){return new TestMode();}
        @Bean CommandHandler casesFixture(){return new CommandHandler(){public String commandType(){return "test_lab_fixture";}public Set<Role> allowedRoles(){return Set.of(Role.PRESENTER);}public HandlerOutcome handle(Command c,Session s,StoreView v){var p=c.payload();return HandlerResult.builder().save(new RecordChange(p.path("type").asText(),p.path("id").asText(),c.runId(),p.path("based").asLong(0),(ObjectNode)p.path("fields")),"TEST_PUBLIC_RECORD_FIXTURE",0.0).build();}};}
    }
}
