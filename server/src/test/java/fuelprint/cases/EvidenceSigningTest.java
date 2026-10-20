package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B2 pure signing, trust anchors and the external configuration policy. Keys are temporary test keys only. */
class EvidenceSigningTest {
    @TempDir Path keys;      // stands for a folder outside the repository
    @TempDir Path repo;      // stands for the repository root

    @Test void ed25519VerifiesExactBytesOnly() {
        var pair = TestKeys.generate("Ed25519");
        byte[] manifest = "{\"version\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] signature = EvidenceSigning.sign(manifest, pair.getPrivate());
        assertThat(signature).hasSize(64);
        assertThat(EvidenceSigning.verify(manifest, signature, pair.getPublic())).isTrue();
        byte[] changed = manifest.clone();
        changed[changed.length - 2] ^= 1;
        assertThat(EvidenceSigning.verify(changed, signature, pair.getPublic())).isFalse();
        byte[] badSig = signature.clone(); badSig[10] ^= 1;
        assertThat(EvidenceSigning.verify(manifest, badSig, pair.getPublic())).isFalse();
        var other = TestKeys.generate("Ed25519");
        assertThat(EvidenceSigning.verify(manifest, signature, other.getPublic())).isFalse();
        assertThat(EvidenceSigning.verify(manifest, new byte[63], pair.getPublic())).isFalse();
        assertThat(EvidenceSigning.verify(manifest, null, pair.getPublic())).isFalse();
    }

