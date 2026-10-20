package fuelprint.cases;

import static fuelprint.cases.EvidenceVaultTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B2: signature validity and external key trust stay separate; signed intent is bound and cannot be downgraded. */
class EvidenceSignatureTest {
    @TempDir Path dir;
    BlobStore blobs;
    TestKeys key, other;
    final EvidenceVaultTest graphs = new EvidenceVaultTest();

    @BeforeEach void setUp() {
        blobs = new BlobStore(dir.resolve("blobs"));
        key = TestKeys.write(dir.resolve("keys"), "signer");
        other = TestKeys.write(dir.resolve("keys"), "other");
    }

    EvidenceVault.Sealed sealSigned(TestKeys k) {
        var m = graphs.withSpecimen(false);
        return new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT, new EvidenceVault.Options(2, k.signer()));
    }
    /** The export record the seal handler saves for a signed v2 pack. */
    ObjectNode signedBody(EvidenceVault.Sealed s) {
        ObjectNode b = exportBody(s.manifestBytes(), s.manifestSha256(), s.members(), s.custody(), "SEALED");
        b.put("schema", "fp.evidence/2").put("signing_policy", "SIGN_REQUIRED");
        b.putObject("signature").put("path", "signature.json").put("length", s.signatureBytes().length).put("sha256", s.signatureSha256())
                .put("algorithm", "Ed25519").put("key_fingerprint_sha256", s.keyFingerprint());
        return b;
    }
    ObjectNode verify(ObjectNode body, EvidenceTrust trust) {
        return new EvidenceVerifier(EvidenceVerifier.blobLookup(blobs), trust, null).verify(export(body));
    }
    EvidenceTrust trusting(String... fps) { return EvidenceTrust.of(List.of(fps), "SERVER_CONFIG"); }
    static void sevenAxes(JsonNode r) {
        assertThat(r.path("axes").fieldNames()).toIterable().containsExactly("integrity", "key_trust", "lineage", "trusted_time",
                "custody_documentation", "measurement_qualification", "legal_suitability");
        assertThat(r.path("axes").path("trusted_time").asText()).isEqualTo("UNVERIFIED");
        assertThat(r.path("axes").path("measurement_qualification").asText()).isEqualTo("SYNTHETIC_SCOPE_ONLY");
        assertThat(r.path("axes").path("legal_suitability").asText()).isEqualTo("NOT_ASSESSED");
    }
    /** Re-stores an edited envelope and points the export at it. */
    ObjectNode withEnvelope(EvidenceVault.Sealed s, java.util.function.Consumer<ObjectNode> edit) {
        ObjectNode env = (ObjectNode) JcsManifest.parseCanonical(s.signatureBytes()).deepCopy();
        edit.accept(env);
        byte[] bytes = JcsManifest.bytes(env);
        ObjectNode b = signedBody(s);
        ((ObjectNode) b.path("signature")).put("sha256", blobs.put(bytes)).put("length", bytes.length);
        return b;
    }

    @Test void validSignatureFromATrustedFingerprintIsTrustedAndTheEnvelopeHoldsNoPrivateKey() {
        var s = sealSigned(key);
        JsonNode manifest = JcsManifest.parseCanonical(s.manifestBytes());
        assertThat(manifest.path("signing").path("key_fingerprint_sha256").asText()).isEqualTo(key.fingerprint());
        assertThat(manifest.path("limitations").get(0).asText()).isEqualTo(EvidenceVault.SIGNED_LIMITATION);
        JsonNode env = JcsManifest.parseCanonical(s.signatureBytes());
        assertThat(env.fieldNames()).toIterable().containsExactlyInAnyOrder("schema", "algorithm", "manifest_sha256", "public_key_spki_b64",
                "key_fingerprint_sha256", "signature_b64");
        assertThat(Base64.getDecoder().decode(env.path("signature_b64").asText())).hasSize(64);
        assertThat(EvidenceSigning.verify(s.manifestBytes(), Base64.getDecoder().decode(env.path("signature_b64").asText()), key.pair.getPublic())).isTrue();
        String everything = new String(s.manifestBytes(), StandardCharsets.UTF_8) + new String(s.signatureBytes(), StandardCharsets.UTF_8);
        assertThat(everything).doesNotContain(key.privateBase64()).doesNotContain("PRIVATE KEY");
        var r = verify(signedBody(s), trusting(key.fingerprint()));
        sevenAxes(r);
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
        assertThat(r.path("signature_check").path("validity").asText()).isEqualTo("VALID");
        assertThat(r.path("signature_check").path("trust").asText()).isEqualTo("TRUSTED");
        assertThat(r.path("signature_check").path("trust_source").asText()).isEqualTo("SERVER_CONFIG");
        assertThat(r.path("verifier_version").asText()).isEqualTo("fp-evidence-verify/1");
        assertThat(String.join(" ", list(r.path("reasons")))).contains("does not establish a person's identity, signing time, measurement truth, or legal suitability");
    }

    @Test void validSignatureFromAnUnlistedKeyIsUntrustedEvenThoughThePackCarriesTheKey() {
        var s = sealSigned(key);
        for (EvidenceTrust t : List.of(EvidenceTrust.NONE, trusting(other.fingerprint()))) {
            var r = verify(signedBody(s), t);
            sevenAxes(r);
            assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
            assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("UNTRUSTED_KEY");
            assertThat(r.path("signature_check").path("validity").asText()).isEqualTo("VALID");
            assertThat(r.path("signature_check").path("trust").asText()).isEqualTo("UNTRUSTED");
        }
    }

    @Test void alteredSignatureBytesAreInvalidWhileIntegrityStaysSeparate() {
        var s = sealSigned(key);
        var r = verify(withEnvelope(s, env -> {
            byte[] sig = Base64.getDecoder().decode(env.path("signature_b64").asText()); sig[5] ^= 1;
            env.put("signature_b64", Base64.getEncoder().encodeToString(sig));
        }), trusting(key.fingerprint()));
        sevenAxes(r);
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        assertThat(r.path("signature_check").path("code").asText()).isEqualTo("SIGNATURE_MISMATCH");
        assertThat(r.path("reasons").toString()).doesNotContain("The pack is unsigned");
    }

    @Test void strippingTheEnvelopeFromSignedIntentIsInvalidNotUnsigned() {
        var s = sealSigned(key);
        ObjectNode b = signedBody(s); b.putNull("signature");
        var r = verify(b, trusting(key.fingerprint()));
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        assertThat(r.path("signature_check").path("code").asText()).isEqualTo("SIGNED_INTENT_ENVELOPE_MISSING");
        ObjectNode gone = signedBody(s); ((ObjectNode) gone.path("signature")).put("sha256", "f".repeat(64));
        var r2 = verify(gone, trusting(key.fingerprint()));
        assertThat(r2.path("axes").path("key_trust").asText()).isEqualTo("UNVERIFIED");
        assertThat(r2.path("signature_check").path("validity").asText()).isEqualTo("UNVERIFIED");
    }

    @Test void replacingTheKeyAndSignatureIsDetectedByTheSignedDeclaration() {
        var s = sealSigned(key);
        byte[] forged = EvidenceSigning.sign(s.manifestBytes(), other.pair.getPrivate());
        // Envelope wholly re-made for the other key, but the signed manifest still declares the original signer.
        var r = verify(withEnvelope(s, env -> env.put("public_key_spki_b64", Base64.getEncoder().encodeToString(other.pair.getPublic().getEncoded()))
                .put("key_fingerprint_sha256", other.fingerprint()).put("signature_b64", Base64.getEncoder().encodeToString(forged))),
                trusting(key.fingerprint(), other.fingerprint()));
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        assertThat(r.path("signature_check").path("code").asText()).isEqualTo("KEY_FINGERPRINT_MISMATCH");
        // Envelope fingerprint left claiming the original while the embedded key is the other one.
        var r2 = verify(withEnvelope(s, env -> env.put("public_key_spki_b64", Base64.getEncoder().encodeToString(other.pair.getPublic().getEncoded()))
                .put("signature_b64", Base64.getEncoder().encodeToString(forged))), trusting(key.fingerprint(), other.fingerprint()));
        assertThat(r2.path("signature_check").path("code").asText()).isEqualTo("KEY_FINGERPRINT_MISMATCH");
    }

    @Test void envelopeForAnotherManifestOrWithUnsupportedFieldsFails() {
        var s = sealSigned(key);
        assertThat(verify(withEnvelope(s, env -> env.put("manifest_sha256", "0".repeat(64))), trusting(key.fingerprint()))
                .path("signature_check").path("code").asText()).isEqualTo("ENVELOPE_MANIFEST_MISMATCH");
        assertThat(verify(withEnvelope(s, env -> env.put("algorithm", "Ed448")), trusting(key.fingerprint()))
                .path("signature_check").path("code").asText()).isEqualTo("UNSUPPORTED_ALGORITHM");
        assertThat(verify(withEnvelope(s, env -> env.put("schema", "fp.evidence-signature/9")), trusting(key.fingerprint()))
                .path("signature_check").path("code").asText()).isEqualTo("UNSUPPORTED_SIGNATURE_VERSION");
        assertThat(verify(withEnvelope(s, env -> env.put("extra", "x")), trusting(key.fingerprint()))
                .path("signature_check").path("code").asText()).isEqualTo("ENVELOPE_MALFORMED");
        ObjectNode wrongLength = signedBody(s); ((ObjectNode) wrongLength.path("signature")).put("length", 3);
        assertThat(verify(wrongLength, trusting(key.fingerprint())).path("signature_check").path("code").asText()).isEqualTo("ENVELOPE_LENGTH_MISMATCH");
        // Non-canonical envelope bytes (pretty printed) are refused.
        byte[] pretty = new String(s.signatureBytes(), StandardCharsets.UTF_8).replace(",", ", ").getBytes(StandardCharsets.UTF_8);
        ObjectNode b = signedBody(s); ((ObjectNode) b.path("signature")).put("sha256", blobs.put(pretty)).put("length", pretty.length);
        assertThat(verify(b, trusting(key.fingerprint())).path("signature_check").path("validity").asText()).isEqualTo("UNVERIFIED");
    }

    @Test void anUnexpectedEnvelopeOnAnUnsignedPackIsRefused() {
        var m = graphs.withSpecimen(false);
        var unsigned = new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT, new EvidenceVault.Options(2, null));
        var signed = sealSigned(key);
        ObjectNode b = exportBody(unsigned.manifestBytes(), unsigned.manifestSha256(), unsigned.members(), unsigned.custody(), "SEALED");
        b.put("schema", "fp.evidence/2");
        b.putObject("signature").put("path", "signature.json").put("length", signed.signatureBytes().length).put("sha256", signed.signatureSha256());
        var r = verify(b, trusting(key.fingerprint()));
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        assertThat(r.path("signature_check").path("code").asText()).isEqualTo("UNEXPECTED_SIGNATURE_ENVELOPE");
        // The same on a legacy v1 pack.
        var v1 = new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT);
        ObjectNode legacy = exportBody(v1.manifestBytes(), v1.manifestSha256(), v1.members(), v1.custody(), "SEALED");
        legacy.set("signature", b.path("signature").deepCopy());
        assertThat(verify(legacy, trusting(key.fingerprint())).path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        // Unsigned v2 without an envelope is plainly NOT_SIGNED.
        ObjectNode plain = exportBody(unsigned.manifestBytes(), unsigned.manifestSha256(), unsigned.members(), unsigned.custody(), "SEALED");
        plain.put("schema", "fp.evidence/2").putNull("signature");
        var ok = verify(plain, trusting(key.fingerprint()));
        assertThat(ok.path("axes").path("key_trust").asText()).isEqualTo("NOT_SIGNED");
        assertThat(ok.path("signature_check").path("validity").asText()).isEqualTo("NOT_SIGNED");
    }

    @Test void aReplacedManifestFailsIntegrityOnTheServerAndASelfConsistentReplacementFailsTheSignature() {
        var s = sealSigned(key);
        ObjectNode m = (ObjectNode) JcsManifest.parseCanonical(s.manifestBytes()).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) m.path("limitations")).add("inserted later");
        byte[] altered = JcsManifest.bytes(m);
        // (1) Only the held bytes swapped: the export record's manifest expectation catches it.
        ObjectNode swapped = signedBody(s); ((ObjectNode) swapped.path("manifest")).put("sha256", blobs.put(altered));
        var r1 = verify(swapped, trusting(key.fingerprint()));
        assertThat(r1.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(r1.path("axes").path("key_trust").asText()).isEqualTo("UNVERIFIED");
        // (2) Expectation rewritten too: integrity matches the new bytes, but the signature does not.
        ObjectNode rewritten = signedBody(s);
        rewritten.put("manifest_sha256", EvidenceSigning.sha256(altered)).put("manifest_length", altered.length);
        ((ObjectNode) rewritten.path("manifest")).put("sha256", blobs.put(altered)).put("length", altered.length);
        var r2 = verify(rewritten, trusting(key.fingerprint()));
        assertThat(r2.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r2.path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        assertThat(r2.path("signature_check").path("code").asText()).isEqualTo("ENVELOPE_MANIFEST_MISMATCH");
    }

    @Test void anAlteredMemberFailsIntegrityWhileTheValidSignatureIsStillReportedSeparately() {
        var s = sealSigned(key);
        String target = path("lab_result", LAB, 1);
        ObjectNode b = signedBody(s);
        for (JsonNode mem : b.path("members")) if (mem.path("path").asText().equals(target)) {
            byte[] bytes = blobs.get(mem.path("sha256").asText()).clone(); bytes[bytes.length / 2] ^= 1;
            ((ObjectNode) mem).put("sha256", blobs.put(bytes));
        }
        var r = verify(b, trusting(key.fingerprint()));
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(list(r.path("failed_members"))).containsExactly(target);
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
    }

    @Test void legacyReportsAreUnchangedWithoutSignatureFields() {
        var m = graphs.withSpecimen(false);
        var v1 = new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT);
        ObjectNode b = exportBody(v1.manifestBytes(), v1.manifestSha256(), v1.members(), v1.custody(), "SEALED");
        b.putNull("signature");
        var r = verify(b, trusting(key.fingerprint()));
        assertThat(r.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(Set.of("axes", "reasons", "failed_members", "manifest_check", "member_checks"));
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("NOT_SIGNED");
    }
}
