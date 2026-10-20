package fuelprint.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.*;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import fuelprint.core.world.WorldLoop;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiro draft integrated by Codex, 2026-10-20. Native execution evidence is retained externally.
 * Intended path: server/src/test/java/fuelprint/ops/CompleteBackendWorkflowTest.java
 *
 * Real production command chain through real cookies, CommandBus, Ops/Field/Cases/AI/Alerts handlers and
 * real ScenarioWorldSetup physics. No seeded mission/evidence, no mocked authority/planner/physics, no
 * fuelprint.science.truth import, no forced science outcome. Scripted human steps follow declared
 * public-record policies (see HUMAN_POLICY) and are labelled; they are not hidden-truth decisions.
 *
 * Every scenario writes target/complete-workflow-scenarios/<preset>-<automatic>.json whether it passes or
 * fails. Contract/rule violations are collected in "check_failures" and fail the scenario AFTER the full
 * trace is written; nothing is silently skipped. Contract-narrative endings are recorded, never asserted.
 *
 * Recovery = context recovery: a NEW real Spring context on a consistent SQLite VACUUM INTO copy plus copied
 * immutable blobs. It is not OS-process SIGKILL evidence (see core KillRestartTest for that).
 */
@SpringBootTest(properties={"fp.loop.auto=false","fp.field.safety-loop=false"})
@ActiveProfiles("real") @AutoConfigureMockMvc
class CompleteBackendWorkflowTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-complete-workflow-");
    static final AtomicInteger PHONES=new AtomicInteger();
    static final List<String> HUMAN_ONLY=List.of("approve_mission","reject_mission","review_capture","revise_finding","record_board_decision");
    static final List<String> SETTINGS=List.of("auto_propose","auto_autopilot","auto_capture","auto_seal","auto_alerts","auto_retrain");
    static final List<String> OPEN_MISSION=List.of("PROPOSED","APPROVED","LEASED","OUTBOUND","AT_TARGET","COLLECTING","RETURNING");
    static final String HUMAN_POLICY="Scripted human steps: supervisor approves the first eligible mission; QC reviewer confirms each automatic channel pre-check unchanged (null->REJECTED); "
            +"case reviewer outcome = REJECTED_FOR_USE if a cited SO2 screening is REJECTED, else SUPPORTED_WITHIN_SCOPE if latest same-investigation analysis is PROVISIONAL_SINGLE and a cited screening is VALID and lab QC PASS, else INCONCLUSIVE; "
            +"board records MORE_INVESTIGATION. Inputs are public records only.";
    static final int OBSERVE_STEPS=6200, AUTOPILOT_WAIT_STEPS=2000, ARRIVAL_STEPS=6500, CAPTURE_STEPS=16000, AUTOMATION_STEPS=400;
    @DynamicPropertySource static void data(DynamicPropertyRegistry r){
        r.add("fp.data-dir",DATA::toString);r.add("fp.alerts.sms-config",()->DATA.resolve("absent-sms.json").toString());
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired CommandBus bus; @Autowired AutomationBus automation;
    @Autowired RecordStore store; @Autowired BlobStore blobs; @Autowired CookieSessionService sessions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc; @Autowired WorldLoop loop; @Autowired CommandLog commands;
    String run; ObjectNode trace; ArrayNode checkFailures;
    Cookie presenter,analyst,supervisor,operator,qc,reviewer,inspector,lab,exporter,board;
    String labHolder;
    String terminalStage, terminalOperation;
    static class StageStopped extends RuntimeException {}

    ObjectNode obj(){return json.createObjectNode();}
    Cookie station(String role){
        var session=sessions.createStation(UUID.randomUUID().toString(),Set.of(role));
        if(role.equals("lab_demo"))labHolder=session.actorFor(Set.of(Role.LAB_DEMO));
        return new Cookie(CookieSessionService.COOKIE,session.sessionId());
    }
    void idle(){for(int i=0;i<12;i++)bus.awaitIdle();}
    JsonNode send(Cookie who,String type,ObjectNode payload,Long revision)throws Exception {
        var command=new Command("fp/1",UUID.randomUUID().toString(),type,run,revision,payload);
        var response=mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(command))).andExpect(status().isOk()).andReturn().getResponse();
        idle();JsonNode receipt=json.readTree(response.getContentAsByteArray());
        trace.withArray("receipts").addObject().put("command_type",type).put("sim_time_s",loop.simTimeS()).set("receipt",receipt);
        return receipt;
    }
    JsonNode applied(Cookie who,String type,ObjectNode payload,Long revision)throws Exception {
        var receipt=send(who,type,payload,revision);assertThat(receipt.path("status").asText()).as(type+": "+receipt).isEqualTo("APPLIED");return receipt;
    }
    /** The record of {@code type} named by an APPLIED receipt. Avoids ambiguous "latest by timestamp" reads. */
    StoredRecord fromReceipt(JsonNode receipt,String type){
        for(JsonNode r:receipt.path("record_refs"))if(type.equals(r.path("type").asText()))return store.get(type,r.path("id").asText(),r.path("revision").asLong()).orElseThrow();
        throw new AssertionError("receipt names no "+type+": "+receipt);
    }
    StoredRecord current(StoredRecord r){return store.get(r.type(),r.id()).orElseThrow();}
    ObjectNode ref(StoredRecord r){return obj().put("type",r.type()).put("id",r.id()).put("revision",r.revision());}
    void check(boolean ok,String label,Object detail){
        trace.withArray("checks").addObject().put("check",label).put("passed",ok).put("detail",String.valueOf(detail));
        if(!ok)checkFailures.addObject().put("check",label).put("detail",String.valueOf(detail));
    }
    boolean stepUntil(BooleanSupplier ready,int steps){
        for(int i=0;i<steps&&!ready.getAsBoolean();i++){loop.wallStep();if(i%20==0)idle();}
        idle();return ready.getAsBoolean();
    }
    void requireStep(BooleanSupplier ready,int steps,String label){
        boolean ok=stepUntil(ready,steps);trace.withArray("waits").addObject().put("wait",label).put("reached",ok).put("sim_time_s",loop.simTimeS());
        assertThat(ok).as(label+" at sim "+loop.simTimeS()).isTrue();
    }
    String state(StoredRecord r){return current(r).body().path("state").asText();}
    List<StoredRecord> currentRunNotifications(){
        return store.list("notification",null).stream().filter(n->{
            JsonNode cause=n.body().path("cause");
            return store.get(cause.path("type").asText(),cause.path("id").asText())
                    .map(r->Objects.equals(run,r.runId())).orElse(false);
        }).toList();
    }
    List<ObjectNode> bodies(String type){return (type.equals("notification")?currentRunNotifications():store.list(type,run)).stream().map(StoredRecord::body).toList();}
    static Stream<Arguments> scenarios(){return Stream.of("SC1-NIGHT-PLUME","SC2-BLOCKED-INLET","SC3-ABOVE-THE-BOXES").flatMap(p->Stream.of(Arguments.of(p,false),Arguments.of(p,true)));}

    @ParameterizedTest(name="{0}, automation={1}") @MethodSource("scenarios")
    void genuineApprovalCaptureCasesExportBoardAndReset(String preset,boolean automatic)throws Exception {
        executeWorkflow(preset, automatic);
    }

    static Stream<Arguments> lifecycleStages(){
        return Stream.of("investigation","approval","capture","finding","export")
                .flatMap(stage->Stream.of(Arguments.of(stage,"reset_run"),Arguments.of(stage,"complete_run")));
    }
    @ParameterizedTest(name="{0}, terminal={1}") @MethodSource("lifecycleStages")
    void resetAndCompletionAtEachGenuineWorkflowStage(String stage,String operation)throws Exception {
        terminalStage=stage;terminalOperation=operation;
        executeWorkflow("SC1-NIGHT-PLUME", false);
        assertThat(trace.path("terminal_stage").asText()).isEqualTo(stage);
        assertThat(trace.path("terminal_operation").asText()).isEqualTo(operation);
    }

    void executeWorkflow(String preset,boolean automatic)throws Exception {
        long start=System.nanoTime();run=null;
        trace=obj().put("preset",preset).put("automation_settings",automatic).put("status","STARTED").put("human_policy",HUMAN_POLICY)
                .put("scope","Real production command/physics chain. Lab inputs are explicitly supplied synthetic teaching numbers and the specimen site is a fixed demo input (S02), not hidden truth. "
                        +"Teaching/development seeds only: not held-out, calibration or trained-model evidence. No trained inference/ONNX, no physical SMS (LOG_ONLY).");
        checkFailures=trace.putArray("check_failures");
        presenter=station("presenter");analyst=station("analyst");supervisor=station("supervisor");operator=station("field_operator");qc=station("qc_reviewer");
        reviewer=station("case_reviewer");inspector=station("inspector_demo");lab=station("lab_demo");exporter=station("exporter");board=station("board");
        boolean recover=preset.startsWith("SC1")&&!automatic;
        try {
            for(String setting:SETTINGS)applied(presenter,"change_automation",obj().put("setting",setting).put("value",automatic),null);
            JsonNode reset=applied(presenter,"reset_run",obj().put("preset_id",preset).put("mode","BLIND"),null);
            run=fromReceipt(reset,"run").id();
            if(!"RUNNING".equals(store.get("run",run).orElseThrow().body().path("state").asText()))
                run=store.listAll("run").stream().filter(r->"RUNNING".equals(r.body().path("state").asText())).findFirst().orElseThrow().id();
            trace.put("run_id",run);trace.set("run",store.get("run",run).orElseThrow().body());
            long modelsBefore=store.listAll("model_version").size();
            var subscription=obj().put("phone_number",String.format("+9197%08d",PHONES.incrementAndGet())).put("consent",true).put("consent_text_version","FP-CONSENT-1");
            subscription.putArray("area_ids").add("AREA-E");
            if(terminalOperation==null)applied(station("guest"),"subscribe_alerts",subscription,null);

            // ---- Ops: observe the real world, then choose the mission basis by a declared public policy.
            stepUntil(()->loop.simTimeS()>=300,OBSERVE_STEPS);
            trace.put("observed_until_s",loop.simTimeS());
            trace.set("triggers",json.valueToTree(bodies("trigger")));trace.set("anomalies",json.valueToTree(bodies("anomaly")));
            trace.set("investigations_before_mission",json.valueToTree(bodies("investigation")));
            trace.set("analyses_before_mission",json.valueToTree(bodies("analysis")));trace.set("proposals_before_mission",json.valueToTree(bodies("proposal")));
            StoredRecord mission=chooseAndProposeMission(preset,automatic);
            if(recover)snapshotAndRecover("investigation");

            // Wrong role cannot approve; then the human supervisor approves the exact current dependency hash.
            var probe=send(qc,"approve_mission",obj().put("mission_id",mission.id()).put("dependency_hash",mission.body().path("dependency_hash").asText()),mission.revision());
            check("NOT_ALLOWED".equals(probe.path("reason_code").asText())&&"PROPOSED".equals(state(mission)),"wrong role cannot approve",probe);
            mission=current(mission);
            var approvalReceipt=applied(supervisor,"approve_mission",obj().put("mission_id",mission.id()).put("dependency_hash",mission.body().path("dependency_hash").asText()),mission.revision());
            mission=current(mission);trace.set("approved_mission",mission.body());final StoredRecord selected=mission;
            var supervisorSession=sessions.resolveToken(supervisor.getValue()).orElseThrow();
            check(selected.body().path("approved_by").asText().equals(supervisorSession.actorLabel())
                    &&selected.createdBy().equals(supervisorSession.actorFor(Set.of(Role.SUPERVISOR)))
                    &&approvalReceipt.path("actor").asText().equals(selected.createdBy())
                    &&!approvalReceipt.path("automatic").asBoolean(),"approval matches the human supervisor session and receipt",selected.body().path("approved_by"));
            if(recover)snapshotAndRecover("approval");

            // ---- Field: genuine departure. Automation mode waits for the real automatic autopilot start first.
            String departure;
            if(automatic&&stepUntil(()->!"APPROVED".equals(state(selected)),AUTOPILOT_WAIT_STEPS)){
                departure="AUTOMATIC_AUTOPILOT_AFTER_WAIT";
            } else {
                if(automatic)check(false,"automatic autopilot start observed within "+AUTOPILOT_WAIT_STEPS+" loop steps",state(selected));
                departure=automatic?"MANUAL_OPERATOR_FALLBACK_AFTER_NO_AUTOSTART":"MANUAL_OPERATOR_START_AUTOPILOT";
                if("APPROVED".equals(state(selected)))applied(operator,"start_autopilot",obj().put("mission_id",selected.id()).put("vehicle_id",selected.body().path("vehicle_id").asText()),null);
            }
            trace.put("departure",departure);
            requireStep(()->Set.of("AT_TARGET","COLLECTING").contains(state(selected))||!store.list("attempt",run).stream().filter(a->missionId(a).equals(selected.id())).toList().isEmpty(),ARRIVAL_STEPS,"real arrival");
            if(!automatic){
                var capture=obj().put("mission_id",selected.id()).put("method_id",selected.body().path("method_id").asText());
                capture.set("channel_ids",selected.body().path("channel_ids"));applied(operator,"start_capture",capture,null);
                if(recover)snapshotAndRecover("capture");
            }
            requireStep(()->store.list("attempt",run).stream().anyMatch(a->missionId(a).equals(selected.id())&&!"COLLECTING".equals(a.body().path("state").asText())),CAPTURE_STEPS,"real capture completion");
            var attempt=store.list("attempt",run).stream().filter(a->missionId(a).equals(selected.id())).findFirst().orElseThrow();trace.set("attempt",attempt.body());
            check(!automatic||attempt.createdBy().equals("automation"),"automatic capture start is attributed to automation",attempt.createdBy());

            // ---- Human QC: confirm automatic pre-checks unchanged.
            var decisions=obj().put("attempt_id",attempt.id());var channels=decisions.putArray("channel_decisions");
            for(JsonNode channel:attempt.body().path("channels"))
                channels.addObject().put("channel",channel.path("channel").asText()).put("decision",channel.path("status").asText("REJECTED")).putArray("reasons").add("GENUINE_WORKFLOW_HUMAN_QC");
            applied(qc,"review_capture",decisions,current(attempt).revision());
            var evidence=store.list("screening_record",run).stream().filter(r->attempt.id().equals(r.body().path("attempt_id").asText())).toList();
            check(evidence.size()==attempt.body().path("channels").size(),"one screening record per attempt channel",evidence.size());
            trace.set("screening_records",json.valueToTree(evidence.stream().map(StoredRecord::body).toList()));
            stepUntil(()->false,AUTOMATION_STEPS);trace.set("analyses_after_capture",json.valueToTree(bodies("analysis")));

            // ---- Cases: intake, specimen, custody to lab, lab QC, human finding.
            var intake=obj().put("purpose","Synthetic teaching evidence review after a genuine mission");var ids=intake.putArray("screening_record_ids");evidence.forEach(r->ids.add(r.id()));
            var caseRecord=fromReceipt(applied(reviewer,"create_case",intake,null),"case");
            var material=obj().put("case_id",caseRecord.id()).put("site_id","S02").put("material","SYNTHETIC_COAL_COKE_LIKE").put("method_id","TEACH-FUEL-S-1").put("container","teaching-jar").put("seal_id","teaching-seal").put("mass_mg",100);
            var specimen=fromReceipt(applied(inspector,"create_specimen",material,current(caseRecord).revision()),"specimen");
            var custody=obj().put("specimen_id",specimen.id()).put("to_holder",labHolder).put("seal_state","INTACT").put("event_time_utc",java.time.Instant.now().toString());custody.putArray("exceptions");
            applied(inspector,"append_custody",custody,current(specimen).revision());
            var request=obj().put("specimen_id",specimen.id()).put("method_id","TEACH-FUEL-S-1");
            request.set("qc_inputs",obj().put("gross_ug",2010).put("blank_ug",10).put("dup_a_pct",2.0).put("dup_b_pct",2.2).put("spike_added_ug",100).put("spike_recovered_ug",90));
            var result=fromReceipt(applied(lab,"request_lab",request,current(specimen).revision()),"lab_result");trace.set("lab_result",result.body());
            check("PASS".equals(result.body().path("qc_status").asText()),"declared demo lab inputs pass lab QC",result.body().path("qc_status"));
            caseRecord=current(caseRecord);
            String outcome=findingOutcome(caseRecord,evidence,result);
            var finding=obj().put("case_id",caseRecord.id()).put("outcome",outcome);
            finding.putArray("limitations").add("Synthetic teaching specimen only.");finding.putArray("alternatives").add("Other explanations remain possible.");
            var cited=finding.putArray("cited_refs");for(JsonNode pinned:caseRecord.body().path("screening_refs"))cited.add(pinned);cited.add(ref(result));
            sameInvestigationAnalysis(caseRecord).ifPresent(a->cited.add(ref(a)));
            var findingRecord=fromReceipt(applied(reviewer,"revise_finding",finding,caseRecord.revision()),"finding");trace.set("finding",findingRecord.body());
            check("NOT_ASSESSED".equals(findingRecord.body().path("legal_suitability").asText()),"finding legal suitability stays NOT_ASSESSED",findingRecord.body().path("legal_suitability"));
            caseRecord=current(caseRecord);
            if(recover)snapshotAndRecover("finding");

            // ---- Export: manual exporter or automatic seal/verify; full seven-axis and byte checks.
            if(!automatic)applied(exporter,"seal_export",obj().put("case_id",caseRecord.id()).put("case_revision",caseRecord.revision()),caseRecord.revision());
            else stepUntil(()->store.list("export",run).stream().anyMatch(e->"ORIGINAL".equals(e.body().path("kind").asText())),AUTOMATION_STEPS);
            var pack=store.list("export",run).stream().filter(e->"ORIGINAL".equals(e.body().path("kind").asText())).findFirst().orElseThrow();
            if(!automatic)applied(exporter,"verify_export",obj().put("export_id",pack.id()),null);
            else stepUntil(()->!reports(pack.id()).isEmpty(),AUTOMATION_STEPS);
            check(!automatic||pack.createdBy().equals("automation"),"automatic seal attributed to automation",pack.createdBy());
            checkExportBytes(pack,"original");
            var report=reports(pack.id()).stream().findFirst().orElseThrow();
            checkOriginalAxes(report.body(),"original export");
            check(report.body().path("export_ref").path("revision").asLong()==pack.revision(),"report cites exact export revision",report.body().path("export_ref"));
            check(pack.body().path("finding_ref").equals(current(caseRecord).body().path("finding_ref")),"export pins the case's exact finding revision",pack.body().path("finding_ref"));
            trace.set("export",pack.body());trace.set("verification_reports",json.valueToTree(reports(pack.id()).stream().map(StoredRecord::body).toList()));
            if(recover)snapshotAndRecover("export");
            var preserved=evidenceRevisions();

            // Tamper demo: a COPY fails integrity; the original record, bytes and verification remain intact.
            String member=pack.body().path("members").get(0).path("path").asText();
            var copy=fromReceipt(applied(exporter,"tamper_copy",obj().put("export_id",pack.id()).put("member_path",member),null),"export");
            var copyReport=fromReceipt(applied(exporter,"verify_export",obj().put("export_id",copy.id()),null),"verification_report");
            check("FAIL".equals(copyReport.body().path("axes").path("integrity").asText()),"tampered copy fails integrity",copyReport.body().path("axes"));
            check(!"PASS".equals(copyReport.body().path("axes").path("lineage").asText()),"tampered copy lineage is not blessed",copyReport.body().path("axes").path("lineage"));
            var reverify=fromReceipt(applied(exporter,"verify_export",obj().put("export_id",pack.id()),null),"verification_report");
            checkOriginalAxes(reverify.body(),"original after tamper copy");checkExportBytes(pack,"original after tamper copy");
            check(store.revisions("export",pack.id()).equals(preserved.get("export/"+pack.id())),"original export record unchanged by tamper copy",pack.id());
            trace.set("tamper_copy",copy.body());trace.set("tamper_copy_report",copyReport.body());

            // ---- Board (human) and notifications (LOG_ONLY). Automatic send when on; presenter sends when off.
            caseRecord=current(caseRecord);
            var notice=obj().put("case_id",caseRecord.id()).put("decision","MORE_INVESTIGATION").put("rationale","Teaching specimen does not settle the wider investigation.");
            notice.putObject("notice").put("area","East colony").put("interval","Current teaching interval").put("status_text","Investigation continues").putArray("area_ids").add("AREA-E");
            var boardRecord=fromReceipt(applied(board,"record_board_decision",notice,caseRecord.revision()),"board_decision");
            check(boardRecord.body().path("decided_by").asText().startsWith("board@"),"board decision actor is human board",boardRecord.body().path("decided_by"));
            stepUntil(()->false,AUTOMATION_STEPS);
            var automaticBoard=boardNotifications(boardRecord.id());
            check(automaticBoard.size()==(automatic?1:0),"automatic BOARD_UPDATE count matches auto_alerts",automaticBoard.size());
            if(!automatic){
                var manual=obj().put("kind","BOARD_UPDATE");manual.putArray("area_ids").add("AREA-E");manual.set("cause",ref(boardRecord));
                var receipt=send(presenter,"send_alert",manual,null);
                if("WORDING_PENDING".equals(receipt.path("reason_code").asText())){stepUntil(()->false,AUTOMATION_STEPS);receipt=send(presenter,"send_alert",manual,null);}
                check("APPLIED".equals(receipt.path("status").asText()),"presenter manual BOARD_UPDATE applied",receipt);
            }
            var boardNotes=boardNotifications(boardRecord.id());
            check(boardNotes.size()==1,"exactly one BOARD_UPDATE per board decision",boardNotes.size());
            for(StoredRecord n:boardNotes){
                var b=n.body();
                check("LOG_ONLY".equals(b.path("gateway_mode").asText()),"notification is LOG_ONLY",b.path("gateway_mode"));
                check(automatic?"automation".equals(n.createdBy()):n.createdBy().startsWith("presenter@"),"notification actor matches mode",n.createdBy());
                if("SUPPRESSED".equals(b.path("state").asText()))
                    check(Set.of("RATE_LIMIT","DAILY_CAP","NO_SUBSCRIBERS","GATEWAY_OFF").contains(b.path("suppressed_reason").asText())&&b.path("sent").asInt(-1)==0&&b.path("failed").asInt(-1)==0,"suppressed notification reports reason and no delivery",b.path("suppressed_reason"));
                else
                    check(b.path("wording_id").isTextual()&&store.get("wording",b.path("wording_id").asText()).map(w->w.body().path("text").equals(b.path("text"))).orElse(false),"notification uses its saved ordinary wording",b.path("wording_id"));
                check(b.path("text").asText().startsWith("FuelPrint DEMO (simulated):")&&b.path("text").asText().length()<=160&&!b.path("text").asText().matches("(?s).*S0[1-6].*"),"SMS text rules",b.path("text"));
                check(!b.toString().matches("(?s).*\\+?91\\s?97\\d{8}.*"),"notification holds no phone number",n.id());
            }
            smokeWarningConsistency(automatic);
            trace.set("notifications",json.valueToTree(bodies("notification")));

            // ---- AI honesty: retraining is assessed (automatic only) and never invents a model.
            var retrains=commands.automationEntries(run,10000).stream().filter(e->e.commandType().equals("retrain_model")).toList();
            check(automatic?retrains.stream().anyMatch(e->"TRAINING_UNAVAILABLE".equals(e.receipt().reasonCode())):retrains.isEmpty(),"automatic retrain assessment matches auto_retrain",retrains.size());
            check(store.listAll("model_version").size()==modelsBefore,"no model_version fabricated",store.listAll("model_version").size());

            // ---- Human-only commands refuse the automation actor (with real IDs; nothing changes).
            humanOnlyRefusals(selected,attempt,current(caseRecord));

            // ---- Real outcomes vs contract narrative (recorded, never asserted or forced).
            recordOutcomes(preset,evidence,findingRecord);
            trace.set("automatic_receipts",json.valueToTree(commands.automationEntries(run,10000)));

            // ---- Reset preserves history.
            String oldRun=run;var beforeReset=store.get("run",oldRun).orElseThrow().body();
            trace.set("run_before_reset",beforeReset);
            applied(presenter,"reset_run",obj().put("preset_id",preset).put("mode","BLIND"),null);
            var oldRunBody=store.get("run",oldRun).orElseThrow().body();
            if(Set.of("RUNNING","PAUSED").contains(beforeReset.path("state").asText()))
                check("ABORTED".equals(oldRunBody.path("state").asText())&&"RESET".equals(oldRunBody.path("state_reason").asText()),"reset aborts active old run with RESET",oldRunBody.path("state"));
            else check(oldRunBody.equals(beforeReset),"reset preserves a run that already ended naturally",oldRunBody);
            check(store.listAll("run").stream().anyMatch(r->!oldRun.equals(r.id())&&"RUNNING".equals(r.body().path("state").asText())),"reset creates a distinct running run",oldRun);
            for(var e:preserved.entrySet()){var k=e.getKey().split("/");check(store.revisions(k[0],k[1]).equals(e.getValue()),"evidence preserved across tamper/board/reset: "+e.getKey(),k[1]);}
            trace.put("status",checkFailures.isEmpty()?"PASS_WITH_DECLARED_LIMITS":"FAILED_CHECKS");
        } catch(StageStopped expected){
            trace.put("status",checkFailures.isEmpty()?"PASS_WITH_DECLARED_LIMITS":"FAILED_CHECKS");
        } catch(Throwable failure){trace.put("status","FAILED").put("failure",failure.toString());throw failure;}
        finally {
            trace.put("elapsed_s",(System.nanoTime()-start)/1e9);
            store.listAll("run").stream().filter(r->loop.isLoaded(r.id())).forEach(r->loop.stop(r.id()));
            Path report=terminalOperation==null
                    ?Path.of("target/complete-workflow-scenarios",preset+"-"+automatic+".json")
                    :Path.of("target/workflow-lifecycle-scenarios",terminalStage+"-"+terminalOperation+".json");
            Files.createDirectories(report.getParent());
            Files.writeString(report,json.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
        }
        assertThat(checkFailures).as("scenario rule checks; full trace in target/complete-workflow-scenarios").isEmpty();
    }

    /** Declared basis policy: automation-proposed planner mission, else manual planner best action, else independent survey. */
    StoredRecord chooseAndProposeMission(String preset,boolean automatic)throws Exception {
        var proposed=store.list("mission",run).stream().filter(m->"PROPOSED".equals(m.body().path("state").asText())).findFirst();
        if(automatic&&proposed.isPresent()){
            trace.put("mission_basis","PLANNER_BEST_ACTION_AUTOMATICALLY_PROPOSED");
            check(proposed.get().createdBy().equals("automation"),"automatic proposal attributed to automation",proposed.get().createdBy());
            return proposed.get();
        }
        // Only the most recently created proposal is current; an older BEST_ACTION would carry stale dependencies.
        var best=store.list("proposal",run).stream().reduce((a,b)->b).filter(p->"BEST_ACTION".equals(p.body().path("result").asText()));
        if(automatic)check(best.isEmpty()||proposed.isPresent(),"auto_propose turned an existing BEST_ACTION into a mission",best.map(StoredRecord::id).orElse("none"));
        if(!automatic&&best.isPresent()){
            trace.put("mission_basis","PLANNER_BEST_ACTION_PROPOSED_BY_ANALYST");
            var p=best.get();var receipt=applied(analyst,"propose_mission",obj().put("proposal_id",p.id()).put("action_id",p.body().path("best_action_id").asText()),p.revision());
            return fromReceipt(receipt,"mission");
        }
        // No planner recommendation: an independent survey from the public catalogue. Action per contract §10 narrative
        // (SC3 plume above the 3 m boxes -> 45 m point); not hidden truth, and not a planner-benefit claim.
        String action=preset.startsWith("SC3")?"SURVEY-DRONE-280-140-45":"SURVEY-DRONE-0-100-35";
        trace.put("mission_basis","INDEPENDENT_SURVEY_"+action);
        var survey=fromReceipt(applied(analyst,"schedule_survey",obj().put("action_id",action).put("reason","Independent public survey; planner result retained as recorded"),null),"survey");
        trace.set("analyses_after_survey",json.valueToTree(bodies("analysis")));trace.set("proposals_after_survey",json.valueToTree(bodies("proposal")));
        var receipt=send(analyst,"propose_mission",obj().put("survey_id",survey.id()).put("action_id",action),current(survey).revision());
        if("MISSION_PENDING".equals(receipt.path("reason_code").asText())){
            // A pending planner mission already exists for this investigation; record and use it rather than bypass the guard.
            trace.put("mission_basis","EXISTING_PENDING_MISSION_AFTER_SURVEY_REFUSED");
            return store.list("mission",run).stream().filter(m->"PROPOSED".equals(m.body().path("state").asText())).findFirst().orElseThrow();
        }
        assertThat(receipt.path("status").asText()).as("propose survey mission: "+receipt).isEqualTo("APPLIED");
        return fromReceipt(receipt,"mission");
    }

    String findingOutcome(StoredRecord caseRecord,List<StoredRecord> evidence,StoredRecord lab){
        boolean so2Rejected=evidence.stream().anyMatch(r->r.body().path("channel").asText().startsWith("SO2")&&"REJECTED".equals(r.body().path("decision").asText()));
        boolean valid=evidence.stream().anyMatch(r->"VALID".equals(r.body().path("decision").asText()));
        String analysis=sameInvestigationAnalysis(caseRecord).map(a->a.body().path("outcome").asText()).orElse("NONE");
        String outcome=so2Rejected?"REJECTED_FOR_USE":("PROVISIONAL_SINGLE".equals(analysis)&&valid&&"PASS".equals(lab.body().path("qc_status").asText()))?"SUPPORTED_WITHIN_SCOPE":"INCONCLUSIVE";
        trace.putObject("finding_policy_inputs").put("so2_rejected",so2Rejected).put("any_valid",valid).put("latest_analysis_outcome",analysis).put("lab_qc",lab.body().path("qc_status").asText()).put("chosen",outcome);
        return outcome;
    }
    Optional<StoredRecord> sameInvestigationAnalysis(StoredRecord caseRecord){
        String inv=caseRecord.body().path("investigation_id").asText();
        return store.list("analysis",run).stream().filter(a->inv.equals(a.body().path("investigation_id").asText())).reduce((a,b)->b);
    }
    List<StoredRecord> reports(String exportId){return store.list("verification_report",run).stream().filter(r->exportId.equals(r.body().path("export_id").asText())).toList();}
    List<StoredRecord> boardNotifications(String boardId){
        return currentRunNotifications().stream().filter(n->"BOARD_UPDATE".equals(n.body().path("kind").asText())&&boardId.equals(n.body().path("cause").path("id").asText())).toList();
    }
    void checkOriginalAxes(JsonNode report,String label){
        var a=report.path("axes");
        check("PASS".equals(a.path("integrity").asText()),label+": integrity PASS",a);
        check("NOT_SIGNED".equals(a.path("key_trust").asText()),label+": key_trust NOT_SIGNED",a);
        check("PASS".equals(a.path("lineage").asText()),label+": lineage PASS",a);
        check("UNVERIFIED".equals(a.path("trusted_time").asText()),label+": trusted_time UNVERIFIED",a);
        check(Set.of("PASS","LIMITED").contains(a.path("custody_documentation").asText()),label+": custody PASS or LIMITED",a);
        check("SYNTHETIC_SCOPE_ONLY".equals(a.path("measurement_qualification").asText()),label+": measurement SYNTHETIC_SCOPE_ONLY",a);
        check("NOT_ASSESSED".equals(a.path("legal_suitability").asText()),label+": legal NOT_ASSESSED",a);
    }
    void checkExportBytes(StoredRecord pack,String label){
        var b=current(pack).body();
        check("SEALED".equals(b.path("state").asText())&&"ORIGINAL".equals(b.path("kind").asText())&&b.path("signature").isNull(),label+": sealed unsigned original",b.path("state"));
        check(b.path("members").size()>0,label+": has members",b.path("members").size());
        for(JsonNode m:b.path("members")){
            byte[] bytes=blobs.exists(m.path("sha256").asText())?blobs.get(m.path("sha256").asText()):null;
            check(bytes!=null&&bytes.length==m.path("length").asLong()&&BlobStore.sha256(bytes).equals(m.path("sha256").asText()),label+": member bytes exact "+m.path("path").asText(),m);
        }
        String manifest=b.path("manifest_sha256").asText();
        check(blobs.exists(manifest)&&BlobStore.sha256(blobs.get(manifest)).equals(manifest),label+": manifest bytes exact",manifest);
    }
    Map<String,List<StoredRecord>> evidenceRevisions(){
        var out=new LinkedHashMap<String,List<StoredRecord>>();
        for(String type:List.of("case","finding","specimen","custody_event","lab_result","export","verification_report","screening_record","attempt"))
            for(StoredRecord r:store.list(type,run))out.put(type+"/"+r.id(),store.revisions(type,r.id()));
        return out;
    }
    void smokeWarningConsistency(boolean automatic){
        var warned=store.list("investigation",run).stream().filter(i->i.body().path("warned_area_ids").isArray()&&i.body().path("warned_area_ids").size()>0).toList();
        var smoke=currentRunNotifications().stream().filter(n->"SMOKE_WARNING".equals(n.body().path("kind").asText())).toList();
        trace.putObject("smoke_warning").put("investigations_with_warned_areas",warned.size()).put("notifications",smoke.size());
        if(!automatic)check(smoke.isEmpty(),"no automatic SMOKE_WARNING with auto_alerts off",smoke.size());
        else if(!warned.isEmpty())check(!smoke.isEmpty(),"a warned investigation produced a SMOKE_WARNING notification (SENT or SUPPRESSED with reason)",smoke.size());
        smoke.forEach(n->check(!"SUPPRESSED".equals(n.body().path("state").asText())||n.body().path("suppressed_reason").isTextual(),"suppressed warning carries its reason",n.body().path("suppressed_reason")));
    }
    void humanOnlyRefusals(StoredRecord mission,StoredRecord attempt,StoredRecord caseRecord)throws Exception {
        var before=new LinkedHashMap<String,Long>();
        for(StoredRecord r:List.of(mission,attempt,caseRecord))before.put(r.type()+"/"+r.id(),current(r).revision());
        var payloads=Map.of(
                "approve_mission",obj().put("mission_id",mission.id()).put("dependency_hash",mission.body().path("dependency_hash").asText()),
                "reject_mission",obj().put("mission_id",mission.id()).put("reason","automation probe"),
                "review_capture",(ObjectNode)obj().put("attempt_id",attempt.id()).set("channel_decisions",json.createArrayNode()),
                "revise_finding",obj().put("case_id",caseRecord.id()).put("outcome","INCONCLUSIVE"),
                "record_board_decision",obj().put("case_id",caseRecord.id()).put("decision","MORE_INVESTIGATION"));
        for(String type:HUMAN_ONLY){
            Receipt receipt=automation.submit(type,(ObjectNode)payloads.get(type),run,null).get(30,TimeUnit.SECONDS);
            trace.withArray("human_only_probes").addObject().put("command_type",type).put("status",receipt.status().name()).put("reason_code",receipt.reasonCode());
            check("NOT_ALLOWED".equals(receipt.reasonCode()),"automation refused for "+type,receipt.reasonCode());
        }
        idle();
        for(var e:before.entrySet()){var k=e.getKey().split("/");check(store.latestRevision(k[0],k[1])==e.getValue(),"human-only probes changed nothing: "+e.getKey(),k[1]);}
    }
    void recordOutcomes(String preset,List<StoredRecord> evidence,StoredRecord finding){
        var latest=store.list("analysis",run).stream().reduce((a,b)->b).map(a->a.body().path("outcome").asText()).orElse(null);
        var latestProposal=store.list("proposal",run).stream().reduce((a,b)->b).map(p->p.body().path("result").asText()).orElse(null);
        var channels=obj();evidence.forEach(r->channels.put(r.body().path("channel").asText(),r.body().path("decision").asText()));
        var expected=switch(preset){
            case "SC1-NIGHT-PLUME"->obj().put("analysis","PROVISIONAL_SINGLE S02").put("finding","SUPPORTED_WITHIN_SCOPE");
            case "SC2-BLOCKED-INLET"->obj().put("channels","SO2 REJECTED, CO2 VALID").put("finding","REJECTED_FOR_USE");
            default->obj().put("analysis","AMBIGUOUS_OR_OPTIONAL_MIXTURE or NO_USEFUL_MEASUREMENT").put("finding","INCONCLUSIVE");
        };
        var o=trace.putObject("actual_outcomes").put("latest_analysis_outcome",latest).put("latest_proposal_result",latestProposal).put("finding",finding.body().path("outcome").asText());
        o.set("screening_decisions",channels);o.set("contract_narrative_expected_NOT_ASSERTED",expected);
        o.put("finding_matches_narrative",expected.path("finding").asText().equals(finding.body().path("outcome").asText()));
    }

    void stopAtStage(String stage)throws Exception {
        var immutable=evidenceRevisions();
        var missions=store.list("mission",run);var attempts=store.list("attempt",run);
        var screening=store.list("screening_record",run);
        var flightHistory=new LinkedHashMap<String,List<StoredRecord>>();
        for(String type:List.of("mission","lease"))for(var record:store.list(type,run))flightHistory.put(type+"/"+record.id(),store.revisions(type,record.id()));
        trace.set("terminal_starting_run",store.get("run",run).orElseThrow().body());
        trace.set("terminal_starting_missions",json.valueToTree(missions));trace.set("terminal_starting_attempts",json.valueToTree(attempts));
        var originalBlobs=new LinkedHashMap<String,byte[]>();
        for(var pack:store.list("export",run)){
            originalBlobs.put(pack.body().path("manifest_sha256").asText(),blobs.get(pack.body().path("manifest_sha256").asText()));
            for(var member:pack.body().path("members"))originalBlobs.put(member.path("sha256").asText(),blobs.get(member.path("sha256").asText()));
        }
        assertThat(store.get("run",run).orElseThrow().body().path("state").asText()).as(stage+" active before terminal command").isEqualTo("RUNNING");
        trace.put("terminal_stage",stage).put("terminal_operation",terminalOperation)
                .put("terminal_scope","Real command at a genuine workflow stage. complete_run WORLD_CAPACITY is an explicitly injected lifecycle event, not a claim that physics naturally reached capacity.");
        if(terminalOperation.equals("reset_run")){
            applied(presenter,"reset_run",obj().put("preset_id","SC1-NIGHT-PLUME").put("mode","BLIND"),null);
        }else{
            var receipt=automation.submit("complete_run",obj().put("run_id",run).put("reason","WORLD_CAPACITY"),run,store.get("run",run).orElseThrow().revision()).get(30,TimeUnit.SECONDS);
            trace.withArray("terminal_receipts").add((JsonNode)json.valueToTree(receipt));
            assertThat(receipt.applied()).as("automatic complete_run: "+receipt.reasonText()).isTrue();idle();
        }
        var ended=store.get("run",run).orElseThrow().body();trace.set("terminal_run",ended);
        assertThat(ended.path("state").asText()).isEqualTo(terminalOperation.equals("reset_run")?"ABORTED":"COMPLETED");
        assertThat(ended.path("state_reason").asText()).isEqualTo(terminalOperation.equals("reset_run")?"RESET":"WORLD_CAPACITY");
        for(var m:missions)if(OPEN_MISSION.contains(m.body().path("state").asText()))
            assertThat(store.get("mission",m.id()).orElseThrow().body().path("state").asText()).as(stage+" open mission interrupted").isEqualTo("INTERRUPTED");
        for(var a:attempts)if("COLLECTING".equals(a.body().path("state").asText()))
            assertThat(store.get("attempt",a.id()).orElseThrow().body().path("state").asText()).as(stage+" capture interrupted").isEqualTo("INTERRUPTED");
        assertThat(store.list("lease",run)).allSatisfy(l->assertThat(l.body().path("state").asText()).isNotEqualTo("ACTIVE"));
        assertThat(store.list("screening_record",run)).as("terminal transition cannot manufacture usable evidence").isEqualTo(screening);
        for(var entry:immutable.entrySet()){
            var key=entry.getKey().split("/");var after=store.revisions(key[0],key[1]);
            if(key[0].equals("attempt"))assertThat(after).as("attempt history retains ordered prefix").startsWith(entry.getValue().toArray(StoredRecord[]::new));
            else assertThat(after).as(stage+" immutable "+entry.getKey()).isEqualTo(entry.getValue());
        }
        for(var entry:flightHistory.entrySet()){
            var key=entry.getKey().split("/");
            assertThat(store.revisions(key[0],key[1])).as("flight authority history preserves ordered prefix").startsWith(entry.getValue().toArray(StoredRecord[]::new));
        }
        for(var blob:originalBlobs.entrySet())assertThat(blobs.get(blob.getKey())).as("sealed bytes unchanged").isEqualTo(blob.getValue());
        if(stage.equals("export")){
            var pack=store.list("export",run).stream().filter(e->"ORIGINAL".equals(e.body().path("kind").asText())).findFirst().orElseThrow();
            var report=fromReceipt(applied(exporter,"verify_export",obj().put("export_id",pack.id()),null),"verification_report");
            checkOriginalAxes(report.body(),"export after "+terminalOperation);
        }
        trace.put("terminal_checks","OPEN_WORK_INTERRUPTED_AND_PRIOR_EVIDENCE_UNCHANGED");
    }

    void snapshotAndRecover(String stage)throws Exception {
        idle();
        if(terminalOperation!=null){
            if(stage.equals(terminalStage)){
                stopAtStage(stage);
                throw new StageStopped();
            }
            return;
        }
        // Actual pause/resume at every persisted workflow stage, before taking the running crash image.
        var beforePause=evidenceRevisions();
        double pausedTime=loop.simTimeS();
        applied(presenter,"pause_run",obj().put("run_id",run),store.get("run",run).orElseThrow().revision());
        for(int i=0;i<20;i++)loop.wallStep();idle();
        assertThat(store.get("run",run).orElseThrow().body().path("state").asText()).isEqualTo("PAUSED");
        assertThat(loop.simTimeS()).as(stage+" paused scientific clock").isEqualTo(pausedTime);
        assertThat(evidenceRevisions()).as(stage+" pause preserves workflow evidence").isEqualTo(beforePause);
        applied(presenter,"resume_run",obj().put("run_id",run),store.get("run",run).orElseThrow().revision());
        assertThat(store.get("run",run).orElseThrow().body().path("state").asText()).isEqualTo("RUNNING");
        trace.withArray("pause_resume_stages").add(stage);
        Path copy=CoreSpringTest.TempFolders.create("fp-workflow-recover-"+stage+"-");
        // SQLite VACUUM INTO writes a consistent committed copy (WAL content included) without touching the source.
        jdbc.execute("VACUUM INTO '"+copy.resolve("fuelprint.db").toString().replace("'","''")+"'");
        Path copiedBlobs=Files.createDirectories(copy.resolve("blobs"));
        if(Files.exists(DATA.resolve("blobs")))try(var paths=Files.list(DATA.resolve("blobs"))){
            for(Path blob:paths.filter(Files::isRegularFile).toList())if(blob.getFileName().toString().matches("[0-9a-f]{64}"))Files.copy(blob,copiedBlobs.resolve(blob.getFileName()));
        }
        var immutable=evidenceRevisions();immutable.keySet().removeIf(k->k.startsWith("attempt/"));
        var missions=store.list("mission",run);var attempts=store.list("attempt",run);
        long originalRevisionTotal=jdbc.queryForObject("SELECT COUNT(*) FROM records",Long.class);
        var stageTrace=trace.withArray("recovered_snapshots").addObject().put("stage",stage)
                .put("scope","CONTEXT_RECOVERY: new real Spring context (profile real, non-web) on a consistent SQLite copy plus copied immutable blobs; not OS-process SIGKILL");
        try(var again=new SpringApplicationBuilder(fuelprint.FuelPrintApplication.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=real","--fp.data-dir="+copy,"--fp.loop.auto=false","--fp.field.safety-loop=false",
                        "--fp.alerts.sms-config="+copy.resolve("absent-sms.json"),"--spring.main.banner-mode=off")){
            var reopened=again.getBean(RecordStore.class);var restartedBus=again.getBean(CommandBus.class);
            for(int i=0;i<12;i++)restartedBus.awaitIdle();
            var runBody=reopened.get("run",run).orElseThrow().body();
            stageTrace.set("recovered_run",runBody);
            assertThat(runBody.path("state").asText()).as(stage+" run state").isEqualTo("PAUSED");
            assertThat(runBody.path("state_reason").asText()).as(stage+" run reason").isEqualTo("INTERRUPTED");
            for(var entry:immutable.entrySet()){var key=entry.getKey().split("/");assertThat(reopened.revisions(key[0],key[1])).as(stage+" immutable "+entry.getKey()).isEqualTo(entry.getValue());}
            for(StoredRecord m:missions)if(OPEN_MISSION.contains(m.body().path("state").asText()))
                assertThat(reopened.get("mission",m.id()).orElseThrow().body().path("state").asText()).as(stage+" open mission").isEqualTo("INTERRUPTED");
            for(StoredRecord a:attempts)if("COLLECTING".equals(a.body().path("state").asText()))
                assertThat(reopened.get("attempt",a.id()).orElseThrow().body().path("state").asText()).as(stage+" collecting attempt").isEqualTo("INTERRUPTED");
            assertThat(reopened.list("lease",run)).as(stage+" leases").allSatisfy(l->assertThat(l.body().path("state").asText()).isNotEqualTo("ACTIVE"));
            // Approval is never resurrected: an interrupted mission cannot be flown again.
            for(StoredRecord m:missions)if("APPROVED".equals(m.body().path("state").asText())){
                var station=again.getBean(CookieSessionService.class).createStation("recovery-operator",Set.of("field_operator"));
                var r=restartedBus.send(new Command("fp/1",UUID.randomUUID().toString(),"start_autopilot",run,null,obj().put("mission_id",m.id()).put("vehicle_id",m.body().path("vehicle_id").asText())),station);
                stageTrace.withArray("receipts").add((JsonNode)json.valueToTree(r));
                assertThat(r.applied()).as(stage+" interrupted approval cannot depart").isFalse();
            }
            if(stage.equals("export")){
                var pack=reopened.list("export",run).stream().filter(e->"ORIGINAL".equals(e.body().path("kind").asText())).findFirst().orElseThrow();
                var station=again.getBean(CookieSessionService.class).createStation("recovery-exporter",Set.of("exporter"));
                var receipt=restartedBus.send(new Command("fp/1",UUID.randomUUID().toString(),"verify_export",run,null,obj().put("export_id",pack.id())),station);
                stageTrace.withArray("receipts").add((JsonNode)json.valueToTree(receipt));
                assertThat(receipt.applied()).as("stored bytes verify after a new real context: "+receipt.reasonText()).isTrue();
                var axes=reopened.list("verification_report",run).stream().filter(v->receipt.recordRefs().stream().anyMatch(ref->ref.id().equals(v.id()))).findFirst().orElseThrow().body().path("axes");
                stageTrace.set("recovered_axes",axes);
                assertThat(axes.path("integrity").asText()).isEqualTo("PASS");assertThat(axes.path("lineage").asText()).isEqualTo("PASS");
            }
            stageTrace.put("result","PAUSED_INTERRUPTED_AND_EVIDENCE_UNCHANGED");
        }
        // The original context is untouched by the copy's recovery.
        assertThat(store.get("run",run).orElseThrow().body().path("state").asText()).as("original run still running").isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM records",Long.class)).as("original database unchanged by recovery").isEqualTo(originalRevisionTotal);
    }
    String missionId(StoredRecord r){return r.body().path("mission_id").asText();}
}