    @Test void fingerprintIsSha256OfTheDerSpki() throws Exception {
        var pair = TestKeys.generate("Ed25519");
        byte[] spki = pair.getPublic().getEncoded();
        assertThat(spki).hasSize(44);
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(spki));
        assertThat(EvidenceTrust.fingerprint(pair.getPublic())).isEqualTo(expected).matches("[0-9a-f]{64}");
        assertThat(EvidenceSigning.publicKey(spki).getEncoded()).isEqualTo(spki);
    }

    @Test void nonEd25519KeysAreRefusedWithFixedCodes() {
        var rsa = TestKeys.generate("RSA");
        assertThatThrownBy(() -> EvidenceSigning.publicKey(rsa.getPublic().getEncoded()))
                .isInstanceOfSatisfying(EvidenceSigning.KeyProblem.class, p -> assertThat(p.code).isEqualTo("UNSUPPORTED_ALGORITHM"));
        assertThatThrownBy(() -> EvidenceSigning.privateKey(rsa.getPrivate().getEncoded()))
                .isInstanceOfSatisfying(EvidenceSigning.KeyProblem.class, p -> assertThat(p.code).isEqualTo("SIGNING_KEY_UNSUPPORTED_ALGORITHM"));
        var ed448 = TestKeys.generate("Ed448");
        assertThatThrownBy(() -> EvidenceSigning.publicKey(ed448.getPublic().getEncoded())).isInstanceOf(EvidenceSigning.KeyProblem.class);
    }

    @Test void trustAnchorsAreStrictAndBounded() throws Exception {
        String fp = "a".repeat(64);
        var t = EvidenceTrust.load(TestKeys.trustFile(keys, fp), "SERVER_CONFIG");
        assertThat(t.trusts(fp)).isTrue();
        assertThat(t.trusts("b".repeat(64))).isFalse();
        assertThat(EvidenceTrust.NONE.trusts(fp)).isFalse();
        for (String bad : new String[]{"{}", "[]", "{\"schema\":\"fp.evidence-trust/1\",\"trusted\":[{\"key_fingerprint_sha256\":\"ABC\",\"label\":\"x\"}]}",
                "{\"schema\":\"fp.evidence-trust/2\",\"trusted\":[]}", "{\"schema\":\"fp.evidence-trust/1\",\"trusted\":[],\"x\":1}",
                "{\"schema\":\"fp.evidence-trust/1\",\"schema\":\"fp.evidence-trust/1\",\"trusted\":[]}",
                "{\"schema\":\"fp.evidence-trust/1\",\"trusted\":[{\"key_fingerprint_sha256\":\"" + fp + "\"}]}"}) {
            Path p = keys.resolve("bad-" + System.nanoTime() + ".json");
            Files.writeString(p, bad);
            assertThatThrownBy(() -> EvidenceTrust.load(p, "X")).as(bad)
                    .isInstanceOfSatisfying(EvidenceSigning.KeyProblem.class, e -> assertThat(e.code).isEqualTo("TRUST_ANCHORS_INVALID"));
        }
        assertThatThrownBy(() -> EvidenceTrust.load(keys.resolve("absent.json"), "X"))
                .isInstanceOfSatisfying(EvidenceSigning.KeyProblem.class, e -> assertThat(e.code).isEqualTo("TRUST_ANCHORS_MISSING"));
        String[] many = new String[65];
        java.util.Arrays.fill(many, fp);
        assertThatThrownBy(() -> EvidenceTrust.of(java.util.List.of(many), "X")).isInstanceOf(EvidenceSigning.KeyProblem.class);
    }

    Path config(String json) throws Exception { Path p = keys.resolve("evidence-" + System.nanoTime() + ".json"); Files.writeString(p, json); return p; }
    String signed(TestKeys k) {
        return "{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + k.privatePem + "\",\"public_key_path\":\"" + k.publicPem + "\"}";
    }

    @Test void missingConfigIsLegacyUnsignedAndTheSupportedCombinationsLoad() throws Exception {
        var none = EvidenceConfig.load(keys.resolve("no-such.json"), repo);
        assertThat(none.ok()).isTrue(); assertThat(none.format()).isEqualTo(1); assertThat(none.signer()).isNull();
        var v1 = EvidenceConfig.load(config("{\"format\":1,\"signing_policy\":\"UNSIGNED\"}"), repo);
        assertThat(v1.ok()).isTrue(); assertThat(v1.options()).isEqualTo(EvidenceVault.Options.V1);
        var v2 = EvidenceConfig.load(config("{\"format\":2,\"signing_policy\":\"UNSIGNED\"}"), repo);
        assertThat(v2.ok()).isTrue(); assertThat(v2.format()).isEqualTo(2); assertThat(v2.signer()).isNull();
        var k = TestKeys.write(keys, "good");
        var s = EvidenceConfig.load(config(signed(k)), repo);
        assertThat(s.ok()).as(String.valueOf(s.reason())).isTrue();
        assertThat(s.format()).isEqualTo(2);
        assertThat(s.signer().fingerprint()).isEqualTo(k.fingerprint());
        byte[] msg = "m".getBytes(StandardCharsets.UTF_8);
        assertThat(EvidenceSigning.verify(msg, s.signer().sign(msg), k.pair.getPublic())).isTrue();
        assertThat(s.signer().toString()).doesNotContain(k.privateBase64());
    }

    @Test void unsupportedCombinationsAreRefusedNeverDowngraded() throws Exception {
        var k = TestKeys.write(keys, "k");
        String[][] cases = {
                {"{\"format\":1,\"signing_policy\":\"SIGN_REQUIRED\"}", "EVIDENCE_CONFIG_INVALID"},
                {"{\"format\":2,\"signing_policy\":\"SIGN_IF_CONFIGURED\"}", "EVIDENCE_CONFIG_INVALID"},
                {"{\"format\":3}", "EVIDENCE_CONFIG_INVALID"},
                {"{\"format\":\"2\"}", "EVIDENCE_CONFIG_INVALID"},
                {"{\"format\":2,\"extra\":true}", "EVIDENCE_CONFIG_INVALID"},
                {"{\"format\":2,\"signing_policy\":\"UNSIGNED\",\"private_key_path\":\"" + k.privatePem + "\"}", "EVIDENCE_CONFIG_INVALID"},
                {"not json", "EVIDENCE_CONFIG_INVALID"},
                {"[]", "EVIDENCE_CONFIG_INVALID"},
                {"{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\"}", "SIGNING_KEY_MISSING"},
                {"{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + keys.resolve("absent.pem") + "\",\"public_key_path\":\"" + k.publicPem + "\"}", "SIGNING_KEY_MISSING"},
        };
        for (String[] c : cases) {
            var s = EvidenceConfig.load(config(c[0]), repo);
            assertThat(s.ok()).as(c[0]).isFalse();
            assertThat(s.code()).as(c[0]).isEqualTo(c[1]);
            assertThat(s.signer()).isNull();
        }
    }

    @Test void badKeysAreRefusedWithKeyFreeReasons() throws Exception {
        var a = TestKeys.write(keys, "a");
        var b = TestKeys.write(keys, "b");
        // Public key of a different pair.
        var mismatch = EvidenceConfig.load(config("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + a.privatePem
                + "\",\"public_key_path\":\"" + b.publicPem + "\"}"), repo);
        assertThat(mismatch.code()).isEqualTo("KEY_PAIR_MISMATCH");
        // RSA private key.
        var rsa = TestKeys.write(keys, "rsa", TestKeys.generate("RSA"));
        assertThat(EvidenceConfig.load(config("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + rsa.privatePem
                + "\",\"public_key_path\":\"" + a.publicPem + "\"}"), repo).code()).isEqualTo("SIGNING_KEY_UNSUPPORTED_ALGORITHM");
        // Garbage PEM and oversized file.
        Path garbage = keys.resolve("garbage.pem"); Files.writeString(garbage, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");
        assertThat(EvidenceConfig.load(config("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + garbage
                + "\",\"public_key_path\":\"" + a.publicPem + "\"}"), repo).code()).isIn("SIGNING_KEY_INVALID", "SIGNING_KEY_UNSUPPORTED_ALGORITHM");
        Path big = keys.resolve("big.pem"); Files.writeString(big, "x".repeat(5000));
        assertThat(EvidenceConfig.load(config("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + big
                + "\",\"public_key_path\":\"" + a.publicPem + "\"}"), repo).code()).isEqualTo("SIGNING_KEY_INVALID");
        Path wrongLabel = keys.resolve("wrong.pem"); Files.writeString(wrongLabel, Files.readString(a.privatePem).replace("PRIVATE KEY", "RSA PRIVATE KEY"));
        assertThat(EvidenceConfig.load(config("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + wrongLabel
                + "\",\"public_key_path\":\"" + a.publicPem + "\"}"), repo).code()).isEqualTo("SIGNING_KEY_INVALID");
        // A key inside the repository is refused even when valid.
        var inside = TestKeys.write(repo.resolve("server/config"), "inside");
        var in = EvidenceConfig.load(config(signed(inside)), repo);
        assertThat(in.code()).isEqualTo("KEY_INSIDE_REPOSITORY");
        for (var s : java.util.List.of(mismatch, in))
            assertThat(s.reason()).doesNotContain(a.privateBase64()).doesNotContain(inside.privateBase64()).doesNotContain(keys.toString())
                    .contains("never falls back to unsigned");
    }

    @Test void signerFailureBecomesAnExplicitSealRefusalNotAnUnsignedPack() throws Exception {
        var graphs = new EvidenceVaultTest();
        var blobs = new fuelprint.core.store.BlobStore(keys.resolve("blobs"));
        EvidenceVault.Signer broken = new EvidenceVault.Signer() {
            public String fingerprint() { return "c".repeat(64); }
            public byte[] publicKeySpki() { return new byte[44]; }
            public byte[] sign(byte[] manifest) { throw new EvidenceSigning.KeyProblem("SIGNING_FAILED", "x"); }
        };
        var m = graphs.base();
        assertThatThrownBy(() -> new EvidenceVault(blobs).seal(m, m.get("case", EvidenceVaultTest.CASE, 3).orElseThrow(), EvidenceVaultTest.SEALED_AT,
                new EvidenceVault.Options(2, broken)))
                .isInstanceOfSatisfying(EvidenceVault.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_SIGNING_FAILED"));
        assertThatThrownBy(() -> new EvidenceVault.Options(1, broken)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------- R1: verification trust is independent of the private signing key ----------------

    String signedWithTrust(Path priv, Path pub, Path trust) {
        return "{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + priv + "\",\"public_key_path\":\"" + pub
                + "\",\"trust_anchors_path\":\"" + trust + "\"}";
    }

    @Test void r1VerificationKeepsValidTrustedWhenTheSigningKeyIsGoneOrMismatched() throws Exception {
        var k = TestKeys.write(keys.resolve("r1"), "signer");
        var other = TestKeys.write(keys.resolve("r1"), "other");
        Path trustFile = TestKeys.trustFile(keys, k.fingerprint());
        Path cfg = config(signedWithTrust(k.privatePem, k.publicPem, trustFile));
        var settings = EvidenceConfig.load(cfg, repo);
        assertThat(settings.ok()).isTrue();
        // A signed pack sealed while the key was available.
        var graphs = new EvidenceVaultTest();
        var blobs = new fuelprint.core.store.BlobStore(keys.resolve("r1-blobs"));
        var m = graphs.withSpecimen(false);
        var sealed = new EvidenceVault(blobs).seal(m, m.get("case", EvidenceVaultTest.CASE, 3).orElseThrow(), EvidenceVaultTest.SEALED_AT, settings.options());
        var body = EvidenceVaultTest.exportBody(sealed.manifestBytes(), sealed.manifestSha256(), sealed.members(), sealed.custody(), "SEALED");
        body.put("schema", "fp.evidence/2");
        body.putObject("signature").put("path", "signature.json").put("length", sealed.signatureBytes().length).put("sha256", sealed.signatureSha256());
        var export = EvidenceVaultTest.export(body);

        java.util.List<String> broken = new java.util.ArrayList<>();
        Files.delete(k.privatePem);
        broken.add(signedWithTrust(k.privatePem, k.publicPem, trustFile));                       // private key removed
        broken.add(signedWithTrust(other.privatePem, k.publicPem, trustFile));                   // mismatched pair
        Path corrupt = keys.resolve("r1/corrupt.pem"); Files.writeString(corrupt, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");
        broken.add(signedWithTrust(corrupt, k.publicPem, trustFile));                            // corrupted private key
        broken.add(signedWithTrust(Path.of("/nonexistent/" + "x"), k.publicPem, trustFile));     // unreadable location
        for (String json : broken) {
            Path c = config(json);
            var seal = EvidenceConfig.load(c, repo);
            assertThat(seal.ok()).as(json).isFalse();
            assertThat(seal.signer()).isNull();
            var trust = EvidenceConfig.trust(c);
            assertThat(trust.problem()).as(json).isNull();
            assertThat(trust.trust().trusts(k.fingerprint())).isTrue();
            var r = new EvidenceVerifier(EvidenceVerifier.blobLookup(blobs), trust.trust(), trust.problem()).verify(export);
            assertThat(r.path("signature_check").path("validity").asText()).isEqualTo("VALID");
            assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
        }
        // Trust loading never touches the private key: even an invalid (NUL) private key path leaves trust intact.
        var nul = EvidenceConfig.trust(config("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"a\\u0000b\",\"public_key_path\":\""
                + k.publicPem + "\",\"trust_anchors_path\":\"" + trustFile + "\"}"));
        assertThat(nul.problem()).isNull();
        assertThat(nul.trust().trusts(k.fingerprint())).isTrue();
        // An invalid or missing trust file is never trusted and gives a fixed reason.
        Path bad = keys.resolve("bad-trust.json"); Files.writeString(bad, "{\"schema\":\"x\"}");
        var invalid = EvidenceConfig.trust(config(signedWithTrust(other.privatePem, other.publicPem, bad)));
        assertThat(invalid.trust().trusts(k.fingerprint())).isFalse();
        assertThat(invalid.problem()).isEqualTo("TRUST_ANCHORS_INVALID");
        var missing = EvidenceConfig.trust(config(signedWithTrust(other.privatePem, other.publicPem, keys.resolve("absent-trust.json"))));
        assertThat(missing.trust().trusts(k.fingerprint())).isFalse();
        assertThat(missing.problem()).isEqualTo("TRUST_ANCHORS_MISSING");
        var r = new EvidenceVerifier(EvidenceVerifier.blobLookup(blobs), missing.trust(), missing.problem()).verify(export);
        assertThat(r.path("axes").path("key_trust").asText()).isEqualTo("UNTRUSTED_KEY");
        assertThat(String.join(" ", EvidenceVaultTest.list(r.path("reasons")))).contains("TRUST_ANCHORS_MISSING").doesNotContain(keys.toString());
    }

    // ---------------- R2: invalid path strings are fixed, path-free refusals ----------------

    @Test void r2NulInAnyConfiguredPathIsAFixedRefusalWithoutExceptionOrEcho() throws Exception {
        var k = TestKeys.write(keys.resolve("r2"), "signer");
        Path trustFile = TestKeys.trustFile(keys, k.fingerprint());
        String secretish = "SECRET-DIR";
        String nul = secretish + "\\u0000x";
        String[][] cases = {
                {"{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + nul + "\",\"public_key_path\":\"" + k.publicPem + "\"}", "SIGNING_KEY_INVALID"},
                {"{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + k.privatePem + "\",\"public_key_path\":\"" + nul + "\"}", "PUBLIC_KEY_INVALID"},
                {"{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + k.privatePem + "\",\"public_key_path\":\"" + k.publicPem
                        + "\",\"trust_anchors_path\":\"" + nul + "\"}", "EVIDENCE_CONFIG_INVALID"},
                {"{\"format\":2,\"signing_policy\":\"UNSIGNED\",\"trust_anchors_path\":\"" + nul + "\"}", "EVIDENCE_CONFIG_INVALID"},
        };
        for (String[] c : cases) {
            Path cfg = config(c[0]);
            var s = EvidenceConfig.load(cfg, repo);
            assertThat(s.ok()).as(c[0]).isFalse();
            assertThat(s.code()).as(c[0]).isEqualTo(c[1]);
            assertThat(s.signer()).isNull();
            assertThat(s.reason()).doesNotContain(secretish).doesNotContain(keys.toString()).contains("never falls back to unsigned");
            var t = EvidenceConfig.trust(cfg);
            if (c[0].contains("trust_anchors_path")) {
                assertThat(t.problem()).isEqualTo("TRUST_ANCHORS_INVALID");
                assertThat(t.trust().trusts(k.fingerprint())).isFalse();
            }
        }
        // A NUL key path does not disturb a valid trust file.
        var t = EvidenceConfig.trust(config("{\"format\":2,\"signing_policy\":\"SIGN_REQUIRED\",\"private_key_path\":\"" + nul
                + "\",\"public_key_path\":\"" + nul + "\",\"trust_anchors_path\":\"" + trustFile + "\"}"));
        assertThat(t.trust().trusts(k.fingerprint())).isTrue();
        // The configuration location property itself.
        var bad = new EvidenceConfig("cfg" + '\u0000' + secretish);
        assertThat(bad.settings().code()).isEqualTo("EVIDENCE_CONFIG_INVALID");
        assertThat(bad.settings().reason()).doesNotContain(secretish);
        assertThat(bad.trust().problem()).isEqualTo("EVIDENCE_CONFIG_INVALID");
        assertThat(bad.trust().trust().size()).isZero();
        // Default (missing file) behaviour and ordinary valid external paths are unchanged.
        assertThat(new EvidenceConfig(keys.resolve("none.json").toString()).settings().format()).isEqualTo(1);
        assertThat(EvidenceConfig.load(config(signed(k)), repo).ok()).isTrue();
    }
}
