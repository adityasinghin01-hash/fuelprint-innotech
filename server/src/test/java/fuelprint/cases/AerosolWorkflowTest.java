package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.*;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/** B4 through the real command bus: separate aerosol media, custody, lab QC, findings, sealing and offline verification. */
@SpringBootTest(properties = {"fp.loop.auto=false", "fp.field.safety-loop=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("real") @AutoConfigureMockMvc @Import(ExportWorkflowTest.Config.class)
class AerosolWorkflowTest {
    static final Path DATA = CoreSpringTest.TempFolders.create("fp-cases-aerosol-");
    static final Path KEYS = CoreSpringTest.TempFolders.create("fp-cases-aerosol-keys-");
    static final Path CONFIG = KEYS.resolve("evidence.json");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r) {
        r.add("fp.data-dir", DATA::toString);
        r.add("fp.cases.evidence-config", CONFIG::toString);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired RecordStore store;
    @Autowired CommandBus bus; @Autowired CookieSessionService sessions; @Autowired ExportWorkflowTest.TestMode mode;
    @Autowired AutomationBus automation;
    @org.springframework.test.context.bean.override.convention.TestBean(name = "clockControl", methodName = "testClock") ClockControl clock;
    static volatile double now = 1000;
    static ClockControl testClock() {
        return new ClockControl() { public double simTimeS() { return now; } public double timeScale() { return 1; }
            public void requestTimeScale(String r, double s) { } public void releaseTimeScale(String r) { } };
    }
    String run; Cookie presenter, reviewer, inspector, lab, exporter, otherInspector;
    static TestKeys key;
    static String id() { return UUID.randomUUID().toString(); }
    ObjectNode obj() { return json.createObjectNode(); }
    Cookie cookie(String station, String... roles) { return new Cookie(CookieSessionService.COOKIE, sessions.createStation(station, Set.of(roles)).sessionId()); }

    @BeforeAll static void keys() { key = TestKeys.write(KEYS, "signer"); }
    @AfterAll static void cleanup() throws Exception { Files.deleteIfExists(CONFIG); }

    @BeforeEach void fresh() throws Exception {
        bus.awaitIdle(); mode.active = false; now = 1000;
        Files.writeString(CONFIG, "{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + key.privatePem + "\",\"public_key_path\":\""
                + key.publicPem + "\",\"trust_anchors_path\":\"" + TestKeys.trustFile(KEYS, key.fingerprint()) + "\"}");
        presenter = cookie("presenter", "presenter"); reviewer = cookie("cases", "case_reviewer");
        inspector = cookie("collector", "inspector_demo"); lab = cookie("lab", "lab_demo"); exporter = cookie("export", "exporter");
        otherInspector = cookie("other", "inspector_demo");
        ok(send("reset_run", obj().put("preset_id", "SC1-NIGHT-PLUME").put("mode", "TEACHING"), null, presenter));
        run = store.listAll("run").stream().filter(x -> x.body().path("state").asText().equals("RUNNING")).reduce((a, b) -> b).orElseThrow().id();
        ok(send("change_automation", obj().put("setting", "auto_seal").put("value", false), null, presenter));
    }

    JsonNode send(String type, ObjectNode p, Long rev, Cookie who) throws Exception { return send(new Command("fp/1", id(), type, run, rev, p), who); }
    JsonNode send(Command c, Cookie who) throws Exception {
        var response = mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c)))
                .andExpect(status().isOk()).andReturn().getResponse();
        bus.awaitIdle(); return json.readTree(response.getContentAsString());
    }
    void ok(JsonNode r) { assertThat(r.path("status").asText()).as(r.toString()).isEqualTo("APPLIED"); }
    void rejected(JsonNode r, String code) { assertThat(r.path("status").asText()).as(r.toString()).isEqualTo("REJECTED"); if (code != null) assertThat(r.path("reason_code").asText()).as(r.toString()).isEqualTo(code); }
    String seed(String type, ObjectNode b) throws Exception {
        String k = id(); var p = obj().put("type", type).put("id", k); p.set("fields", b);
        ok(send("test_export_fixture", p, null, presenter)); return k;
    }
    String screening() throws Exception {
        String inv = seed("investigation", obj().put("state", "EVIDENCE_BACK"));
        String survey = seed("survey", obj().put("investigation_id", inv));
        String mission = seed("mission", obj().put("survey_id", survey).put("state", "COMPLETE"));
        String attempt = seed("attempt", obj().put("mission_id", mission).put("state", "COMPLETE"));
        var b = obj().put("mission_id", mission).put("attempt_id", attempt).put("channel", "SO2_ppb").put("named_use", "SYNTHETIC_ONLINE_SCREENING").put("decision", "LIMITED");
        b.putArray("reasons").add("FLOW_OR_SCOPE_LIMIT"); b.putObject("summary").putNull("mean_enhancement_ppb");
        return seed("screening_record", b);
    }
    StoredRecord caseRecord() { return store.list("case", run).get(0); }
    StoredRecord media() { return store.list("media", run).get(0); }
    void newCase() throws Exception {
        var intake = obj().put("purpose", "Teaching investigation only"); intake.putArray("screening_record_ids").add(screening());
        ok(send("create_case", intake, null, reviewer));
    }
    ObjectNode mediaPayload(String validS, String status) {
        var p = obj().put("case_id", caseRecord().id()).put("material", "SYNTHETIC_UNCLASSIFIED_AEROSOL").put("method_id", "TEACH-AEROSOL-S-1")
                .put("media_type", "FILTER").put("container", "cassette-1").put("seal_id", "seal-A1").put("location_label", "Teaching fence line, north gate");
        p.putObject("collection").put("start_sim_s", 100).put("end_sim_s", 800).put("valid_s", new BigDecimal(validS)).put("flow_L_min", 2).put("status", status);
        return p;
    }
    ObjectNode qc() {
        return obj().put("gross_ug", new BigDecimal("1.2")).put("blank_ug", new BigDecimal("0.01")).put("dup_a_ug", new BigDecimal("1.0"))
                .put("dup_b_ug", new BigDecimal("1.1")).put("spike_added_ug", new BigDecimal("1.0")).put("spike_recovered_ug", new BigDecimal("0.95"));
    }
    JsonNode mediaLab(ObjectNode qc) throws Exception {
        var p = obj().put("media_id", media().id()).put("method_id", "TEACH-AEROSOL-S-1"); p.set("qc_inputs", qc);
        return send("request_media_lab", p, media().revision(), lab);
    }
    StoredRecord lastMediaLab() { var l = store.list("media_lab_result", run); return l.get(l.size() - 1); }
    JsonNode finding(String outcome, StoredRecord... cited) throws Exception {
        var p = obj().put("case_id", caseRecord().id()).put("outcome", outcome); var refs = p.putArray("cited_refs");
        for (var r : cited) refs.add(CasesRecords.ref(r));
        p.putArray("alternatives").add("Another unregistered source may explain the filter loading.");
        p.putArray("limitations").add("Synthetic observation only");
        return send("revise_finding", p, caseRecord().revision(), reviewer);
    }

    @Test void mediaAreASeparateHumanRecordedIdentityWithNoInventedFieldAttemptOrSiteLabel() throws Exception {
        newCase();
        ok(send("create_media", mediaPayload("600", "COMPLETE"), caseRecord().revision(), inspector));
        var m = media().body();
        assertThat(m.path("attempt_ref").isNull()).isTrue();
        assertThat(m.path("attempt_ref_reason").asText()).isEqualTo("FIELD_AEROSOL_CAPTURE_NOT_AVAILABLE");
        assertThat(m.path("origin").asText()).isEqualTo("SYNTHETIC_TEACHING");
        assertThat(m.path("input_basis").asText()).isEqualTo("HUMAN_RECORDED_SYNTHETIC_TEACHING");
        assertThat(m.path("collector_id").asText()).isEqualTo("inspector_demo@collector");
        assertThat(m.has("site_id")).isFalse();
        assertThat(m.path("collection_computed").path("volume_L").asDouble()).isEqualTo(20.0);
        assertThat(m.path("case_ref").path("id").asText()).isEqualTo(caseRecord().id());
        assertThat(store.list("specimen", run)).isEmpty();
        assertThat(store.list("custody_event", run)).isEmpty();
        assertThat(store.list("media_custody_event", run)).hasSize(1);
        assertThat(caseRecord().body().path("state").asText()).isEqualTo("SPECIMEN");
        assertThat(caseRecord().body().path("media_ids").get(0).asText()).isEqualTo(media().id());
    }

    @Test void createMediaRefusesWrongRolesAutomationFieldsLabelsAndImpossibleCollections() throws Exception {
        newCase();
        long rev = caseRecord().revision();
        for (Cookie wrong : List.of(reviewer, lab, exporter, presenter)) rejected(send("create_media", mediaPayload("600", "COMPLETE"), rev, wrong), "NOT_ALLOWED");
        mode.active = true;
        try { assertThat(automation.submit("create_media", mediaPayload("600", "COMPLETE"), run, null).join().reasonCode()).isEqualTo("NOT_ALLOWED"); }
        finally { mode.active = false; }
        for (String field : List.of("attempt_ref", "site_id", "mass_mg", "volume_L"))
            rejected(send("create_media", mediaPayload("600", "COMPLETE").put(field, "x"), rev, inspector), "INVALID");
        rejected(send("create_media", mediaPayload("600", "COMPLETE").put("location_label", "Outside S02 stack"), rev, inspector), "INVALID");
        rejected(send("create_media", mediaPayload("600", "COMPLETE").put("material", "SYNTHETIC_COAL_COKE_LIKE"), rev, inspector), "INVALID");
        rejected(send("create_media", mediaPayload("600", "COMPLETE").put("method_id", "TEACH-FUEL-S-1"), rev, inspector), "INVALID");
        rejected(send("create_media", mediaPayload("701", "COMPLETE"), rev, inspector), "INVALID");          // valid > window
        now = 500;
        rejected(send("create_media", mediaPayload("600", "COMPLETE"), rev, inspector), "INVALID");          // end after the clock
        now = 1000;
        rejected(send("create_media", mediaPayload("600", "COMPLETE"), rev - 1, inspector), "STALE_REVISION");
        var p = mediaPayload("600", "COMPLETE"); p.put("case_id", id());
        rejected(send("create_media", p, null, inspector), "INVALID");
        assertThat(store.list("media", run)).isEmpty();
        // Repeated command id returns the first receipt and creates one media item.
        var c = new Command("fp/1", id(), "create_media", run, rev, mediaPayload("600", "COMPLETE"));
        var first = send(c, inspector); var again = send(c, inspector);
        assertThat(again).isEqualTo(first);
        assertThat(store.list("media", run)).hasSize(1);
    }

    @Test void mediaCustodyIsAppendOnlyAndSeparateFromFuelCustody() throws Exception {
        newCase();
        ok(send("create_media", mediaPayload("600", "COMPLETE"), caseRecord().revision(), inspector));
        var transfer = obj().put("media_id", media().id()).put("to_holder", "lab_demo@lab").put("seal_state", "INTACT").put("event_time_utc", "2026-10-20T10:00:00.000Z");
        transfer.putArray("exceptions");
        rejected(send("append_media_custody", transfer, media().revision(), otherInspector), "NOT_ALLOWED");
        var first = store.list("media_custody_event", run).get(0);
        ok(send("append_media_custody", transfer, media().revision(), inspector));
        assertThat(media().body().path("current_holder").asText()).isEqualTo("lab_demo@lab");
        assertThat(store.list("media_custody_event", run)).hasSize(2);
        assertThat(store.get("media_custody_event", first.id(), 1).orElseThrow().body()).isEqualTo(first.body());
        var broken = obj().put("media_id", media().id()).put("to_holder", "lab_demo@lab").put("seal_state", "BROKEN").put("event_time_utc", "2026-10-20T10:01:00.000Z");
        broken.putArray("exceptions").add("BROKEN_SEAL");
        ok(send("append_media_custody", broken, media().revision(), lab));
        assertThat(media().body().path("custody_exceptions").get(0).asText()).isEqualTo("BROKEN_SEAL");
        // A fuel custody command cannot address media, and a media command cannot address a fuel specimen.
        var fuel = obj().put("specimen_id", media().id()).put("to_holder", "x@y").put("seal_state", "INTACT").put("event_time_utc", "2026-10-20T10:02:00.000Z");
        fuel.putArray("exceptions");
        rejected(send("append_custody", fuel, null, lab), "INVALID");
        assertThat(store.list("custody_event", run)).isEmpty();
    }

    @Test void mediaLabResultUsableLimitedAndFailedFollowTheProfileAndFuelLabRefusesMedia() throws Exception {
        newCase();
        ok(send("create_media", mediaPayload("600", "COMPLETE"), caseRecord().revision(), inspector));
        now = 1100;
        ok(mediaLab(qc()));
        var usable = lastMediaLab().body();
        assertThat(usable.path("qc_status").asText()).isEqualTo("PASS");
        assertThat(usable.path("result_use").asText()).isEqualTo("USABLE");
        assertThat(usable.path("hold_time_s").asDouble()).isEqualTo(300.0);
        assertThat(usable.path("calculation_precision").asText()).isEqualTo("DECIMAL128_HALF_EVEN");
        assertThat(usable.path("computed_decimal").path("concentration_ug_m3").asText()).isEqualTo("59.5");
        assertThat(usable.path("custody_ref").path("type").asText()).isEqualTo("media_custody_event");
        assertThat(usable.path("limitations").toString()).contains("no airborne capture was simulated");
        ok(mediaLab(qc().put("gross_ug", new BigDecimal("0.05"))));
        assertThat(lastMediaLab().body().path("result_use").asText()).isEqualTo("LIMITED_BELOW_LOQ");
        assertThat(lastMediaLab().body().path("limitations").toString()).contains("limited, not zero");
        now = 1100.001;
        ok(mediaLab(qc()));
        assertThat(lastMediaLab().body().path("qc_reasons").toString()).contains("HOLD_TIME_EXCEEDED");
        assertThat(lastMediaLab().body().path("result_use").asText()).isEqualTo("NOT_USABLE");
        // Weights, mass and unknown inputs are refused; role and automation guarded.
        rejected(mediaLab(qc().put("filter_pre_mg", 1)), "INVALID");
        var p = obj().put("media_id", media().id()).put("method_id", "TEACH-AEROSOL-S-1"); p.set("qc_inputs", qc());
        rejected(send("request_media_lab", p, media().revision(), inspector), "NOT_ALLOWED");
        rejected(send("request_media_lab", p.deepCopy().put("method_id", "TEACH-FUEL-S-1"), media().revision(), lab), "INVALID");
        var fuel = obj().put("specimen_id", media().id()).put("method_id", "TEACH-FUEL-S-1"); fuel.putObject("qc_inputs").put("gross_ug", 2010);
        rejected(send("request_lab", fuel, null, lab), "INVALID");
        assertThat(store.list("lab_result", run)).isEmpty();
        assertThat(caseRecord().body().path("state").asText()).isEqualTo("LAB");
        assertThat(caseRecord().body().path("media_lab_result_ids")).hasSize(3);
    }

    @Test void findingsUseOnlyUsableMediaResultsForSupportAndFailedOnesForRejection() throws Exception {
        newCase();
        ok(send("create_media", mediaPayload("600", "COMPLETE"), caseRecord().revision(), inspector));
        ok(mediaLab(qc().put("gross_ug", new BigDecimal("0.05"))));
        var limited = lastMediaLab();
        rejected(finding("SUPPORTED_WITHIN_SCOPE", limited), "INVALID");
        rejected(finding("REJECTED_FOR_USE", limited), "INVALID");
        ok(finding("INCONCLUSIVE", limited));
        ok(mediaLab(qc().put("blank_ug", new BigDecimal("0.5"))));
        ok(finding("REJECTED_FOR_USE", lastMediaLab()));
        ok(mediaLab(qc()));
        ok(finding("SUPPORTED_WITHIN_SCOPE", lastMediaLab()));
        // A media lab result of another case cannot be cited.
        var otherCase = obj().put("purpose", "Second teaching case"); otherCase.putArray("screening_record_ids").add(screening());
        ok(send("create_case", otherCase, null, reviewer));
        var second = store.list("case", run).stream().filter(c -> !c.id().equals(media().body().path("case_id").asText())).findFirst().orElseThrow();
        var p = obj().put("case_id", second.id()).put("outcome", "INCONCLUSIVE");
        p.putArray("cited_refs").add(CasesRecords.ref(lastMediaLab()));
        p.putArray("alternatives"); p.putArray("limitations").add("Synthetic observation only");
        rejected(send("revise_finding", p, second.revision(), reviewer), "INVALID");
    }

    @Test void sealedAerosolPackVerifiesOnServerAndOfflineAndATamperedCopyFails() throws Exception {
        newCase();
        ok(send("create_media", mediaPayload("600", "COMPLETE"), caseRecord().revision(), inspector));
        var transfer = obj().put("media_id", media().id()).put("to_holder", "lab_demo@lab").put("seal_state", "INTACT").put("event_time_utc", "2026-10-20T10:00:00.000Z");
        transfer.putArray("exceptions");
        ok(send("append_media_custody", transfer, media().revision(), inspector));
        ok(mediaLab(qc()));
        ok(finding("SUPPORTED_WITHIN_SCOPE", lastMediaLab()));
        ok(send("seal_export", obj().put("case_id", caseRecord().id()).put("case_revision", caseRecord().revision()), caseRecord().revision(), exporter));
        var export = store.list("export", run).get(0);
        List<String> paths = new ArrayList<>();
        export.body().path("members").forEach(m -> paths.add(m.path("path").asText()));
        assertThat(paths).anyMatch(x -> x.contains("/media/")).anyMatch(x -> x.contains("/media_lab_result/"))
                .filteredOn(x -> x.contains("/media_custody_event/")).hasSize(2);
        assertThat(export.body().path("custody_summary").path("status").asText()).isEqualTo("PASS");
        ok(send("verify_export", obj().put("export_id", export.id()), null, exporter));
        var report = store.list("verification_report", run).get(0).body();
        assertThat(report.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(report.path("axes").path("lineage").asText()).as(report.path("reasons").toString()).isEqualTo("PASS");
        assertThat(report.path("axes").path("custody_documentation").asText()).isEqualTo("PASS");
        assertThat(report.path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
        var zip = mvc.perform(get("/api/exports/" + export.id() + "/pack.zip?run_id=" + run).cookie(exporter)).andReturn().getResponse();
        assertThat(zip.getStatus()).isEqualTo(200);
        Path pack = Files.createTempFile(KEYS, "aerosol-", ".zip"); Files.write(pack, zip.getContentAsByteArray());
        var offline = EvidenceOffline.verify(pack, EvidenceTrust.of(List.of(key.fingerprint()), "CLI_ARGUMENT"), false);
        assertThat(offline.exitCode()).as(offline.json().toString()).isZero();
        String labPath = paths.stream().filter(x -> x.contains("/media_lab_result/")).findFirst().orElseThrow();
        ok(send("tamper_copy", obj().put("export_id", export.id()).put("member_path", labPath), null, exporter));
        var copy = store.list("export", run).stream().filter(x -> "TAMPER_DEMO_COPY".equals(x.body().path("state").asText())).findFirst().orElseThrow();
        var copyZip = mvc.perform(get("/api/exports/" + copy.id() + "/pack.zip?run_id=" + run).cookie(exporter)).andReturn().getResponse();
        Path bad = Files.createTempFile(KEYS, "aerosol-bad-", ".zip"); Files.write(bad, copyZip.getContentAsByteArray());
        var failed = EvidenceOffline.verify(bad, EvidenceTrust.of(List.of(key.fingerprint()), "CLI_ARGUMENT"), false);
        assertThat(failed.exitCode()).isEqualTo(5);
        assertThat(failed.json().path("member_checks").toString()).contains(labPath);
        assertThat(EvidenceOffline.verify(pack, EvidenceTrust.of(List.of(key.fingerprint()), "CLI_ARGUMENT"), false).exitCode()).isZero();
        // No supported finding is ever created automatically from passing QC.
        assertThat(store.list("finding", run).get(0).body().path("reviewed_by").asText()).isEqualTo("case_reviewer@cases");
    }

    @Test void aMediaCustodyExceptionMakesTheSealedPackLimited() throws Exception {
        newCase();
        ok(send("create_media", mediaPayload("600", "COMPLETE"), caseRecord().revision(), inspector));
        var broken = obj().put("media_id", media().id()).put("to_holder", "lab_demo@lab").put("seal_state", "BROKEN").put("event_time_utc", "2026-10-20T10:00:00.000Z");
        broken.putArray("exceptions").add("BROKEN_SEAL");
        ok(send("append_media_custody", broken, media().revision(), inspector));
        ok(mediaLab(qc()));
        ok(finding("INCONCLUSIVE", lastMediaLab()));
        ok(send("seal_export", obj().put("case_id", caseRecord().id()).put("case_revision", caseRecord().revision()), caseRecord().revision(), exporter));
        var export = store.list("export", run).get(0);
        assertThat(export.body().path("custody_summary").path("status").asText()).isEqualTo("LIMITED");
        ok(send("verify_export", obj().put("export_id", export.id()), null, exporter));
        assertThat(store.list("verification_report", run).get(0).body().path("axes").path("custody_documentation").asText()).isEqualTo("LIMITED");
    }
}
