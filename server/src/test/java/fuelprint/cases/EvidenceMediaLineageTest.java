package fuelprint.cases;

import static fuelprint.cases.EvidenceVaultTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.BlobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B4 export lineage for aerosol media: sealing collects the media graph and the store-free verifier checks its semantics. */
class EvidenceMediaLineageTest {
    static final String MEDIA = "f0000000-0000-4000-8000-000000000001", ME1 = "f0000000-0000-4000-8000-000000000002",
            ME2 = "f0000000-0000-4000-8000-000000000003", MLAB = "f0000000-0000-4000-8000-000000000004";
    @TempDir Path dir;
    final EvidenceVaultTest graphs = new EvidenceVaultTest();

    @BeforeEach void setUp() { graphs.dir = dir; graphs.blobStore(); }
    BlobStore blobs() { return graphs.blobs; }

    ObjectNode body(String json) { return (ObjectNode) parse(q(json)); }
    /** Base graph plus one filter media, two media custody steps and a usable media lab result cited by the finding. */
    EvidenceVaultTest.Mem withMedia() {
        var m = graphs.base();
        m.put("case", CASE, 2, CASE2);
        String common = "'run_id':'" + RUN + "','created_utc':'" + T + "','created_by':'x@y'";
        m.put("media", MEDIA, 1, body("{'id':'" + MEDIA + "','revision':1," + common + ",'case_id':'" + CASE + "','material':'SYNTHETIC_UNCLASSIFIED_AEROSOL',"
                + "'method_id':'TEACH-AEROSOL-S-1','current_holder':'inspector_demo@c','current_media_custody_event_id':'" + ME1 + "','seal_state':'INTACT','custody_exceptions':[]}"));
        m.put("media", MEDIA, 2, body("{'id':'" + MEDIA + "','revision':2," + common + ",'case_id':'" + CASE + "','material':'SYNTHETIC_UNCLASSIFIED_AEROSOL',"
                + "'method_id':'TEACH-AEROSOL-S-1','current_holder':'lab_demo@lab','current_media_custody_event_id':'" + ME2 + "','seal_state':'INTACT','custody_exceptions':[]}"));
        m.put("media_custody_event", ME1, 1, body("{'id':'" + ME1 + "','revision':1," + common + ",'media_id':'" + MEDIA + "','previous_event_id':null,"
                + "'from_holder':'inspector_demo@c','to_holder':'inspector_demo@c','seal_state':'INTACT','exceptions':[]}"));
        m.put("media_custody_event", ME2, 1, body("{'id':'" + ME2 + "','revision':1," + common + ",'media_id':'" + MEDIA + "','previous_event_id':'" + ME1 + "',"
                + "'from_holder':'inspector_demo@c','to_holder':'lab_demo@lab','seal_state':'INTACT','exceptions':[]}"));
        ObjectNode lab = body("{'id':'" + MLAB + "','revision':1," + common + ",'media_id':'" + MEDIA + "','method_id':'TEACH-AEROSOL-S-1','qc_status':'PASS','result_use':'USABLE'}");
        lab.set("media_ref", ref("media", MEDIA, 2)); lab.set("case_ref", ref("case", CASE, 2)); lab.set("custody_ref", ref("media_custody_event", ME2, 1));
        m.put("media_lab_result", MLAB, 1, lab);
        m.edit("case", CASE, 3, c -> { c.putArray("media_ids").add(MEDIA); c.putArray("media_lab_result_ids").add(MLAB); });
        m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).add(ref("media_lab_result", MLAB, 1)));
        return m;
    }
    EvidenceVault.Sealed seal(EvidenceVaultTest.Mem m) { return new EvidenceVault(blobs()).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT); }
    ObjectNode verify(EvidenceVault.Sealed s) { return new EvidenceVerifier(blobs()).verify(export(exportBody(s.manifestBytes(), s.manifestSha256(), s.members(), s.custody(), "SEALED"))); }
    Map<String, String> files(EvidenceVault.Sealed s) {
        Map<String, String> f = new LinkedHashMap<>();
        for (var m : s.members()) f.put(m.path(), new String(blobs().get(m.sha256()), StandardCharsets.UTF_8));
        return f;
    }
    String lineage(Map<String, String> f) { return graphs.verify(graphs.hand(f, m -> { }, "PASS").export()).path("axes").path("lineage").asText(); }
    String reasons(Map<String, String> f) { return graphs.verify(graphs.hand(f, m -> { }, "PASS").export()).path("reasons").toString(); }

    @Test void sealingCollectsTheWholeMediaGraphAndItVerifies() {
        var s = seal(withMedia());
        Set<String> paths = new TreeSet<>();
        s.members().forEach(m -> paths.add(m.path()));
        assertThat(paths).contains(path("media", MEDIA, 2), path("media_custody_event", ME1, 1), path("media_custody_event", ME2, 1),
                path("media_lab_result", MLAB, 1), path("case", CASE, 2));
        assertThat(s.custody().path("status").asText()).isEqualTo("PASS");
        var r = verify(s);
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).as(r.path("reasons").toString()).isEqualTo("PASS");
        assertThat(r.path("axes").path("custody_documentation").asText()).isEqualTo("PASS");
        assertThat(lineage(files(s))).isEqualTo("PASS");
    }

    @Test void sealingRefusesIncoherentMediaGraphs() {
        var otherCase = withMedia(); otherCase.edit("media", MEDIA, 2, b -> b.put("case_id", OTHER));
        assertThatThrownBy(() -> seal(otherCase)).isInstanceOfSatisfying(EvidenceVault.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_INCOHERENT"));
        var foreignEvent = withMedia(); foreignEvent.edit("media_custody_event", ME2, 1, b -> b.put("media_id", OTHER));
        assertThatThrownBy(() -> seal(foreignEvent)).isInstanceOf(EvidenceVault.Refusal.class);
        var wrongType = withMedia(); wrongType.edit("media_lab_result", MLAB, 1, b -> b.set("custody_ref", ref("custody_event", ME2, 1)));
        assertThatThrownBy(() -> seal(wrongType)).isInstanceOf(EvidenceVault.Refusal.class);
        var uncited = withMedia(); uncited.edit("case", CASE, 3, c -> c.putArray("media_lab_result_ids"));
        assertThatThrownBy(() -> seal(uncited)).isInstanceOfSatisfying(EvidenceVault.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_INCOHERENT"));
        var extraEvent = withMedia();
        extraEvent.put("media_custody_event", OTHER, 1, body("{'id':'" + OTHER + "','revision':1,'run_id':'" + RUN + "','created_utc':'" + T
                + "','created_by':'x','media_id':'" + MEDIA + "','previous_event_id':null,'to_holder':'z','seal_state':'INTACT','exceptions':[]}"));
        assertThatThrownBy(() -> seal(extraEvent)).isInstanceOfSatisfying(EvidenceVault.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_INCOHERENT"));
    }

    @Test void mediaCustodyExceptionsMakeCustodyLimited() {
        var m = withMedia();
        m.edit("media", MEDIA, 2, b -> { b.put("seal_state", "BROKEN"); ((ArrayNode) b.path("custody_exceptions")).add("BROKEN_SEAL"); });
        m.edit("media_custody_event", ME2, 1, b -> { b.put("seal_state", "BROKEN"); ((ArrayNode) b.path("exceptions")).add("BROKEN_SEAL"); });
        var s = seal(m);
        assertThat(s.custody().path("status").asText()).isEqualTo("LIMITED");
        assertThat(verify(s).path("axes").path("custody_documentation").asText()).isEqualTo("LIMITED");
    }

    /** Self-consistent hand-built packs (hashes match) whose media semantics are wrong: lineage FAILs offline-style. */
    @Test void verifierChecksMediaSemanticsNotJustMemberExistence() {
        var good = files(seal(withMedia()));
        Map<String, java.util.function.UnaryOperator<String>> breakages = new LinkedHashMap<>();
        breakages.put(path("media", MEDIA, 2), s -> s.replace("\"case_id\":\"" + CASE + "\"", "\"case_id\":\"" + OTHER + "\""));             // cross-case media
        breakages.put(path("media_custody_event", ME2, 1), s -> s.replace("\"run_id\":\"" + RUN + "\"", "\"run_id\":\"" + OTHER + "\""));      // cross-run event
        breakages.put(path("media_lab_result", MLAB, 1) + "#type", s -> s.replace("\"type\":\"media_custody_event\"", "\"type\":\"custody_event\"")); // wrong-type ref
        breakages.put(path("media_custody_event", ME2, 1) + "#owner", s -> s.replace("\"media_id\":\"" + MEDIA + "\"", "\"media_id\":\"" + OTHER + "\"")); // mismatched custody
        breakages.put(path("media_lab_result", MLAB, 1) + "#method", s -> s.replace("\"method_id\":\"TEACH-AEROSOL-S-1\"", "\"method_id\":\"TEACH-FUEL-S-1\"")); // method mismatch
        breakages.put(path("media", MEDIA, 2) + "#material", s -> s.replace("SYNTHETIC_UNCLASSIFIED_AEROSOL", "SYNTHETIC_COAL_COKE_LIKE"));
        breakages.put(path("media", MEDIA, 2) + "#holder", s -> s.replace("\"current_holder\":\"lab_demo@lab\"", "\"current_holder\":\"someone@else\""));
        for (var b : breakages.entrySet()) {
            String target = b.getKey().split("#")[0];
            var f = new LinkedHashMap<>(good);
            String changed = b.getValue().apply(f.get(target));
            assertThat(changed).as(b.getKey()).isNotEqualTo(f.get(target));
            f.put(target, changed);
            assertThat(lineage(f)).as(b.getKey() + " " + reasons(f)).isEqualTo("FAIL");
        }
        // Removing the media itself (still self-hashed) also fails.
        var noMedia = new LinkedHashMap<>(good); noMedia.remove(path("media", MEDIA, 2));
        assertThat(lineage(noMedia)).isEqualTo("FAIL");
        assertThat(lineage(good)).isEqualTo("PASS");
    }

    @Test void offlineLineageRejectsSelfAndTwoEventCustodyCyclesWithMatchingHashes() {
        var good = files(seal(withMedia()));
        String first = path("media_custody_event", ME1, 1);
        for (String previous : List.of(ME1, ME2)) {
            var cyclic = new LinkedHashMap<>(good);
            cyclic.put(first, good.get(first).replace("\"previous_event_id\":null", "\"previous_event_id\":\"" + previous + "\""));
            var report = graphs.verify(graphs.hand(cyclic, m -> {}, "PASS").export());
            assertThat(report.path("axes").path("integrity").asText()).isEqualTo("PASS");
            assertThat(report.path("axes").path("lineage").asText()).as("cycle through " + previous).isEqualTo("FAIL");
        }
        assertThat(lineage(good)).isEqualTo("PASS");
    }

    @Test void exactReferencesNeverCoerceFractionalTextOrOverflowingRevisions() {
        var media = files(seal(withMedia()));
        var fuel = files(seal(graphs.withSpecimen(false)));
        record Target(Map<String, String> files, String path, String pointer, int valid) {}
        var targets = List.of(
                new Target(media, path("media_lab_result", MLAB, 1), "/media_ref", 2),
                new Target(media, path("media_lab_result", MLAB, 1), "/case_ref", 2),
                new Target(media, path("media_lab_result", MLAB, 1), "/custody_ref", 1),
                new Target(media, path("case", CASE, 3), "/finding_ref", 1),
                new Target(media, path("case", CASE, 3), "/screening_refs/0", 1),
                new Target(media, path("finding", FINDING, 1), "/cited_refs/0", 1),
                new Target(fuel, path("lab_result", LAB, 1), "/specimen_ref", 1),
                new Target(fuel, path("lab_result", LAB, 1), "/case_ref", 2),
                new Target(fuel, path("lab_result", LAB, 1), "/custody_ref", 1));
        for (Target target : targets) {
            for (String token : List.of(target.valid() + ".5", target.valid() + ".0", "\"" + target.valid() + "\"", "true", "0", "-1",
                    new java.math.BigInteger("18446744073709551616").add(java.math.BigInteger.valueOf(target.valid())).toString())) {
                var changed = new LinkedHashMap<>(target.files());
                ObjectNode b = (ObjectNode) parse(changed.get(target.path()));
                assertThat(b.at(target.pointer()).isObject()).as(target.pointer()).isTrue();
                ((ObjectNode) b.at(target.pointer())).set("revision", parse(token));
                changed.put(target.path(), b.toString());
                var result = graphs.verify(graphs.hand(changed, m -> {}, "PASS").export());
                assertThat(result.path("axes").path("integrity").asText()).isEqualTo("PASS");
                assertThat(result.path("axes").path("lineage").asText()).as(target.path() + target.pointer() + " revision=" + token).isEqualTo("FAIL");
            }
        }
        assertThat(lineage(media)).isEqualTo("PASS");
        assertThat(lineage(fuel)).isEqualTo("PASS");
    }

    @Test void memberIdentityRevisionsArePositiveIntegralAndInRange() {
        var good = files(seal(withMedia()));
        for (String target : List.of(path("media", MEDIA, 2), path("media_lab_result", MLAB, 1), path("case", CASE, 3))) {
            int valid = parse(good.get(target)).path("revision").intValue();
            for (String token : List.of(valid + ".5", valid + ".0", "\"" + valid + "\"", "true", "0", "-1",
                    new java.math.BigInteger("18446744073709551616").add(java.math.BigInteger.valueOf(valid)).toString())) {
                var changed = new LinkedHashMap<>(good);
                ObjectNode body = (ObjectNode) parse(changed.get(target));
                body.set("revision", parse(token)); changed.put(target, body.toString());
                var report = graphs.verify(graphs.hand(changed, m -> {}, "PASS").export());
                assertThat(report.path("axes").path("integrity").asText()).isEqualTo("PASS");
                assertThat(report.path("axes").path("lineage").asText()).as(target + " identity revision=" + token).isEqualTo("FAIL");
            }
        }
    }

    @Test void legacyStructuralIntegersCannotWrapToValidRevisionsOrLengths() {
        var good = files(seal(withMedia()));
        var wrap = new java.math.BigInteger("18446744073709551616");
        for (String field : List.of("case_revision", "member_length", "export_manifest_length")) {
            var hand = graphs.hand(good, m -> {
                if (field.equals("case_revision")) m.put("case_revision", wrap.add(java.math.BigInteger.valueOf(3)));
                if (field.equals("member_length")) {
                    ObjectNode member = (ObjectNode) m.path("members").get(0);
                    member.put("length", wrap.add(member.path("length").bigIntegerValue()));
                }
            }, "PASS");
            ObjectNode exportBody = hand.export().body().deepCopy();
            if (field.equals("export_manifest_length")) exportBody.put("manifest_length", wrap.add(exportBody.path("manifest_length").bigIntegerValue()));
            assertThat(graphs.verify(export(exportBody)).path("axes").path("integrity").asText()).as(field).isEqualTo("FAIL");
        }
    }

    @Test void fuelLabResultReferencesAreTypeCheckedToo() {
        var good = files(seal(graphs.withSpecimen(false)));
        String lab = path("lab_result", LAB, 1);
        var wrong = new LinkedHashMap<>(good);
        wrong.put(lab, good.get(lab).replace("\"type\":\"custody_event\"", "\"type\":\"specimen\""));
        assertThat(lineage(wrong)).isEqualTo("FAIL");
        var otherSpecimen = new LinkedHashMap<>(good);
        otherSpecimen.put(path("custody_event", EV1, 1), good.get(path("custody_event", EV1, 1)).replace("\"specimen_id\":\"" + SPEC + "\"", "\"specimen_id\":\"" + OTHER + "\""));
        assertThat(lineage(otherSpecimen)).isEqualTo("FAIL");
        assertThat(lineage(good)).isEqualTo("PASS");
    }

    /** A pack whose only usable laboratory evidence is aerosol carries no fuel specimen site label. */
    @Test void aerosolOnlyPackCarriesNoTrainingSiteLabel() {
        var s = seal(withMedia());
        for (var m : s.members()) {
            if (!m.path().contains("/media")) continue;
            JsonNode b = parse(blobs().get(m.sha256()));
            assertThat(b.has("site_id")).as(m.path()).isFalse();
        }
        assertThat(s.members()).noneMatch(m -> m.path().contains("/specimen/") || m.path().contains("/lab_result/"));
    }
}
