package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.api.RecordSaved;
import fuelprint.api.ServerMode;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.*;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 6 through the real command bus and real session cookies: seal_export, verify_export, tamper_copy and the
 * after-commit finding to seal to verify automation. Public-record prerequisites are supplied the same way as in
 * the Task 4/5 tests (a presenter-only fixture command); everything else is an ordinary command.
 */
@SpringBootTest(properties = {"fp.loop.auto=false", "fp.field.safety-loop=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("real") @AutoConfigureMockMvc @Import(ExportWorkflowTest.Config.class)
class ExportWorkflowTest {
    static final Path DATA = CoreSpringTest.TempFolders.create("fp-cases-export-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r) { r.add("fp.data-dir", DATA::toString); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @MockitoSpyBean RecordStore store;
    @Autowired CommandBus bus; @Autowired CookieSessionService sessions; @Autowired AutomationBus automation;
    @Autowired TestMode mode; @Autowired CommandLog commands; @Autowired BlobStore blobs;
    @Autowired ApplicationEventPublisher publisher;
    @org.springframework.test.context.bean.override.convention.TestBean(name = "clockControl", methodName = "testClock") ClockControl clock;
    String run; Cookie presenter, reviewer, inspector, lab, exporter, other;
    static ClockControl testClock() {
        return new ClockControl() { public double simTimeS() { return 42.5; } public double timeScale() { return 1; }
            public void requestTimeScale(String r, double s) { } public void releaseTimeScale(String r) { } };
    }
    static String id() { return UUID.randomUUID().toString(); }
    ObjectNode obj() { return json.createObjectNode(); }
    Cookie cookie(String station, String... roles) { return new Cookie(CookieSessionService.COOKIE, sessions.createStation(station, Set.of(roles)).sessionId()); }

    @BeforeEach void fresh() throws Exception {
        Mockito.reset(store);
        bus.awaitIdle(); mode.active = false;
        presenter = cookie("presenter", "presenter"); reviewer = cookie("cases", "case_reviewer");
        inspector = cookie("collector", "inspector_demo"); lab = cookie("lab", "lab_demo");
        exporter = cookie("export", "exporter"); other = cookie("other", "inspector_demo");
        assertThat(send("reset_run", obj().put("preset_id", "SC1-NIGHT-PLUME").put("mode", "TEACHING"), null, presenter).path("status").asText()).isEqualTo("APPLIED");
        run = store.listAll("run").stream().filter(x -> x.body().path("state").asText().equals("RUNNING")).reduce((a, b) -> b).orElseThrow().id();
        setSeal(true);
    }
    @AfterEach void restore() { Mockito.reset(store); mode.active = false; }

    JsonNode send(String type, ObjectNode p, Long rev, Cookie who) throws Exception { return send(new Command("fp/1", id(), type, run, rev, p), who); }
    JsonNode send(Command c, Cookie who) throws Exception {
        var response = mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c)))
                .andExpect(status().isOk()).andReturn().getResponse();
        bus.awaitIdle(); var r = json.readTree(response.getContentAsString()); System.out.println("EXPORT_RECEIPT " + r); return r;
    }
    void setSeal(boolean on) throws Exception {
        assertThat(send("change_automation", obj().put("setting", "auto_seal").put("value", on), null, presenter).path("status").asText()).isEqualTo("APPLIED");
    }
    String seed(String type, ObjectNode b) throws Exception { return seed(type, b, run); }
    String seed(String type, ObjectNode b, String runId) throws Exception {
        String key = id(); var p = obj().put("type", type).put("id", key); p.set("fields", b);
        assertThat(send(new Command("fp/1", id(), "test_export_fixture", runId, null, p), presenter).path("status").asText()).isEqualTo("APPLIED"); return key;
    }
    void update(StoredRecord r, ObjectNode b) throws Exception {
        RecordChange.COMMON_FIELDS.forEach(b::remove); var p = obj().put("type", r.type()).put("id", r.id()).put("based", r.revision()); p.set("fields", b);
        assertThat(send("test_export_fixture", p, null, presenter).path("status").asText()).isEqualTo("APPLIED");
    }
    /** Waits (bounded) for a condition that depends on queued automatic commands. */
    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < end) { bus.awaitIdle(); if (condition.getAsBoolean()) return; Thread.sleep(40); }
        throw new AssertionError("timed out waiting for the automatic steps");
    }
    void quiet() throws Exception { for (int i = 0; i < 6; i++) { bus.awaitIdle(); Thread.sleep(40); } }

    // ---- public-record prerequisites ----
    String investigation() throws Exception { return seed("investigation", obj().put("state", "EVIDENCE_BACK")); }
    String evidence(String inv, String decision) throws Exception {
        String survey = seed("survey", obj().put("investigation_id", inv)); String mission = seed("mission", obj().put("survey_id", survey).put("state", "COMPLETE"));
        return screening(mission, seed("attempt", obj().put("mission_id", mission).put("state", "COMPLETE")), decision);
    }
    String screening(String mission, String attempt, String decision) throws Exception {
        var b = obj().put("mission_id", mission).put("attempt_id", attempt).put("channel", "SO2_ppb").put("named_use", "SYNTHETIC_ONLINE_SCREENING").put("decision", decision);
        b.putArray("reasons").add(decision.equals("VALID") ? "WITHIN_TEACHING_SCOPE" : "FLOW_OR_SCOPE_LIMIT"); b.putObject("summary").putNull("mean_enhancement_ppb"); return seed("screening_record", b);
    }
    ObjectNode intake(String... evidence) { var p = obj().put("purpose", "Teaching investigation only"); p.set("screening_record_ids", json.valueToTree(List.of(evidence))); return p; }
    StoredRecord caseRecord() { return store.list("case", run).get(0); }
    StoredRecord specimen() { return store.list("specimen", run).get(0); }
    StoredRecord screen() { return store.get("screening_record", caseRecord().body().path("screening_record_ids").get(0).asText()).orElseThrow(); }
    ObjectNode material() { return obj().put("case_id", caseRecord().id()).put("site_id", "S02").put("material", "SYNTHETIC_COAL_COKE_LIKE").put("method_id", "TEACH-FUEL-S-1").put("container", "jar-1").put("seal_id", "seal-1").put("mass_mg", 100); }
    ObjectNode transfer(String holder, String seal, String... ex) { var p = obj().put("specimen_id", specimen().id()).put("to_holder", holder).put("seal_state", seal).put("event_time_utc", "2026-10-19T10:00:00.000Z"); p.set("exceptions", json.valueToTree(List.of(ex))); return p; }
    ObjectNode qc() { return obj().put("gross_ug", 2010).put("blank_ug", 10).put("dup_a_pct", 2.0).put("dup_b_pct", 2.2).put("spike_added_ug", 100).put("spike_recovered_ug", 90); }
    ObjectNode finding(String outcome, StoredRecord... records) {
        var p = obj().put("case_id", caseRecord().id()).put("outcome", outcome); var refs = p.putArray("cited_refs");
        for (var r : records) refs.add(obj().put("type", r.type()).put("id", r.id()).put("revision", r.revision()));
        p.putArray("alternatives"); p.putArray("limitations").add("Synthetic observation only"); return p;
    }
    void ok(JsonNode receipt) { assertThat(receipt.path("status").asText()).as(receipt.toString()).isEqualTo("APPLIED"); }

    /** Case with a specimen, a passing lab result, optionally a documented broken-seal custody step; no finding yet. */
    void labbed(boolean custodyException) throws Exception {
        ok(send("create_case", intake(evidence(investigation(), "VALID")), null, reviewer));
        ok(send("create_specimen", material(), caseRecord().revision(), inspector));
        if (custodyException) ok(send("append_custody", transfer("lab_demo@lab", "BROKEN", "BROKEN_SEAL"), null, inspector));
        var request = obj().put("specimen_id", specimen().id()).put("method_id", "TEACH-FUEL-S-1"); request.set("qc_inputs", qc());
        ok(send("request_lab", request, specimen().revision(), lab));
    }
    void findingSaved() throws Exception {
        ok(send("revise_finding", finding("SUPPORTED_WITHIN_SCOPE", screen(), store.list("lab_result", run).get(0)), caseRecord().revision(), reviewer));
    }
    /** A case in FINDING state, ready to seal. */
    void ready(boolean custodyException) throws Exception { labbed(custodyException); findingSaved(); assertThat(caseRecord().body().path("state").asText()).isEqualTo("FINDING"); }

    ObjectNode sealPayload() { return obj().put("case_id", caseRecord().id()).put("case_revision", caseRecord().revision()); }
    JsonNode seal() throws Exception { return send("seal_export", sealPayload(), caseRecord().revision(), exporter); }
    StoredRecord theExport() { var l = store.list("export", run).stream().filter(e -> "SEALED".equals(e.body().path("state").asText())).toList(); assertThat(l).hasSize(1); return l.get(0); }
    List<StoredRecord> reports() { return store.list("verification_report", run); }
    JsonNode verify(String exportId, Cookie who) throws Exception { return send("verify_export", obj().put("export_id", exportId), null, who); }
    StoredRecord lastReport() { var l = reports(); return l.get(l.size() - 1); }
    long autoCount(String type) { return commands.automationEntries(run, Integer.MAX_VALUE).stream().filter(e -> e.commandType().equals(type)).count(); }
    long blobCount() throws Exception { try (Stream<Path> s = Files.list(DATA.resolve("blobs"))) { return s.filter(p -> !p.getFileName().toString().startsWith(".")).count(); } }
    static String sha(byte[] b) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); } }
    Path blobFile(String sha) { return DATA.resolve("blobs").resolve(sha); }
    String memberPath(StoredRecord export, String type) {
        for (JsonNode m : export.body().path("members")) if (m.path("path").asText().contains("/" + type + "/")) return m.path("path").asText();
        throw new AssertionError("no " + type + " member");
    }
    String locator(StoredRecord export, String path) {
        for (JsonNode m : export.body().path("members")) if (m.path("path").asText().equals(path)) return m.path("sha256").asText();
        throw new AssertionError("no locator for " + path);
    }
    Map<String, byte[]> blobSnapshot(StoredRecord export) throws Exception {
        Map<String, byte[]> m = new TreeMap<>();
        m.put("manifest", Files.readAllBytes(blobFile(export.body().path("manifest").path("sha256").asText())));
        for (JsonNode x : export.body().path("members")) m.put(x.path("path").asText(), Files.readAllBytes(blobFile(x.path("sha256").asText())));
        return m;
    }
    /** JSON equality that treats 100 and 100.0 as the same number (the canonical form writes the shortest decimal). */
    static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
        if (a.isObject() && b.isObject()) { if (a.size() != b.size()) return false; var it = a.fieldNames(); while (it.hasNext()) { String k = it.next(); if (!b.has(k) || !same(a.get(k), b.get(k))) return false; } return true; }
        if (a.isArray() && b.isArray()) { if (a.size() != b.size()) return false; for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false; return true; }
        return a.equals(b);
    }

    // =====================================================================================================
    // C14 sealing
    // =====================================================================================================

    @Test void exporterSealsExactStoredBytesAndTheCaseBecomesSealedWithItsFindingPinKept() throws Exception {
        ready(false);
        var before = caseRecord(); var pinned = before.body().path("finding_ref");
        var receipt = seal(); ok(receipt);
        assertThat(receipt.path("actor").asText()).isEqualTo("exporter@export"); assertThat(receipt.path("automatic").asBoolean()).isFalse();
        var export = theExport(); var e = export.body();
        assertThat(e.path("state").asText()).isEqualTo("SEALED"); assertThat(e.path("case_id").asText()).isEqualTo(before.id());
        assertThat(e.path("case_revision").asLong()).isEqualTo(before.revision()); assertThat(e.path("signature").isNull()).isTrue();
        byte[] manifestBytes = blobs.get(e.path("manifest").path("sha256").asText());
        assertThat(sha(manifestBytes)).isEqualTo(e.path("manifest_sha256").asText());
        assertThat(manifestBytes.length).isEqualTo(e.path("manifest_length").asInt());
        JsonNode manifest = json.readTree(manifestBytes);
        assertThat(manifest.fieldNames()).toIterable().containsExactly("case_id", "case_revision", "created_utc", "limitations", "members", "schema");
        assertThat(manifest.path("schema").asText()).isEqualTo("fp.evidence/1");
        assertThat(manifest.path("case_id").asText()).isEqualTo(before.id()); assertThat(manifest.path("case_revision").asLong()).isEqualTo(before.revision());
        assertThat(new String(manifestBytes, StandardCharsets.UTF_8)).startsWith("{\"case_id\":\"" + before.id() + "\",\"case_revision\":" + before.revision() + ",\"created_utc\":\"");
        assertThat(manifest.path("members")).isEqualTo(e.path("members"));
        List<String> paths = new ArrayList<>(); manifest.path("members").forEach(m -> paths.add(m.path("path").asText()));
        assertThat(paths).isSorted().doesNotHaveDuplicates();
        for (JsonNode m : manifest.path("members")) {
            byte[] bytes = blobs.get(m.path("sha256").asText());
            assertThat(sha(bytes)).isEqualTo(m.path("sha256").asText()); assertThat(bytes.length).isEqualTo(m.path("length").asInt());
            var parts = m.path("path").asText().replace("payload/records/", "").replace(".json", "").split("/|-r(?=[0-9]+$)");
            var exact = store.get(parts[0], parts[1], Long.parseLong(parts[2])).orElseThrow();
            assertThat(same(json.readTree(bytes), exact.body())).as(m.path("path").asText()).isTrue();
            assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\n").doesNotContain("\": ");
        }
        assertThat(paths).anyMatch(p -> p.contains("/finding/")).anyMatch(p -> p.contains("/lab_result/")).anyMatch(p -> p.contains("/specimen/"))
                .anyMatch(p -> p.contains("/custody_event/")).anyMatch(p -> p.contains("/screening_record/")).anyMatch(p -> p.contains("/case/"));
        var sealedCase = caseRecord();
        assertThat(sealedCase.revision()).isEqualTo(before.revision() + 1); assertThat(sealedCase.body().path("state").asText()).isEqualTo("SEALED");
        assertThat(sealedCase.body().path("finding_ref")).isEqualTo(pinned); assertThat(sealedCase.body().path("export_id").asText()).isEqualTo(export.id());
        assertThat(store.get("case", before.id(), before.revision()).orElseThrow().body().path("state").asText()).isEqualTo("FINDING");
        assertThat(store.get("finding", pinned.path("id").asText(), pinned.path("revision").asLong())).isPresent();
    }

    @Test void inconclusiveFindingCanExportExactLegalUnderflowInput() throws Exception {
        labbed(false);
        var request = obj().put("specimen_id", specimen().id()).put("method_id", "TEACH-FUEL-S-1");
        request.set("qc_inputs", qc().put("gross_ug", new java.math.BigDecimal("1e-1000")));
        ok(send("request_lab", request, specimen().revision(), lab));
        var labs = store.list("lab_result", run);
        var tiny = labs.get(labs.size()-1);
        assertThat(tiny.body().path("raw").path("gross_ug").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("1e-1000"));
        ok(send("revise_finding", finding("INCONCLUSIVE", tiny), caseRecord().revision(), reviewer));
        ok(seal());
        var pack = theExport();
        boolean retained = false;
        for (JsonNode member : pack.body().path("members")) {
            if (!member.path("path").asText().contains("/lab_result/"+tiny.id()+"-")) continue;
            JsonNode body = fuelprint.core.store.RecordStore.exactReader(json)
                    .readTree(Files.readAllBytes(blobFile(member.path("sha256").asText())));
            assertThat(body.path("raw").path("gross_ug").decimalValue())
                    .isEqualByComparingTo(new java.math.BigDecimal("1e-1000"));
            retained = true;
        }
        assertThat(retained).isTrue();
        ok(verify(pack.id(), exporter));
        assertThat(lastReport().body().path("axes").path("integrity").asText()).isEqualTo("PASS");
    }

    @Test void sealIsRefusedUntilTheCaseHasAFindingAndWritesNothing() throws Exception {
        ok(send("create_case", intake(evidence(investigation(), "VALID")), null, reviewer));
        for (String state : List.of("INTAKE", "SPECIMEN", "LAB")) {
            update(caseRecord(), caseRecord().body().put("state", state));
            long blobsBefore = blobCount();
            var r = seal(); assertThat(r.path("status").asText()).isEqualTo("REJECTED"); assertThat(r.path("reason_code").asText()).isEqualTo("WRONG_STATE");
            assertThat(store.list("export", run)).isEmpty(); assertThat(blobCount()).isEqualTo(blobsBefore);
        }
    }

    @Test void roleStaleRevisionRetryAndSecondSealAreGuarded() throws Exception {
        ready(false);
        var p = sealPayload(); long rev = caseRecord().revision();
        for (Cookie wrong : List.of(reviewer, presenter, lab, inspector))
            assertThat(send("seal_export", p, null, wrong).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(send("seal_export", p, rev - 1, exporter).path("reason_code").asText()).isEqualTo("STALE_REVISION");
        assertThat(send("seal_export", obj().put("case_id", caseRecord().id()).put("case_revision", rev - 1), null, exporter).path("reason_code").asText()).isEqualTo("STALE_REVISION");
        for (ObjectNode bad : List.of(obj().put("case_revision", rev), obj().put("case_id", caseRecord().id()), obj().put("case_id", caseRecord().id()).put("case_revision", String.valueOf(rev)),
                obj().put("case_id", "S02").put("case_revision", rev), sealPayload().put("extra", true), obj().put("case_id", id()).put("case_revision", rev)))
            assertThat(send("seal_export", bad, null, exporter).path("status").asText()).as(bad.toString()).isEqualTo("REJECTED");
        assertThat(store.list("export", run)).isEmpty();
        var command = new Command("fp/1", id(), "seal_export", run, rev, p);
        var first = send(command, exporter); ok(first);
        assertThat(send(command, exporter)).isEqualTo(first); assertThat(store.list("export", run)).hasSize(1);
        var again = send("seal_export", sealPayload(), null, exporter);
        assertThat(again.path("reason_code").asText()).isEqualTo("WRONG_STATE"); assertThat(store.list("export", run)).hasSize(1);
        assertThat(send("revise_finding", finding("INCONCLUSIVE"), null, reviewer).path("status").asText()).isEqualTo("REJECTED");
    }

    @Test void missingCitedBytesAreRefusedWithTheExactReasonAndNothingIsSaved() throws Exception {
        ready(false);
        var c = caseRecord(); var b = c.body(); ((ObjectNode) b.path("screening_refs").get(0)).put("revision", 9); update(c, b);
        var before = caseRecord(); long blobsBefore = blobCount();
        var r = seal();
        assertThat(r.path("status").asText()).isEqualTo("REJECTED"); assertThat(r.path("reason_code").asText()).isEqualTo("EVIDENCE_MISSING");
        assertThat(store.list("export", run)).isEmpty(); assertThat(caseRecord()).isEqualTo(before); assertThat(blobCount()).isEqualTo(blobsBefore);
    }

    @ParameterizedTest @ValueSource(strings = {"CITES_NOTIFICATION", "CITES_SUBSCRIPTION", "PHONE_IN_SCREENING", "PHONE_KEY_IN_MISSION", "TRUTH_KEY_IN_INVESTIGATION"})
    void forbiddenRecordsAndPrivateFieldsNeverEnterAnExport(String variant) throws Exception {
        ready(false);
        String number = "9876543210";
        switch (variant) {
            case "CITES_NOTIFICATION", "CITES_SUBSCRIPTION" -> {
                boolean n = variant.equals("CITES_NOTIFICATION");
                var rec = obj().put("text", "FuelPrint DEMO (simulated): call " + number); rec.put("phone_masked", "XXXXXX3210");
                String key = seed(n ? "notification" : "alert_subscription", rec);
                var f = store.list("finding", run).get(0); var fb = f.body();
                ((ArrayNode) fb.path("cited_refs")).add(obj().put("type", n ? "notification" : "alert_subscription").put("id", key).put("revision", 1));
                update(f, fb);
                var c = caseRecord(); var cb = c.body(); ((ObjectNode) cb.path("finding_ref")).put("revision", 2); update(c, cb);
            }
            case "PHONE_IN_SCREENING" -> {
                var s = screen(); var sb = s.body(); ((ArrayNode) sb.path("reasons")).add("call +91 " + number); update(s, sb);
                var c = caseRecord(); var cb = c.body(); ((ObjectNode) cb.path("screening_refs").get(0)).put("revision", 2); update(c, cb);
                var f = store.list("finding", run).get(0); var fb = f.body(); ((ObjectNode) fb.path("cited_refs").get(0)).put("revision", 2); update(f, fb);
                c = caseRecord(); cb = c.body(); ((ObjectNode) cb.path("finding_ref")).put("revision", 2); update(c, cb);
            }
            case "PHONE_KEY_IN_MISSION", "TRUTH_KEY_IN_INVESTIGATION" -> {
                String type = variant.startsWith("PHONE") ? "mission" : "investigation";
                String id = type.equals("mission") ? screen().body().path("mission_id").asText() : caseRecord().body().path("investigation_id").asText();
                var r = store.get(type, id).orElseThrow(); var rb = r.body(); rb.put(type.equals("mission") ? "phone_number" : "hidden_truth", type.equals("mission") ? number : "S02"); update(r, rb);
            }
            default -> throw new IllegalStateException(variant);
        }
        var before = caseRecord(); long blobsBefore = blobCount();
        var r = seal();
        assertThat(r.path("reason_code").asText()).as(r.toString()).isEqualTo("EVIDENCE_FORBIDDEN");
        assertThat(r.toString()).doesNotContain(number).doesNotContain("3210");
        assertThat(store.list("export", run)).isEmpty(); assertThat(caseRecord()).isEqualTo(before); assertThat(blobCount()).isEqualTo(blobsBefore);
    }

    @Test void aSealNeverContainsPhoneNumbersNotificationsSubscriptionsOrTruth() throws Exception {
        ready(true);
        String number = "9812345678";
        seed("alert_subscription", obj().put("phone_masked", "XXXXXX5678").put("state", "ACTIVE"));
        seed("notification", obj().put("text", "FuelPrint DEMO (simulated): " + number).put("kind", "BOARD_UPDATE"));
        ok(seal());
        var export = theExport();
        StringBuilder all = new StringBuilder(new String(blobs.get(export.body().path("manifest").path("sha256").asText()), StandardCharsets.UTF_8));
        for (JsonNode m : export.body().path("members")) all.append(new String(blobs.get(m.path("sha256").asText()), StandardCharsets.UTF_8));
        assertThat(all.toString()).doesNotContain(number).doesNotContain("alert_subscription").doesNotContain("notification").doesNotContain("phone").doesNotContain("hidden_truth");
        assertThat(export.body().toString()).doesNotContain(number).doesNotContain("alert_subscription");
    }

    @Test void aFailureBetweenTheExportAndTheCaseSaveRollsBackBothAndPublishesNothing() throws Exception {
        ready(false);
        var before = caseRecord(); long exportsBefore = store.listAll("export").size();
        doThrow(new IllegalStateException("injected failure")).when(store).save(argThat(c -> c != null && c.type().equals("case") && "SEALED".equals(c.fields().path("state").asText())), any(), any());
        var response = mvc.perform(post("/api/commands").cookie(exporter).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new Command("fp/1", id(), "seal_export", run, before.revision(), sealPayload())))).andReturn().getResponse();
        bus.awaitIdle();
        Mockito.reset(store);
        assertThat(response.getStatus()).as("a server failure answers 500 with a SERVER_ERROR receipt").isEqualTo(500);
        assertThat(json.readTree(response.getContentAsString()).path("reason_code").asText()).isEqualTo("SERVER_ERROR");
        assertThat(store.listAll("export")).hasSize((int) exportsBefore); assertThat(caseRecord()).isEqualTo(before);
        assertThat(caseRecord().body().path("state").asText()).isEqualTo("FINDING");
        // The same seal succeeds afterwards: nothing partial was left behind.
        ok(seal()); assertThat(store.list("export", run)).hasSize(1);
    }

    @Test void automationMayNotSealWhileAutoSealIsOffButMaySealWhenOn() throws Exception {
        ready(false);
        setSeal(false); mode.active = true;
        try {
            var off = automation.submit("seal_export", sealPayload(), run, null).join();
            assertThat(off.reasonCode()).isEqualTo("NOT_ALLOWED"); assertThat(off.automatic()).isTrue(); assertThat(store.list("export", run)).isEmpty();
            setSeal(true);
            var on = automation.submit("seal_export", sealPayload(), run, null).join();
            assertThat(on.applied()).as(on.toString()).isTrue(); assertThat(on.automatic()).isTrue(); assertThat(on.actor()).isEqualTo("automation");
        } finally { mode.active = false; }
        assertThat(store.list("export", run)).hasSize(1);
    }

    // =====================================================================================================
    // C15 verification
    // =====================================================================================================

    @Test void exporterVerifiesASealedExportAndTheReportKeepsSevenSeparateAxes() throws Exception {
        ready(true); ok(seal());
        var export = theExport(); var snapshot = export.body();
        var command = new Command("fp/1", id(), "verify_export", run, null, obj().put("export_id", export.id()));
        var receipt = send(command, exporter); ok(receipt);
        assertThat(send(command, exporter)).isEqualTo(receipt); assertThat(reports()).hasSize(1);
        var report = reports().get(0).body(); var axes = report.path("axes");
        assertThat(axes.fieldNames()).toIterable().containsExactly("integrity", "key_trust", "lineage", "trusted_time", "custody_documentation", "measurement_qualification", "legal_suitability");
        assertThat(axes.path("integrity").asText()).isEqualTo("PASS"); assertThat(axes.path("key_trust").asText()).isEqualTo("NOT_SIGNED");
        assertThat(axes.path("lineage").asText()).isEqualTo("PASS"); assertThat(axes.path("trusted_time").asText()).isEqualTo("UNVERIFIED");
        assertThat(axes.path("custody_documentation").asText()).as("a recorded custody exception is not erased by a passing integrity check").isEqualTo("LIMITED");
        assertThat(axes.path("measurement_qualification").asText()).isEqualTo("SYNTHETIC_SCOPE_ONLY"); assertThat(axes.path("legal_suitability").asText()).isEqualTo("NOT_ASSESSED");
        assertThat(report.path("export_id").asText()).isEqualTo(export.id()); assertThat(report.path("failed_members")).isEmpty();
        assertThat(report.path("reasons")).isNotEmpty(); assertThat(report.toString()).doesNotContain("\"verified\"").doesNotContain("authentic");
        assertThat(store.revisions("export", export.id())).hasSize(1); assertThat(theExport().body()).isEqualTo(snapshot);
        ok(verify(export.id(), exporter)); assertThat(reports()).hasSize(2);
    }

    @Test void verifyRolesUnknownForeignAndMalformedExports() throws Exception {
        ready(false); ok(seal()); var export = theExport();
        for (Cookie wrong : List.of(reviewer, presenter, lab, inspector)) assertThat(verify(export.id(), wrong).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(verify(id(), exporter).path("status").asText()).isEqualTo("REJECTED");
        assertThat(send("verify_export", obj().put("export_id", "S02"), null, exporter).path("status").asText()).isEqualTo("REJECTED");
        assertThat(send("verify_export", obj(), null, exporter).path("status").asText()).isEqualTo("REJECTED");
        String foreign = seed("export", obj().put("state", "SEALED"), id());
        assertThat(verify(foreign, exporter).path("status").asText()).isEqualTo("REJECTED");
        assertThat(reports()).isEmpty();
        // An export record that lacks its manifest expectation is reported as failed verification, not a crash.
        String broken = seed("export", obj().put("state", "SEALED").put("case_id", caseRecord().id()));
        ok(verify(broken, exporter));
        var r = lastReport().body();
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL"); assertThat(r.path("axes").path("lineage").asText()).isEqualTo("UNVERIFIED");
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("NOT_SIGNED");
    }

    @Test void verificationReadsTheStoredBytesNotTheLiveLatestRecords() throws Exception {
        ready(false); ok(seal()); var export = theExport();
        var s = screen(); var sb = s.body().put("decision", "REJECTED"); update(s, sb);
        var mission = store.get("mission", s.body().path("mission_id").asText()).orElseThrow(); update(mission, mission.body().put("state", "INTERRUPTED"));
        ok(verify(export.id(), exporter));
        var axes = lastReport().body().path("axes");
        assertThat(axes.path("integrity").asText()).isEqualTo("PASS"); assertThat(axes.path("lineage").asText()).isEqualTo("PASS");
    }

    @Test void aMissingOrDamagedBlobOnDiskIsAFailedVerificationNotACrash() throws Exception {
        ready(false); ok(seal()); var export = theExport();
        String path = memberPath(export, "screening_record");
        Path file = blobFile(locator(export, path)); byte[] original = Files.readAllBytes(file);
        try {
            Files.write(file, "damaged".getBytes(StandardCharsets.UTF_8));
            ok(verify(export.id(), exporter));
            var corrupt = lastReport().body();
            assertThat(corrupt.path("axes").path("integrity").asText()).isEqualTo("FAIL");
            assertThat(corrupt.path("failed_members")).extracting(JsonNode::asText).containsExactly(path);
            Files.delete(file);
            ok(verify(export.id(), exporter));
            assertThat(lastReport().body().path("member_checks").toString()).contains("MISSING");
        } finally { Files.write(file, original); }
        ok(verify(export.id(), exporter));
        assertThat(lastReport().body().path("axes").path("integrity").asText()).isEqualTo("PASS");
    }

    // =====================================================================================================
    // C16 tamper copy
    // =====================================================================================================

    @ParameterizedTest @ValueSource(strings = {"screening_record", "finding", "case", "lab_result", "custody_event", "specimen"})
    void aTamperCopyFailsExactlyTheChangedMemberWhileTheOriginalStaysByteIdenticalAndPasses(String type) throws Exception {
        ready(true); ok(seal());
        var export = theExport(); var originalBody = export.body(); var snapshot = blobSnapshot(export);
        ok(verify(export.id(), exporter)); assertThat(lastReport().body().path("axes").path("integrity").asText()).isEqualTo("PASS");
        String target = memberPath(export, type);
        var receipt = send("tamper_copy", obj().put("export_id", export.id()).put("member_path", target), null, exporter);
        ok(receipt);
        assertThat(store.list("export", run)).hasSize(2);
        var copy = store.list("export", run).stream().filter(e -> !e.id().equals(export.id())).findFirst().orElseThrow(); var c = copy.body();
        assertThat(c.path("state").asText()).isEqualTo("TAMPER_DEMO_COPY"); assertThat(c.path("copy_of").path("id").asText()).isEqualTo(export.id());
        assertThat(c.path("manifest_sha256")).isEqualTo(originalBody.path("manifest_sha256")); assertThat(c.path("manifest")).isEqualTo(originalBody.path("manifest"));
        int changedMembers = 0;
        for (int i = 0; i < c.path("members").size(); i++) {
            JsonNode copyMember = c.path("members").get(i), origMember = originalBody.path("members").get(i);
            assertThat(copyMember.path("path")).isEqualTo(origMember.path("path")); assertThat(copyMember.path("length")).isEqualTo(origMember.path("length"));
            if (!copyMember.path("sha256").equals(origMember.path("sha256"))) {
                changedMembers++; assertThat(copyMember.path("path").asText()).isEqualTo(target);
                byte[] a = blobs.get(origMember.path("sha256").asText()), b = blobs.get(copyMember.path("sha256").asText()); int diff = 0;
                assertThat(b.length).isEqualTo(a.length); for (int k = 0; k < a.length; k++) if (a[k] != b[k]) diff++;
                assertThat(diff).as("exactly one byte differs").isEqualTo(1);
            }
        }
        assertThat(changedMembers).isEqualTo(1);
        // The original export and every original blob are byte-identical.
        assertThat(store.revisions("export", export.id())).hasSize(1); assertThat(store.get("export", export.id()).orElseThrow().body()).isEqualTo(originalBody);
        var after = blobSnapshot(export); assertThat(after.keySet()).isEqualTo(snapshot.keySet());
        for (var e : snapshot.entrySet()) assertThat(after.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
        // Verify the copy: only the changed member fails; other axes stay independent.
        ok(verify(copy.id(), exporter));
        var rc = lastReport().body(); var axes = rc.path("axes");
        assertThat(axes.path("integrity").asText()).isEqualTo("FAIL"); assertThat(rc.path("failed_members")).extracting(JsonNode::asText).containsExactly(target);
        assertThat(rc.path("export_id").asText()).isEqualTo(copy.id());
        assertThat(axes.path("custody_documentation").asText()).as("the custody exception survives a failed integrity check").isEqualTo("LIMITED");
        assertThat(axes.path("key_trust").asText()).isEqualTo("NOT_SIGNED"); assertThat(axes.path("legal_suitability").asText()).isEqualTo("NOT_ASSESSED");
        assertThat(axes.path("trusted_time").asText()).isEqualTo("UNVERIFIED"); assertThat(axes.path("measurement_qualification").asText()).isEqualTo("SYNTHETIC_SCOPE_ONLY");
        for (JsonNode check : rc.path("member_checks")) assertThat(check.path("status").asText()).isEqualTo(check.path("path").asText().equals(target) ? "HASH_MISMATCH" : "PASS");
        // And the original still passes afterwards.
        ok(verify(export.id(), exporter)); var ro = lastReport().body();
        assertThat(ro.path("export_id").asText()).isEqualTo(export.id()); assertThat(ro.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(ro.path("failed_members")).isEmpty();
    }

    @Test void tamperCopyIsExporterOnlyAndRefusesBadMembersCopiesAndMissingOriginals() throws Exception {
        ready(false); ok(seal()); var export = theExport(); String target = memberPath(export, "screening_record");
        for (Cookie wrong : List.of(reviewer, presenter, lab, inspector)) assertThat(send("tamper_copy", obj().put("export_id", export.id()).put("member_path", target), null, wrong).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
        mode.active = true;
        try { assertThat(automation.submit("tamper_copy", obj().put("export_id", export.id()).put("member_path", target), run, null).join().reasonCode()).isEqualTo("NOT_ALLOWED"); }
        finally { mode.active = false; }
        for (String bad : List.of("manifest.json", "payload/records/../../x.json", "/etc/passwd", "", "payload/records/case/" + id() + "-r1.json", target.toUpperCase(Locale.ROOT), target + " "))
            assertThat(send("tamper_copy", obj().put("export_id", export.id()).put("member_path", bad), null, exporter).path("status").asText()).as(bad).isEqualTo("REJECTED");
        assertThat(send("tamper_copy", obj().put("export_id", export.id()).putNull("member_path"), null, exporter).path("status").asText()).isEqualTo("REJECTED");
        assertThat(send("tamper_copy", obj().put("export_id", id()).put("member_path", target), null, exporter).path("status").asText()).isEqualTo("REJECTED");
        assertThat(store.list("export", run)).hasSize(1);
        var command = new Command("fp/1", id(), "tamper_copy", run, null, obj().put("export_id", export.id()).put("member_path", target));
        var first = send(command, exporter); ok(first); assertThat(send(command, exporter)).isEqualTo(first); assertThat(store.list("export", run)).hasSize(2);
        var copy = store.list("export", run).stream().filter(e -> !e.id().equals(export.id())).findFirst().orElseThrow();
        var chain = send("tamper_copy", obj().put("export_id", copy.id()).put("member_path", target), null, exporter);
        assertThat(chain.path("status").asText()).isEqualTo("REJECTED"); assertThat(store.list("export", run)).hasSize(2);
        // A missing original blob cannot be copied.
        String findingPath = memberPath(export, "finding");
        Path file = blobFile(locator(export, findingPath)); byte[] original = Files.readAllBytes(file);
        try {
            Files.delete(file);
            var r = send("tamper_copy", obj().put("export_id", export.id()).put("member_path", findingPath), null, exporter);
            assertThat(r.path("status").asText()).isEqualTo("REJECTED"); assertThat(r.path("reason_code").asText()).isEqualTo("EVIDENCE_MISSING");
        } finally { Files.write(file, original); }
        assertThat(store.list("export", run)).hasSize(2);
    }

    // =====================================================================================================
    // Automatic finding -> seal -> verify
    // =====================================================================================================

    @Test void aSavedFindingIsSealedAndVerifiedAutomaticallyOnceAndNeverLoops() throws Exception {
        labbed(true); mode.active = true;
        findingSaved();
        await(() -> !reports().isEmpty());
        quiet();
        var export = theExport();
        assertThat(store.list("export", run)).hasSize(1); assertThat(reports()).hasSize(1);
        assertThat(caseRecord().body().path("state").asText()).isEqualTo("SEALED");
        assertThat(autoCount("seal_export")).isEqualTo(1); assertThat(autoCount("verify_export")).isEqualTo(1);
        for (var entry : commands.automationEntries(run, Integer.MAX_VALUE)) {
            if (entry.commandType().equals("seal_export") || entry.commandType().equals("verify_export")) {
                assertThat(entry.receipt().applied()).isTrue(); assertThat(entry.receipt().automatic()).isTrue(); assertThat(entry.receipt().actor()).isEqualTo("automation");
            }
        }
        assertThat(export.createdBy()).isEqualTo("automation");
        var report = reports().get(0).body();
        assertThat(report.path("export_id").asText()).isEqualTo(export.id()); assertThat(reports().get(0).createdBy()).isEqualTo("automation");
        assertThat(report.path("axes").path("integrity").asText()).isEqualTo("PASS"); assertThat(report.path("axes").path("custody_documentation").asText()).isEqualTo("LIMITED");
        assertThat(report.path("axes").path("key_trust").asText()).isEqualTo("NOT_SIGNED");
        // Replayed events change nothing.
        var f = store.list("finding", run).get(0);
        publisher.publishEvent(new RecordSaved("finding", f.id(), f.revision(), run, "case_reviewer@cases", false));
        publisher.publishEvent(new RecordSaved("export", export.id(), 1, run, "automation", true));
        publisher.publishEvent(new RecordSaved("verification_report", reports().get(0).id(), 1, run, "automation", true));
        quiet();
        assertThat(store.list("export", run)).hasSize(1); assertThat(reports()).hasSize(1); assertThat(autoCount("seal_export")).isEqualTo(1); assertThat(autoCount("verify_export")).isEqualTo(1);
    }

    @Test void withAutoSealOffNothingIsSealedAutomaticallyButTheExporterStillCan() throws Exception {
        labbed(false); setSeal(false); mode.active = true;
        findingSaved(); quiet();
        assertThat(store.list("export", run)).isEmpty(); assertThat(caseRecord().body().path("state").asText()).isEqualTo("FINDING"); assertThat(autoCount("seal_export")).isZero();
        ok(seal()); quiet();
        assertThat(store.list("export", run)).hasSize(1); assertThat(reports()).as("auto verify is part of the same switch").isEmpty(); assertThat(autoCount("verify_export")).isZero();
        ok(verify(theExport().id(), exporter)); assertThat(reports()).hasSize(1); assertThat(reports().get(0).createdBy()).isEqualTo("exporter@export");
    }

    @Test void automationMayNotVerifyWhileAutoSealIsOffButTheExporterMay() throws Exception {
        ready(false); ok(seal()); var export = theExport();
        setSeal(false); mode.active = true;
        try {
            var off = automation.submit("verify_export", obj().put("export_id", export.id()), run, null).join();
            assertThat(off.reasonCode()).isEqualTo("NOT_ALLOWED"); assertThat(off.automatic()).isTrue(); assertThat(reports()).isEmpty();
        } finally { mode.active = false; }
        ok(verify(export.id(), exporter)); assertThat(reports()).hasSize(1);
    }

    @Test void withReplayOrAutomationInactiveNothingIsQueued() throws Exception {
        labbed(false); mode.active = false;
        findingSaved(); quiet();
        assertThat(store.list("export", run)).isEmpty(); assertThat(autoCount("seal_export")).isZero();
    }

    @Test void aTamperCopyIsNeverSealedOrVerifiedAutomaticallyAndAutomationCannotVerifyIt() throws Exception {
        labbed(false); mode.active = true; findingSaved(); await(() -> !reports().isEmpty()); quiet();
        var export = theExport(); long sealsBefore = autoCount("seal_export"), verifiesBefore = autoCount("verify_export");
        ok(send("tamper_copy", obj().put("export_id", export.id()).put("member_path", memberPath(export, "screening_record")), null, exporter));
        var copy = store.list("export", run).stream().filter(e -> !e.id().equals(export.id())).findFirst().orElseThrow();
        publisher.publishEvent(new RecordSaved("export", copy.id(), 1, run, "exporter@export", false));
        quiet();
        assertThat(reports()).hasSize(1); assertThat(autoCount("seal_export")).isEqualTo(sealsBefore); assertThat(autoCount("verify_export")).isEqualTo(verifiesBefore);
        assertThat(store.list("export", run)).hasSize(2); assertThat(caseRecord().body().path("state").asText()).isEqualTo("SEALED");
        var refused = automation.submit("verify_export", obj().put("export_id", copy.id()), run, null).join();
        assertThat(refused.reasonCode()).isEqualTo("NOT_ALLOWED"); assertThat(reports()).hasSize(1);
        ok(verify(copy.id(), exporter)); assertThat(lastReport().body().path("axes").path("integrity").asText()).isEqualTo("FAIL");
    }

    @Test void aRefusedAutomaticSealIsRecordedOnceAndNeverRetriedInALoop() throws Exception {
        labbed(false);
        var mission = store.get("mission", screen().body().path("mission_id").asText()).orElseThrow(); update(mission, mission.body().put("phone_number", "9876543210"));
        mode.active = true; findingSaved(); quiet();
        assertThat(store.list("export", run)).isEmpty(); assertThat(caseRecord().body().path("state").asText()).isEqualTo("FINDING");
        var seals = commands.automationEntries(run, Integer.MAX_VALUE).stream().filter(e -> e.commandType().equals("seal_export")).toList();
        assertThat(seals).hasSize(1); assertThat(seals.get(0).receipt().applied()).isFalse(); assertThat(seals.get(0).receipt().reasonCode()).isEqualTo("EVIDENCE_FORBIDDEN");
        assertThat(seals.get(0).receipt().reasonText()).doesNotContain("9876543210");
        var f = store.list("finding", run).get(0);
        publisher.publishEvent(new RecordSaved("finding", f.id(), f.revision(), run, "case_reviewer@cases", false)); quiet();
        assertThat(autoCount("seal_export")).as("a refused automatic step is not retried").isEqualTo(1);
    }

    @Test void anOlderFindingEventForACaseThatMovedOnDoesNotSealTheNewerState() throws Exception {
        labbed(false); setSeal(false); findingSaved();
        var f1 = store.list("finding", run).get(0);
        ok(send("revise_finding", finding("INCONCLUSIVE"), caseRecord().revision(), reviewer));
        setSeal(true); mode.active = true;
        publisher.publishEvent(new RecordSaved("finding", f1.id(), f1.revision(), run, "case_reviewer@cases", false)); quiet();
        assertThat(store.list("export", run)).as("revision 1 is no longer the case's pinned finding").isEmpty(); assertThat(autoCount("seal_export")).isZero();
    }

    static class TestMode implements ServerMode { volatile boolean active; public boolean isReplay() { return false; } public boolean automationActive() { return active; } }
    @TestConfiguration static class Config {
        @Bean @Primary TestMode exportMode() { return new TestMode(); }
        @Bean CommandHandler exportFixture() {
            return new CommandHandler() {
                public String commandType() { return "test_export_fixture"; }
                public Set<Role> allowedRoles() { return Set.of(Role.PRESENTER); }
                public HandlerOutcome handle(Command c, Session s, StoreView v) {
                    var p = c.payload();
                    return HandlerResult.builder().save(new RecordChange(p.path("type").asText(), p.path("id").asText(), c.runId(), p.path("based").asLong(0), (ObjectNode) p.path("fields")), "TEST_PUBLIC_RECORD_FIXTURE", 0.0).build();
                }
            };
        }
    }
}
