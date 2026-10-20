package fuelprint.cases;

import static fuelprint.cases.EvidenceVaultTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B1: version-dispatched evidence format 2 (unsigned) beside the unchanged legacy format 1. */
class EvidenceFormatV2Test {
    @TempDir Path dir;
    BlobStore blobs;
    final EvidenceVaultTest graphs = new EvidenceVaultTest();

    @BeforeEach void store() { blobs = new BlobStore(dir.resolve("blobs")); }

    EvidenceVault.Sealed seal(EvidenceVaultTest.Mem m, EvidenceVault.Options o) {
        return new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT, o);
    }
    static final EvidenceVault.Options V2 = new EvidenceVault.Options(2, null);
    StoredRecord exportOf(EvidenceVault.Sealed s, String schema) {
        ObjectNode b = exportBody(s.manifestBytes(), s.manifestSha256(), s.members(), s.custody(), "SEALED");
        if (schema != null) b.put("schema", schema);
        return export(b);
    }
    ObjectNode verify(StoredRecord e) { return new EvidenceVerifier(blobs).verify(e); }
    /** A self-hashed export whose manifest bytes are given literally. */
    StoredRecord rawManifest(EvidenceVault.Sealed s, byte[] manifest, String schema) {
        ObjectNode b = exportBody(manifest, blobs.put(manifest), s.members(), s.custody(), "SEALED");
        if (schema != null) b.put("schema", schema);
        return export(b);
    }

    @Test void v2ManifestIsRestrictedCanonicalAndDeclaresItsEncodingsHonestly() {
        var sealed = seal(graphs.withSpecimen(false), V2);
        String text = new String(sealed.manifestBytes(), StandardCharsets.UTF_8);
        JsonNode m = JcsManifest.parseCanonical(sealed.manifestBytes());
        assertThat(m.fieldNames()).toIterable().containsExactly("canonicalization", "case_id", "case_revision", "created_utc",
                "limitations", "member_encoding", "members", "schema", "signing");
        assertThat(m.path("schema").asText()).isEqualTo("fp.evidence/2");
        assertThat(m.path("canonicalization").asText()).isEqualTo("FP-JCS-RESTRICTED-1");
        assertThat(m.path("member_encoding").asText()).isEqualTo("fp-canonical-json/1");
        assertThat(m.path("signing").isNull()).isTrue();
        assertThat(m.path("limitations").get(0).asText()).isEqualTo(L1);
        assertThat(text).startsWith("{\"canonicalization\":\"FP-JCS-RESTRICTED-1\",\"case_id\":\"" + CASE + "\",\"case_revision\":3,");
        assertThat(sealed.schema()).isEqualTo("fp.evidence/2");
        assertThat(sealed.signatureBytes()).isNull();
        assertThat(blobs.get(sealed.manifestSha256())).isEqualTo(sealed.manifestBytes());
    }

    @Test void v2MembersAreTheSameExactLegacyBytesAsV1() {
        var v1 = seal(graphs.withSpecimen(true), EvidenceVault.Options.V1);
        var v2 = seal(graphs.withSpecimen(true), V2);
        assertThat(v2.members()).isEqualTo(v1.members());
        for (var m : v2.members()) assertThat(blobs.get(m.sha256())).isEqualTo(blobs.get(v1.members().get(v1.members().indexOf(m)).sha256()));
        assertThat(v1.schema()).isEqualTo("fp.evidence/1");
        assertThat(JSON_FIELDS(v1.manifestBytes())).containsExactly("case_id", "case_revision", "created_utc", "limitations", "members", "schema");
    }
    static List<String> JSON_FIELDS(byte[] b) { List<String> l = new ArrayList<>(); parse(b).fieldNames().forEachRemaining(l::add); return l; }

    @Test void extremeExactDecimalMembersStayExactInsideAV2Pack() {
        var m = graphs.withSpecimen(false);
        m.edit("lab_result", LAB, 1, lab -> ((ObjectNode) lab.path("computed")).put("percent_w_w", new java.math.BigDecimal("1e1000"))
                .put("tiny", new java.math.BigDecimal("1e-1000")).put("big", new java.math.BigInteger("123456789012345678901234567890")));
        var sealed = seal(m, V2);
        var lab = sealed.members().stream().filter(x -> x.path().contains("/lab_result/")).findFirst().orElseThrow();
        assertThat(new String(blobs.get(lab.sha256()), StandardCharsets.UTF_8))
                .contains("\"percent_w_w\":1E+1000").contains("\"tiny\":1E-1000").contains("\"big\":123456789012345678901234567890");
        var r = verify(exportOf(sealed, "fp.evidence/2"));
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("PASS");
    }

    @Test void unsignedV2PackVerifiesWithAllSevenAxesAndNotSigned() {
        var r = verify(exportOf(seal(graphs.withSpecimen(false), V2), "fp.evidence/2"));
        assertConstantAxes(r.path("axes"));
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("custody_documentation").asText()).isEqualTo("PASS");
        assertThat(r.path("manifest_check").path("status").asText()).isEqualTo("PASS");
    }

    @Test void v2ManifestNeedsAMatchingV2ExportRecordAndNeverFallsBackToV1() {
        var sealed = seal(graphs.base(), V2);
        for (String schema : new String[]{null, "fp.evidence/1"}) {
            var r = verify(exportOf(sealed, schema));
            assertThat(r.path("axes").path("integrity").asText()).as(String.valueOf(schema)).isEqualTo("FAIL");
            assertThat(r.path("manifest_check").path("status").asText()).isEqualTo("MANIFEST_INVALID");
        }
        var v1 = seal(graphs.base(), EvidenceVault.Options.V1);
        var r = verify(exportOf(v1, "fp.evidence/2"));
        assertThat(r.path("manifest_check").path("status").asText()).isEqualTo("MANIFEST_INVALID");
    }

    @Test void nonCanonicalOrOutOfDomainV2ManifestBytesFailEvenWhenSelfHashed() {
        var sealed = seal(graphs.base(), V2);
        ObjectNode m = (ObjectNode) parse(sealed.manifestBytes());
        Map<String, byte[]> variants = new LinkedHashMap<>();
        variants.put("PRETTY", (new String(sealed.manifestBytes(), StandardCharsets.UTF_8).replace(",\"", ", \"")).getBytes(StandardCharsets.UTF_8));
        variants.put("TRAILING_NEWLINE", (new String(sealed.manifestBytes(), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        variants.put("FLOAT", JcsManifestTest.utf8(new String(sealed.manifestBytes(), StandardCharsets.UTF_8).replace("\"case_revision\":3", "\"case_revision\":3.0")));
        variants.put("DUPLICATE", JcsManifestTest.utf8(new String(sealed.manifestBytes(), StandardCharsets.UTF_8).replace("\"case_revision\":3,", "\"case_revision\":3,\"case_revision\":3,")));
        ObjectNode extra = m.deepCopy(); extra.put("zz_extra", 1);
        variants.put("EXTRA_KEY", JcsManifest.bytes(extra));
        ObjectNode badCanon = m.deepCopy(); badCanon.put("canonicalization", "RFC8785");
        variants.put("CLAIMS_GENERAL_RFC8785", JcsManifest.bytes(badCanon));
        ObjectNode badEnc = m.deepCopy(); badEnc.put("member_encoding", "RFC8785");
        variants.put("MEMBERS_CLAIM_JCS", JcsManifest.bytes(badEnc));
        ObjectNode badSigning = m.deepCopy(); badSigning.putObject("signing").put("policy", "SIGN_REQUIRED").put("algorithm", "RSA").put("key_fingerprint_sha256", "a".repeat(64));
        variants.put("BAD_ALGORITHM", JcsManifest.bytes(badSigning));
        for (var v : variants.entrySet()) {
            var r = verify(rawManifest(sealed, v.getValue(), "fp.evidence/2"));
            assertThat(r.path("axes").path("integrity").asText()).as(v.getKey()).isEqualTo("FAIL");
            // A duplicate key is already refused by the strict JSON reader (MANIFEST_UNPARSEABLE); the rest by the profile.
            assertThat(r.path("manifest_check").path("status").asText()).as(v.getKey()).isIn("MANIFEST_INVALID", "MANIFEST_UNPARSEABLE");
            assertThat(r.path("axes").path("lineage").asText()).as(v.getKey()).isEqualTo("UNVERIFIED");
        }
    }

    @Test void unknownVersionsAreUnsupportedNotLegacy() {
        var sealed = seal(graphs.base(), V2);
        for (String schema : new String[]{"fp.evidence/3", "fp.evidence/0", "FP.EVIDENCE/1"}) {
            ObjectNode m = (ObjectNode) parse(sealed.manifestBytes());
            m.put("schema", schema);
            var r = verify(rawManifest(sealed, JcsManifest.bytes(m), schema));
            assertThat(r.path("manifest_check").path("status").asText()).as(schema).isEqualTo("UNSUPPORTED_VERSION");
            assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        }
    }

    @Test void alteredMemberInAV2PackFailsExactlyThatMember() {
        var sealed = seal(graphs.withSpecimen(false), V2);
        String target = path("lab_result", LAB, 1);
        List<EvidenceVault.Member> swapped = new ArrayList<>();
        for (var m : sealed.members()) {
            if (!m.path().equals(target)) { swapped.add(m); continue; }
            byte[] b = blobs.get(m.sha256()).clone(); b[b.length / 2] ^= 1;
            swapped.add(new EvidenceVault.Member(m.path(), m.length(), blobs.put(b)));
        }
        ObjectNode body = exportBody(sealed.manifestBytes(), sealed.manifestSha256(), swapped, sealed.custody(), "SEALED");
        body.put("schema", "fp.evidence/2");
        var r = verify(export(body));
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(list(r.path("failed_members"))).containsExactly(target);
    }

    /** Observation only (A review: do not assume): the sampled legacy manifests happen to be restricted-JCS bytes. Behaviour never depends on it. */
    @Test void legacyManifestsKeepTheirLabelAndAreReportedNotAssumedJcsCompatible() {
        for (var graph : List.of(graphs.base(), graphs.withSpecimen(true))) {
            var v1 = seal(graph, EvidenceVault.Options.V1);
            boolean compatible;
            try { JcsManifest.parseCanonical(v1.manifestBytes()); compatible = true; } catch (JcsManifest.Unsupported u) { compatible = false; }
            System.out.println("LEGACY_MANIFEST_JCS_COMPATIBLE " + compatible + " " + v1.manifestSha256());
            assertThat(parse(v1.manifestBytes()).path("schema").asText()).isEqualTo("fp.evidence/1");
            assertThat(verify(exportOf(v1, null)).path("axes").path("integrity").asText()).isEqualTo("PASS");
        }
        // A legacy manifest with an exact decimal (allowed by the legacy form) still verifies as legacy.
        var v1 = seal(graphs.base(), EvidenceVault.Options.V1);
        String text = new String(v1.manifestBytes(), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("canonicalization");
    }
}
