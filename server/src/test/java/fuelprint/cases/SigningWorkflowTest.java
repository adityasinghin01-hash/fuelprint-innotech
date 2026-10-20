package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

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

/**
 * B2 through the real command bus: v2 signed sealing under external configuration, the refusal (never a fallback)
 * when signing cannot succeed, automatic sealing with signing, separate trust on verification, tamper copies.
 */
@SpringBootTest(properties = {"fp.loop.auto=false", "fp.field.safety-loop=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("real") @AutoConfigureMockMvc @Import(ExportWorkflowTest.Config.class)
class SigningWorkflowTest {
    static final Path DATA = CoreSpringTest.TempFolders.create("fp-cases-signing-");
    static final Path KEYS = CoreSpringTest.TempFolders.create("fp-cases-signing-keys-");
    static final Path CONFIG = KEYS.resolve("evidence.json");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r) {
        r.add("fp.data-dir", DATA::toString);
        r.add("fp.cases.evidence-config", CONFIG::toString);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired RecordStore store;
    @Autowired CommandBus bus; @Autowired CookieSessionService sessions; @Autowired ExportWorkflowTest.TestMode mode; @Autowired CommandLog commands;
    @org.springframework.test.context.bean.override.convention.TestBean(name = "clockControl", methodName = "testClock") ClockControl clock;
    String run; Cookie presenter, reviewer, inspector, lab, exporter;
    static TestKeys key;
    static ClockControl testClock() { return ExportWorkflowTest.testClock(); }
    static String id() { return UUID.randomUUID().toString(); }
    ObjectNode obj() { return json.createObjectNode(); }
    Cookie cookie(String station, String... roles) { return new Cookie(CookieSessionService.COOKIE, sessions.createStation(station, Set.of(roles)).sessionId()); }

    @BeforeAll static void keys() { key = TestKeys.write(KEYS, "signer"); }

    void configure(String json) throws Exception { Files.writeString(CONFIG, json); }
    String signedConfig(Path trust) {
        return "{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + key.privatePem + "\",\"public_key_path\":\""
                + key.publicPem + "\"" + (trust == null ? "" : ",\"trust_anchors_path\":\"" + trust + "\"") + "}";
    }

    @BeforeEach void fresh() throws Exception {
        bus.awaitIdle(); mode.active = false;
        configure(signedConfig(TestKeys.trustFile(KEYS, key.fingerprint())));
        presenter = cookie("presenter", "presenter"); reviewer = cookie("cases", "case_reviewer");
        inspector = cookie("collector", "inspector_demo"); lab = cookie("lab", "lab_demo"); exporter = cookie("export", "exporter");
        ok(send("reset_run", obj().put("preset_id", "SC1-NIGHT-PLUME").put("mode", "TEACHING"), null, presenter));
        run = store.listAll("run").stream().filter(x -> x.body().path("state").asText().equals("RUNNING")).reduce((a, b) -> b).orElseThrow().id();
        setSeal(false);
    }
    @AfterAll static void cleanup() throws Exception { Files.deleteIfExists(CONFIG); }

    JsonNode send(String type, ObjectNode p, Long rev, Cookie who) throws Exception {
        var c = new Command("fp/1", id(), type, run, rev, p);
        var response = mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c)))
                .andExpect(status().isOk()).andReturn().getResponse();
        bus.awaitIdle(); return json.readTree(response.getContentAsString());
    }
    void ok(JsonNode r) { assertThat(r.path("status").asText()).as(r.toString()).isEqualTo("APPLIED"); }
    void setSeal(boolean on) throws Exception { ok(send("change_automation", obj().put("setting", "auto_seal").put("value", on), null, presenter)); }
    String seed(String type, ObjectNode b) throws Exception {
        String k = id(); var p = obj().put("type", type).put("id", k); p.set("fields", b);
        ok(send("test_export_fixture", p, null, presenter)); return k;
    }
    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < end) { bus.awaitIdle(); if (condition.getAsBoolean()) return; Thread.sleep(40); }
        throw new AssertionError("timed out waiting for the automatic steps");
    }
    StoredRecord caseRecord() { return store.list("case", run).get(0); }
    StoredRecord specimen() { return store.list("specimen", run).get(0); }
    String screening() throws Exception {
        String inv = seed("investigation", obj().put("state", "EVIDENCE_BACK"));
        String survey = seed("survey", obj().put("investigation_id", inv));
        String mission = seed("mission", obj().put("survey_id", survey).put("state", "COMPLETE"));
        String attempt = seed("attempt", obj().put("mission_id", mission).put("state", "COMPLETE"));
        var b = obj().put("mission_id", mission).put("attempt_id", attempt).put("channel", "SO2_ppb").put("named_use", "SYNTHETIC_ONLINE_SCREENING").put("decision", "VALID");
        b.putArray("reasons").add("WITHIN_TEACHING_SCOPE"); b.putObject("summary").putNull("mean_enhancement_ppb");
        return seed("screening_record", b);
    }
    /** Case in FINDING with a specimen and a passing fuel lab. */
    void ready() throws Exception {
        var intake = obj().put("purpose", "Teaching investigation only"); intake.putArray("screening_record_ids").add(screening());
        ok(send("create_case", intake, null, reviewer));
        ok(send("create_specimen", obj().put("case_id", caseRecord().id()).put("site_id", "S02").put("material", "SYNTHETIC_COAL_COKE_LIKE")
                .put("method_id", "TEACH-FUEL-S-1").put("container", "jar-1").put("seal_id", "seal-1").put("mass_mg", 100), caseRecord().revision(), inspector));
        var request = obj().put("specimen_id", specimen().id()).put("method_id", "TEACH-FUEL-S-1");
        request.putObject("qc_inputs").put("gross_ug", 2010).put("blank_ug", 10).put("dup_a_pct", 2.0).put("dup_b_pct", 2.2).put("spike_added_ug", 100).put("spike_recovered_ug", 90);
        ok(send("request_lab", request, specimen().revision(), lab));
        var finding = obj().put("case_id", caseRecord().id()).put("outcome", "INCONCLUSIVE");
        finding.putArray("cited_refs").add(CasesRecords.ref(store.list("lab_result", run).get(0)));
        finding.putArray("alternatives"); finding.putArray("limitations").add("Synthetic observation only");
        ok(send("revise_finding", finding, caseRecord().revision(), reviewer));
    }
    JsonNode seal() throws Exception { return send("seal_export", obj().put("case_id", caseRecord().id()).put("case_revision", caseRecord().revision()), caseRecord().revision(), exporter); }
    List<StoredRecord> exports() { return store.list("export", run); }
    StoredRecord report() { var l = store.list("verification_report", run); return l.get(l.size() - 1); }
    JsonNode verify(String exportId) throws Exception { return send("verify_export", obj().put("export_id", exportId), null, exporter); }

    @Test void requiredSigningSealsASignedV2PackThatVerifiesTrustedWithAllSevenAxes() throws Exception {
        ready();
        ok(seal());
        var e = exports().get(0).body();
        assertThat(e.path("schema").asText()).isEqualTo("fp.evidence/2");
        assertThat(e.path("signing_policy").asText()).isEqualTo("SIGN_REQUIRED");
        assertThat(e.path("signature").path("key_fingerprint_sha256").asText()).isEqualTo(key.fingerprint());
        assertThat(e.path("signature").path("path").asText()).isEqualTo("signature.json");
        assertThat(e.path("signature_reason").isNull()).isTrue();
        assertThat(caseRecord().body().path("state").asText()).isEqualTo("SEALED");
        ok(verify(exports().get(0).id()));
        var r = report().body();
        assertThat(r.path("axes").fieldNames()).toIterable().containsExactly("integrity", "key_trust", "lineage", "trusted_time",
                "custody_documentation", "measurement_qualification", "legal_suitability");
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
        assertThat(r.path("axes").path("trusted_time").asText()).isEqualTo("UNVERIFIED");
        assertThat(r.path("signature_check").path("validity").asText()).isEqualTo("VALID");
        assertThat(r.path("scope_note").asText()).isEqualTo(ExportHandlers.SIGNED_SCOPE_NOTE);
    }

    @Test void withoutTheSignerInTheTrustAnchorsTheSameValidSignatureIsUntrusted() throws Exception {
        configure(signedConfig(TestKeys.trustFile(KEYS, "e".repeat(64))));
        ready(); ok(seal()); ok(verify(exports().get(0).id()));
        assertThat(report().body().path("axes").path("key_trust").asText()).isEqualTo("UNTRUSTED_KEY");
        configure(signedConfig(null));
        ok(verify(exports().get(0).id()));
        assertThat(report().body().path("axes").path("key_trust").asText()).isEqualTo("UNTRUSTED_KEY");
        assertThat(report().body().path("signature_check").path("trust_source").asText()).isEqualTo("NONE");
    }

    @Test void whenSigningCannotSucceedSealingIsRefusedAndNothingFallsBackToUnsigned() throws Exception {
        ready();
        long rev = caseRecord().revision();
        configure("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + KEYS.resolve("absent.pem")
                + "\",\"public_key_path\":\"" + key.publicPem + "\"}");
        var r = seal();
        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        assertThat(r.path("reason_code").asText()).isEqualTo("SIGNING_KEY_MISSING");
        assertThat(r.path("reason_text").asText()).doesNotContain(key.privateBase64()).doesNotContain(KEYS.toString());
        configure("{\"format\":2,\"signing_policy\":\"SIGN_IF_CONFIGURED\"}");
        assertThat(seal().path("reason_code").asText()).isEqualTo("EVIDENCE_CONFIG_INVALID");
        configure("{\"format\":1,\"signing_policy\":\"SIGN_REQUIRED\"}");
        assertThat(seal().path("reason_code").asText()).isEqualTo("EVIDENCE_CONFIG_INVALID");
        assertThat(exports()).isEmpty();
        assertThat(caseRecord().revision()).isEqualTo(rev);
        assertThat(caseRecord().body().path("state").asText()).isEqualTo("FINDING");
        // Once a valid signer is configured again the same case seals signed.
        configure(signedConfig(null));
        ok(seal());
        assertThat(exports().get(0).body().path("schema").asText()).isEqualTo("fp.evidence/2");
    }

    @Test void automaticSealingSignsTooAndVerificationIsAutomatic() throws Exception {
        setSeal(true);
        mode.active = true;
        try {
            ready();
            await(() -> !store.list("verification_report", run).isEmpty());
        } finally { mode.active = false; }
        var e = exports().get(0);
        assertThat(e.body().path("sealed_by").asText()).isEqualTo("automation");
        assertThat(e.body().path("signature").path("key_fingerprint_sha256").asText()).isEqualTo(key.fingerprint());
        assertThat(report().body().path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
        setSeal(false);
    }

    @Test void unsignedV2AndLegacyDefaultRemainExplicit() throws Exception {
        configure("{\"format\":2,\"signing_policy\":\"UNSIGNED\"}");
        ready(); ok(seal());
        var e = exports().get(0).body();
        assertThat(e.path("schema").asText()).isEqualTo("fp.evidence/2");
        assertThat(e.path("signature").isNull()).isTrue();
        assertThat(e.path("signature_reason").asText()).isEqualTo("UNSIGNED_BY_POLICY");
        ok(verify(exports().get(0).id()));
        assertThat(report().body().path("axes").path("key_trust").asText()).isEqualTo("NOT_SIGNED");
    }

    @Test void tamperCopyOfASignedPackFailsIntegrityWhileTheOriginalStillVerifiesTrusted() throws Exception {
        ready(); ok(seal());
        var original = exports().get(0);
        String member = original.body().path("members").get(0).path("path").asText();
        ok(send("tamper_copy", obj().put("export_id", original.id()).put("member_path", member), null, exporter));
        var copy = exports().stream().filter(x -> "TAMPER_DEMO_COPY".equals(x.body().path("state").asText())).findFirst().orElseThrow();
        assertThat(copy.body().path("signature")).isEqualTo(original.body().path("signature"));
        ok(verify(copy.id()));
        assertThat(report().body().path("axes").path("integrity").asText()).isEqualTo("FAIL");
        ok(verify(original.id()));
        assertThat(report().body().path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(report().body().path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
    }

    @Test void wrongRoleStaleRevisionAndRepeatedCommandAreStillGuarded() throws Exception {
        ready();
        var p = obj().put("case_id", caseRecord().id()).put("case_revision", caseRecord().revision());
        assertThat(send("seal_export", p, caseRecord().revision(), reviewer).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
        assertThat(send("seal_export", p, caseRecord().revision() - 1, exporter).path("reason_code").asText()).isEqualTo("STALE_REVISION");
        var c = new Command("fp/1", id(), "seal_export", run, caseRecord().revision(), p);
        var first = mvc.perform(post("/api/commands").cookie(exporter).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c))).andReturn().getResponse().getContentAsString();
        bus.awaitIdle();
        var again = mvc.perform(post("/api/commands").cookie(exporter).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c))).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
        assertThat(exports()).hasSize(1);
    }

    @Test void noPrivateKeyMaterialEntersAnyRecordOrBlob() throws Exception {
        ready(); ok(seal()); ok(verify(exports().get(0).id()));
        String secret = key.privateBase64();
        for (String type : List.of("case", "export", "verification_report", "finding", "lab_result", "specimen", "custody_event"))
            for (var r : store.list(type, run)) assertThat(r.body().toString()).doesNotContain(secret).doesNotContain("PRIVATE KEY").doesNotContain(key.privatePem.toString());
        try (var files = Files.list(DATA.resolve("blobs"))) {
            for (Path f : files.toList()) if (Files.isRegularFile(f)) assertThat(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret).doesNotContain("PRIVATE KEY");
        }
    }

    @Test void r1AnOldSignedPackStaysTrustedAfterTheSigningKeyBecomesUnusableWhileSealingIsRefused() throws Exception {
        Path trust = TestKeys.trustFile(KEYS, key.fingerprint());
        configure(signedConfig(trust));
        ready(); ok(seal());
        var original = exports().get(0);
        // The signing key becomes unusable; the trust anchors stay configured.
        configure("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + KEYS.resolve("gone.pem")
                + "\",\"public_key_path\":\"" + key.publicPem + "\",\"trust_anchors_path\":\"" + trust + "\"}");
        ok(verify(original.id()));
        assertThat(report().body().path("signature_check").path("validity").asText()).isEqualTo("VALID");
        assertThat(report().body().path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
    }

    @Test void r2NulPathsGiveFixedRejectionsAndSafeVerification() throws Exception {
        ready();
        long rev = caseRecord().revision();
        configure("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"SECRET-DIR\\u0000k\",\"public_key_path\":\""
                + key.publicPem + "\",\"trust_anchors_path\":\"SECRET-DIR\\u0000t\"}");
        var r = seal();
        assertThat(r.path("status").asText()).isEqualTo("REJECTED");
        // The trust path is checked first, so its fixed configuration code is reported.
        assertThat(r.path("reason_code").asText()).isEqualTo("EVIDENCE_CONFIG_INVALID");
        assertThat(r.path("reason_text").asText()).doesNotContain("SECRET-DIR").doesNotContain(KEYS.toString());
        assertThat(exports()).isEmpty();
        assertThat(caseRecord().revision()).isEqualTo(rev);
        // Seal with a working config, then verify while only the trust path is invalid: valid signature, untrusted, fixed reason.
        configure("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"SECRET-DIR\\u0000k\",\"public_key_path\":\"" + key.publicPem + "\"}");
        var keyOnly = seal();
        assertThat(keyOnly.path("reason_code").asText()).isEqualTo("SIGNING_KEY_INVALID");
        assertThat(keyOnly.path("reason_text").asText()).doesNotContain("SECRET-DIR");
        assertThat(exports()).isEmpty();
        configure(signedConfig(null)); ok(seal());
        configure("{\"format\":2,\"signing_policy\":\"UNSIGNED\",\"trust_anchors_path\":\"SECRET-DIR\\u0000t\"}");
        ok(verify(exports().get(0).id()));
        var report = report().body();
        assertThat(report.path("signature_check").path("validity").asText()).isEqualTo("VALID");
        assertThat(report.path("axes").path("key_trust").asText()).isEqualTo("UNTRUSTED_KEY");
        assertThat(report.toString()).contains("TRUST_ANCHORS_INVALID").doesNotContain("SECRET-DIR");
    }

    // ---------------- B3: download controller ----------------

    org.springframework.mock.web.MockHttpServletResponse download(String exportId, String runId, Cookie who) throws Exception {
        var req = get("/api/exports/" + exportId + "/pack.zip" + (runId == null ? "" : "?run_id=" + runId));
        if (who != null) req = req.cookie(who);
        return mvc.perform(req).andReturn().getResponse();
    }
    Path saved(byte[] body) throws Exception { Path p = Files.createTempFile(KEYS, "dl-", ".zip"); Files.write(p, body); return p; }
    long writes() { return store.list("export", run).size() + store.list("verification_report", run).size() + store.list("case", run).size(); }

    @Test void exporterDownloadsAPreparedPackThatVerifiesOfflineAndOthersAreRefusedFirst() throws Exception {
        Path trust = TestKeys.trustFile(KEYS, key.fingerprint());
        configure(signedConfig(trust));
        ready(); ok(seal());
        var export = exports().get(0);
        long before = writes();
        var r = download(export.id(), run, exporter);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentType()).isEqualTo("application/zip");
        assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"fp-evidence-" + export.id().substring(0, 8) + "-ORIGINAL.zip\"");
        var offline = EvidenceOffline.verify(saved(r.getContentAsByteArray()), EvidenceTrust.of(List.of(key.fingerprint()), "CLI_ARGUMENT"), false);
        assertThat(offline.exitCode()).as(offline.json().toString()).isZero();
        // Multi-role exporter station is allowed.
        assertThat(download(export.id(), run, cookie("multi", "exporter", "case_reviewer")).getStatus()).isEqualTo(200);
        // Not allowed: no session, guest, visitor, other roles. Checked before any lookup (unknown id still 403).
        Cookie guest = new Cookie(CookieSessionService.COOKIE, sessions.createGuest().sessionId());
        Cookie visitor = new Cookie(CookieSessionService.COOKIE, sessions.createVisitor("visitor-test").sessionId());
        Cookie stale = new Cookie(CookieSessionService.COOKIE, UUID.randomUUID().toString());
        for (Cookie who : java.util.Arrays.asList(null, guest, visitor, reviewer, inspector, lab, presenter, stale)) {
            for (String target : List.of(export.id(), id(), "not-a-uuid")) {
                var denied = download(target, run, who);
                assertThat(denied.getStatus()).isEqualTo(403);
                assertThat(json.readTree(denied.getContentAsString()).path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
            }
        }
        assertThat(download("not-a-uuid", run, exporter).getStatus()).isEqualTo(400);
        assertThat(download(export.id(), null, exporter).getStatus()).isEqualTo(400);
        assertThat(download(export.id(), "x", exporter).getStatus()).isEqualTo(400);
        assertThat(download(id(), run, exporter).getStatus()).isEqualTo(404);
        assertThat(download(export.id(), id(), exporter).getStatus()).isEqualTo(404);
        String draft = seed("export", obj().put("state", "DRAFT"));
        var wrong = download(draft, run, exporter);
        assertThat(wrong.getStatus()).isEqualTo(400);
        assertThat(json.readTree(wrong.getContentAsString()).path("reason_code").asText()).isEqualTo("NOT_A_PACKABLE_EXPORT");
        assertThat(writes()).isEqualTo(before + 1); // only the seeded DRAFT fixture
    }

    @Test void tamperCopyDownloadsLabelledAndFailsOfflineWhileTheOriginalPasses() throws Exception {
        ready(); ok(seal());
        var original = exports().get(0);
        ok(send("tamper_copy", obj().put("export_id", original.id()).put("member_path", original.body().path("members").get(0).path("path").asText()), null, exporter));
        var copy = exports().stream().filter(x -> "TAMPER_DEMO_COPY".equals(x.body().path("state").asText())).findFirst().orElseThrow();
        var r = download(copy.id(), run, exporter);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Content-Disposition")).endsWith("-TAMPER_DEMO_COPY.zip\"");
        var trust = EvidenceTrust.of(List.of(key.fingerprint()), "CLI_ARGUMENT");
        assertThat(EvidenceOffline.verify(saved(r.getContentAsByteArray()), trust, false).exitCode()).isEqualTo(5);
        assertThat(EvidenceOffline.verify(saved(download(original.id(), run, exporter).getContentAsByteArray()), trust, false).exitCode()).isZero();
    }

    @Test void aDamagedLaterBlobGivesAJsonRefusalNeverAPartialZip() throws Exception {
        ready(); ok(seal());
        var export = exports().get(0);
        var members = export.body().path("members");
        String last = members.get(members.size() - 1).path("sha256").asText();
        Path blob = DATA.resolve("blobs").resolve(last);
        byte[] original = Files.readAllBytes(blob);
        try {
            Files.write(blob, "damaged".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var r = download(export.id(), run, exporter);
            assertThat(r.getStatus()).isEqualTo(500);
            assertThat(r.getContentType()).startsWith("application/json");
            assertThat(r.getHeader("Content-Disposition")).isNull();
            var body = json.readTree(r.getContentAsString());
            assertThat(body.path("reason_code").asText()).isEqualTo("EVIDENCE_STORAGE");
            assertThat(body.toString()).doesNotContain(DATA.toString()).doesNotContain(last);
            Files.delete(blob);
            var missing = download(export.id(), run, exporter);
            assertThat(missing.getStatus()).isEqualTo(409);
            assertThat(json.readTree(missing.getContentAsString()).path("reason_code").asText()).isEqualTo("EVIDENCE_MISSING");
        } finally {
            Files.write(blob, original);
        }
    }
}
