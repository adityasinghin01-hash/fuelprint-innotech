package fuelprint.cases;

import static fuelprint.cases.EvidenceVaultTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B3: bounded ZIP pack, store-free offline verification, CLI exit codes and a clean separate Java process. */
class EvidenceArchiveTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;
    BlobStore blobs;
    TestKeys key, other;
    final EvidenceVaultTest graphs = new EvidenceVaultTest();

    @BeforeEach void setUp() {
        blobs = new BlobStore(dir.resolve("blobs"));
        key = TestKeys.write(dir.resolve("keys"), "signer");
        other = TestKeys.write(dir.resolve("keys"), "other");
    }

    StoredRecord signedExport() {
        var m = graphs.withSpecimen(false);
        var s = new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT, new EvidenceVault.Options(2, key.signer()));
        ObjectNode b = exportBody(s.manifestBytes(), s.manifestSha256(), s.members(), s.custody(), "SEALED");
        b.put("schema", "fp.evidence/2").put("signing_policy", "SIGN_REQUIRED");
        b.putObject("signature").put("path", "signature.json").put("length", s.signatureBytes().length).put("sha256", s.signatureSha256())
                .put("algorithm", "Ed25519").put("key_fingerprint_sha256", s.keyFingerprint());
        return export(b);
    }
    StoredRecord unsignedExport(int format) {
        var m = graphs.withSpecimen(false);
        var s = new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT, format == 1 ? EvidenceVault.Options.V1 : new EvidenceVault.Options(2, null));
        ObjectNode b = exportBody(s.manifestBytes(), s.manifestSha256(), s.members(), s.custody(), "SEALED");
        if (format == 2) b.put("schema", "fp.evidence/2");
        b.putNull("signature");
        return export(b);
    }
    Path pack(StoredRecord e) { return EvidenceArchive.prepare(e, blobs, dir.resolve("tmp")); }
    EvidenceTrust trusted() { return EvidenceTrust.of(List.of(key.fingerprint()), "CLI_ARGUMENT"); }
    EvidenceOffline.Result offline(Path p, EvidenceTrust t, boolean allowUnsigned) { return EvidenceOffline.verify(p, t, allowUnsigned); }

    /** Entries of a ZIP in order (exact bytes). */
    static LinkedHashMap<String, byte[]> entries(Path zip) throws Exception {
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        try (var z = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) out.put(e.getName(), z.readAllBytes());
        }
        return out;
    }
    Path zip(Map<String, byte[]> entries) throws Exception {
        Path p = Files.createTempFile(dir, "crafted-", ".zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
        }
        return p;
    }
    Path bytes(byte[] b) throws Exception { Path p = Files.createTempFile(dir, "raw-", ".zip"); Files.write(p, b); return p; }
    static byte[] replaceAll(byte[] data, byte[] from, byte[] to) {
        byte[] out = data.clone();
        outer:
        for (int i = 0; i + from.length <= out.length; i++) {
            for (int j = 0; j < from.length; j++) if (out[i + j] != from[j]) continue outer;
            System.arraycopy(to, 0, out, i, to.length);
        }
        return out;
    }
    static String refusal(EvidenceOffline.Result r) { return r.json().path("archive_check").path("code").asText(); }

    // ------------------------------------------------------------------ writer

    @Test void preparedPackHasTheFixedLayoutAndExactStoredBytes() throws Exception {
        var e = signedExport();
        Path p = pack(e);
        var entries = entries(p);
        List<String> names = new ArrayList<>(entries.keySet());
        assertThat(names.get(0)).isEqualTo("manifest.json");
        assertThat(names.get(1)).isEqualTo("signature.json");
        List<String> expected = new ArrayList<>();
        e.body().path("members").forEach(m -> expected.add(m.path("path").asText()));
        assertThat(names.subList(2, names.size())).containsExactlyElementsOf(expected);
        assertThat(entries.get("manifest.json")).isEqualTo(blobs.get(e.body().path("manifest").path("sha256").asText()));
        for (JsonNode m : e.body().path("members")) assertThat(entries.get(m.path("path").asText())).isEqualTo(blobs.get(m.path("sha256").asText()));
        if (!System.getProperty("os.name").toLowerCase().contains("win"))
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(p))).isEqualTo("rw-------");
        assertThat(entries(pack(unsignedExport(1))).keySet()).doesNotContain("signature.json");
    }

    @Test void aMissingOrDamagedLaterBlobLeavesNoPartialPackBehind() throws Exception {
        var e = signedExport();
        ObjectNode b = e.body();
        ArrayNode members = (ArrayNode) b.path("members");
        ((ObjectNode) members.get(members.size() - 1)).put("sha256", "f".repeat(64));
        Path tmp = dir.resolve("tmp-missing");
        assertThatThrownBy(() -> EvidenceArchive.prepare(export(b), blobs, tmp))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_MISSING"));
        try (var s = Files.list(tmp)) { assertThat(s.toList()).isEmpty(); }
        ObjectNode b3 = signedExport().body();
        // Damaged bytes on disk for the last member.
        var e2 = signedExport();
        String sha = e2.body().path("members").get(e2.body().path("members").size() - 1).path("sha256").asText();
        Files.write(dir.resolve("blobs").resolve(sha), "damaged".getBytes(StandardCharsets.UTF_8));
        Path tmp2 = dir.resolve("tmp-damaged");
        assertThatThrownBy(() -> EvidenceArchive.prepare(e2, blobs, tmp2))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_STORAGE"));
        try (var s = Files.list(tmp2)) { assertThat(s.toList()).isEmpty(); }
        // Wrong length in the locator (prepared before the damage above, so its first member is still intact).
        ((ObjectNode) b3.path("members").get(0)).put("length", 1);
        assertThatThrownBy(() -> EvidenceArchive.prepare(export(b3), blobs, dir.resolve("tmp3")))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_STORAGE"));
    }

    @Test void writerLimitsAreEnforced() {
        var small = new EvidenceArchive.Limits(64L << 20, 500, 2, 10, 1 << 20, 16 * 1024, 64L << 20, 200);
        assertThatThrownBy(() -> EvidenceArchive.prepare(signedExport(), blobs, dir.resolve("t"), small))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_TOO_LARGE"));
        var tinyZip = new EvidenceArchive.Limits(100, 500, 2, 1 << 20, 1 << 20, 16 * 1024, 64L << 20, 200);
        assertThatThrownBy(() -> EvidenceArchive.prepare(signedExport(), blobs, dir.resolve("t2"), tinyZip))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_TOO_LARGE"));
    }

    // ------------------------------------------------------------------ offline verification and exit codes

    @Test void validSignedPackIsTrustedOnlyWithAnExternalFingerprint() {
        Path p = pack(signedExport());
        var ok = offline(p, trusted(), false);
        assertThat(ok.exitCode()).as(ok.json().toString()).isZero();
        assertThat(ok.json().path("result").asText()).isEqualTo("PASS");
        assertThat(ok.json().path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
        assertThat(ok.json().path("axes").path("custody_documentation").asText()).isEqualTo("PASS");
        assertThat(ok.json().path("signature_check").path("trust_source").asText()).isEqualTo("CLI_ARGUMENT");
        assertThat(ok.json().path("scope_note").asText()).isEqualTo(EvidenceOffline.SCOPE_NOTE);
        assertThat(ok.json().path("axes").fieldNames()).toIterable().hasSize(7);
        assertThat(offline(p, EvidenceTrust.NONE, false).exitCode()).isEqualTo(8);
        assertThat(offline(p, EvidenceTrust.NONE, true).exitCode()).as("--allow-unsigned never excuses an untrusted signer").isEqualTo(8);
        assertThat(offline(p, EvidenceTrust.of(List.of(other.fingerprint()), "CLI_ARGUMENT"), false).exitCode()).isEqualTo(8);
    }

    @Test void unsignedPacksNeedTheExplicitOptionAndSayItPlainly() {
        for (int format : new int[]{1, 2}) {
            Path p = pack(unsignedExport(format));
            var strict = offline(p, trusted(), false);
            assertThat(strict.exitCode()).isEqualTo(8);
            var allowed = offline(p, EvidenceTrust.NONE, true);
            assertThat(allowed.exitCode()).as(allowed.json().toString()).isZero();
            assertThat(allowed.json().path("axes").path("key_trust").asText()).isEqualTo("NOT_SIGNED");
            assertThat(allowed.json().path("signature_check").path("trust").asText()).isEqualTo("NOT_EVALUATED");
            assertThat(allowed.json().toString()).contains("cannot show that the manifest is the one originally sealed");
        }
    }

    @Test void alteredMemberManifestOrSignatureMapToTheirAxesAndCodes() throws Exception {
        Path good = pack(signedExport());
        var entries = entries(good);
        String member = new ArrayList<>(entries.keySet()).get(3);
        // Altered member bytes: integrity FAIL (5), signature still valid and trusted.
        var m1 = new LinkedHashMap<>(entries); byte[] b = m1.get(member).clone(); b[b.length / 2] ^= 1; m1.put(member, b);
        var r1 = offline(zip(m1), trusted(), false);
        assertThat(r1.exitCode()).isEqualTo(5);
        assertThat(r1.json().path("axes").path("key_trust").asText()).isEqualTo("TRUSTED");
        // Altered, still canonical manifest whose list still matches: signature INVALID (7).
        ObjectNode man = (ObjectNode) JcsManifest.parseCanonical(entries.get("manifest.json")).deepCopy();
        ((ArrayNode) man.path("limitations")).add("inserted later");
        var m2 = new LinkedHashMap<>(entries); m2.put("manifest.json", JcsManifest.bytes(man));
        var r2 = offline(zip(m2), trusted(), true);
        assertThat(r2.exitCode()).isEqualTo(7);
        assertThat(r2.json().path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r2.json().path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        // Altered manifest member hash: integrity FAIL (5) with the signature failure also listed.
        ObjectNode man3 = (ObjectNode) JcsManifest.parseCanonical(entries.get("manifest.json")).deepCopy();
        ((ObjectNode) man3.path("members").get(0)).put("sha256", "0".repeat(64));
        var m3 = new LinkedHashMap<>(entries); m3.put("manifest.json", JcsManifest.bytes(man3));
        var r3 = offline(zip(m3), trusted(), false);
        assertThat(r3.exitCode()).isEqualTo(5);
        assertThat(r3.json().path("axes").path("key_trust").asText()).isEqualTo("SIGNATURE_INVALID");
        // Altered signature bytes (7), envelope stripped from signed intent (7 even with --allow-unsigned).
        ObjectNode env = (ObjectNode) JcsManifest.parseCanonical(entries.get("signature.json")).deepCopy();
        byte[] sig = Base64.getDecoder().decode(env.path("signature_b64").asText()); sig[0] ^= 1;
        env.put("signature_b64", Base64.getEncoder().encodeToString(sig));
        var m4 = new LinkedHashMap<>(entries); m4.put("signature.json", JcsManifest.bytes(env));
        assertThat(offline(zip(m4), trusted(), false).exitCode()).isEqualTo(7);
        var m5 = new LinkedHashMap<>(entries); m5.remove("signature.json");
        var r5 = offline(zip(m5), trusted(), true);
        assertThat(r5.exitCode()).isEqualTo(7);
        assertThat(r5.json().path("signature_check").path("code").asText()).isEqualTo("SIGNED_INTENT_ENVELOPE_MISSING");
        // Wrong key: envelope re-made with another key; trusted list contains both (7).
        byte[] forged = EvidenceSigning.sign(entries.get("manifest.json"), other.pair.getPrivate());
        ObjectNode env6 = (ObjectNode) JcsManifest.parseCanonical(entries.get("signature.json")).deepCopy();
        env6.put("public_key_spki_b64", Base64.getEncoder().encodeToString(other.pair.getPublic().getEncoded()))
                .put("key_fingerprint_sha256", other.fingerprint()).put("signature_b64", Base64.getEncoder().encodeToString(forged));
        var m6 = new LinkedHashMap<>(entries); m6.put("signature.json", JcsManifest.bytes(env6));
        assertThat(offline(zip(m6), EvidenceTrust.of(List.of(key.fingerprint(), other.fingerprint()), "CLI_ARGUMENT"), false).exitCode()).isEqualTo(7);
        // Unsupported envelope algorithm or version (4).
        ObjectNode env7 = (ObjectNode) JcsManifest.parseCanonical(entries.get("signature.json")).deepCopy(); env7.put("schema", "fp.evidence-signature/9");
        var m7 = new LinkedHashMap<>(entries); m7.put("signature.json", JcsManifest.bytes(env7));
        assertThat(offline(zip(m7), trusted(), false).exitCode()).isEqualTo(4);
    }

    @Test void unexpectedEnvelopeAndUnsupportedManifestVersion() throws Exception {
        var unsigned = entries(pack(unsignedExport(2)));
        var signedEntries = entries(pack(signedExport()));
        var m1 = new LinkedHashMap<String, byte[]>(); m1.put("manifest.json", unsigned.get("manifest.json")); m1.put("signature.json", signedEntries.get("signature.json"));
        unsigned.forEach((k, v) -> { if (!k.equals("manifest.json")) m1.put(k, v); });
        var r1 = offline(zip(m1), trusted(), true);
        assertThat(r1.exitCode()).isEqualTo(3);
        assertThat(refusal(r1)).isEqualTo("UNEXPECTED_SIGNATURE_ENVELOPE");
        ObjectNode man = (ObjectNode) JcsManifest.parseCanonical(unsigned.get("manifest.json")).deepCopy(); man.put("schema", "fp.evidence/3");
        var m2 = new LinkedHashMap<>(unsigned); m2.put("manifest.json", JcsManifest.bytes(man));
        assertThat(offline(zip(m2), trusted(), true).exitCode()).isEqualTo(4);
        // A manifest that is not JSON fails integrity as MANIFEST_UNPARSEABLE (exit 5); only resource limits are archive refusals.
        var r3 = offline(zip(Map.of("manifest.json", "not json".getBytes(StandardCharsets.UTF_8))), trusted(), true);
        assertThat(r3.exitCode()).isEqualTo(5);
    }

    @Test void lineageFailureWithIntactBytesIsExitSix() throws Exception {
        var entries = entries(pack(unsignedExport(2)));
        // Remove the cited lab result from both the ZIP and a rebuilt manifest: hashes self-consistent, lineage broken.
        String lab = path("lab_result", LAB, 1);
        ObjectNode man = (ObjectNode) JcsManifest.parseCanonical(entries.get("manifest.json")).deepCopy();
        ArrayNode kept = JSON.createArrayNode();
        for (JsonNode m : man.path("members")) if (!m.path("path").asText().equals(lab)) kept.add(m);
        man.set("members", kept);
        var m = new LinkedHashMap<>(entries); m.remove(lab); m.put("manifest.json", JcsManifest.bytes(man));
        var r = offline(zip(m), EvidenceTrust.NONE, true);
        assertThat(r.json().path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.json().path("axes").path("lineage").asText()).isEqualTo("FAIL");
        assertThat(r.exitCode()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ archive refusals (exit 3)

    @Test void unsafeNamesDuplicatesExtraMissingAndDirectoriesAreRefused() throws Exception {
        var entries = entries(pack(signedExport()));
        String first = new ArrayList<>(entries.keySet()).get(2);
        Map<String, String> badNames = new LinkedHashMap<>();
        badNames.put("payload/records/../../etc/passwd", "UNSAFE_NAME");
        badNames.put("/etc/passwd", "UNSAFE_NAME");
        badNames.put("payload\\records\\case\\x.json", "UNSAFE_NAME");
        badNames.put("C:/x.json", "UNSAFE_NAME");
        badNames.put("//server/share/x.json", "UNSAFE_NAME");
        badNames.put("payload/records/case/a\u0000.json", "UNSAFE_NAME");
        badNames.put("payload/records/case/./x.json", "UNSAFE_NAME");
        badNames.put("payload//records/x.json", "UNSAFE_NAME");
        badNames.put("payload/records/case/\u00e9.json", "UNSAFE_NAME");
        badNames.put("notes.txt", "UNSAFE_NAME");
        badNames.put(first.toUpperCase(Locale.ROOT), "UNSAFE_NAME");
        badNames.put("payload/", "UNSAFE_NAME");
        for (var bad : badNames.entrySet()) {
            var m = new LinkedHashMap<>(entries); m.put(bad.getKey(), "{}".getBytes(StandardCharsets.UTF_8));
            var r = offline(zip(m), trusted(), false);
            assertThat(r.exitCode()).as(bad.getKey()).isEqualTo(3);
            assertThat(refusal(r)).as(bad.getKey()).isEqualTo(bad.getValue());
        }
        // An undeclared but well-formed payload entry, and a missing member.
        var extra = new LinkedHashMap<>(entries); extra.put(path("case", OTHER, 1), "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(refusal(offline(zip(extra), trusted(), false))).isEqualTo("UNDECLARED_MEMBER");
        var missing = new LinkedHashMap<>(entries); missing.remove(first);
        assertThat(refusal(offline(zip(missing), trusted(), false))).isEqualTo("MISSING_MEMBER");
        var noManifest = new LinkedHashMap<>(entries); noManifest.remove("manifest.json");
        assertThat(refusal(offline(zip(noManifest), trusted(), false))).isEqualTo("MANIFEST_MISSING");
        // Duplicate entry name: crafted by renaming a same-length entry in both headers.
        String twin = first.substring(0, first.length() - 6) + "9.json";
        assertThat(twin).hasSameSizeAs(first).isNotEqualTo(first);
        var dup = new LinkedHashMap<>(entries); dup.put(twin, entries.get(first));
        byte[] crafted = replaceAll(Files.readAllBytes(zip(dup)), twin.getBytes(StandardCharsets.US_ASCII), first.getBytes(StandardCharsets.US_ASCII));
        var rd = offline(bytes(crafted), trusted(), false);
        assertThat(rd.exitCode()).isEqualTo(3);
        assertThat(refusal(rd)).isIn("DUPLICATE_ENTRY", "MALFORMED_ARCHIVE");
    }

    @Test void truncatedPrefixedEncryptedAndNonZipInputsAreRefused() throws Exception {
        byte[] good = Files.readAllBytes(pack(signedExport()));
        for (int cut : new int[]{good.length / 2, good.length - 10, 30, 0}) {
            var r = offline(bytes(Arrays.copyOf(good, cut)), trusted(), false);
            assertThat(r.exitCode()).as("cut " + cut).isEqualTo(3);
        }
        byte[] prefixed = new byte[good.length + 16];
        System.arraycopy(good, 0, prefixed, 16, good.length);
        assertThat(offline(bytes(prefixed), trusted(), false).exitCode()).isEqualTo(3);
        assertThat(offline(bytes("hello world, not a zip".getBytes(StandardCharsets.UTF_8)), trusted(), false).exitCode()).isEqualTo(3);
        // Encrypted flag (general purpose bit 0) set on the first local header and its central directory entry.
        byte[] enc = good.clone();
        for (int i = 0; i + 4 <= enc.length; i++) {
            if (enc[i] == 0x50 && enc[i + 1] == 0x4b && enc[i + 2] == 0x03 && enc[i + 3] == 0x04) { enc[i + 6] |= 1; break; }
        }
        for (int i = 0; i + 4 <= enc.length; i++) {
            if (enc[i] == 0x50 && enc[i + 1] == 0x4b && enc[i + 2] == 0x01 && enc[i + 3] == 0x02) { enc[i + 8] |= 1; break; }
        }
        var re = offline(bytes(enc), trusted(), false);
        assertThat(re.exitCode()).isEqualTo(3);
        assertThat(refusal(re)).isIn("ENCRYPTED_ENTRY", "MALFORMED_ARCHIVE");
    }

    @Test void oversizedEntriesDecompressionBombsAndEntryCountsAreRefusedWhileReading() throws Exception {
        var entries = entries(pack(unsignedExport(2)));
        String first = new ArrayList<>(entries.keySet()).get(1);
        // One member expanding beyond 1 MiB from a tiny compressed size.
        var bomb = new LinkedHashMap<>(entries); bomb.put(first, new byte[(1 << 20) + 1]);
        Path bombZip = zip(bomb);
        assertThat(Files.size(bombZip)).isLessThan(64 * 1024);
        assertThat(refusal(offline(bombZip, EvidenceTrust.NONE, true))).isEqualTo("ENTRY_TOO_LARGE");
        // Total expansion over the limit with every entry individually small (small limits stand for 64 MiB).
        var limits = new EvidenceArchive.Limits(64L << 20, 500, 2, 1 << 20, 1 << 20, 16 * 1024, 2_000, 200);
        assertThat(refusal(EvidenceOffline.verify(zip(entries), EvidenceTrust.NONE, true, limits))).isEqualTo("EXPANDED_TOO_LARGE");
        var fewEntries = new EvidenceArchive.Limits(64L << 20, 3, 2, 1 << 20, 1 << 20, 16 * 1024, 64L << 20, 200);
        assertThat(refusal(EvidenceOffline.verify(zip(entries), EvidenceTrust.NONE, true, fewEntries))).isEqualTo("TOO_MANY_ENTRIES");
        var smallFile = new EvidenceArchive.Limits(500, 500, 2, 1 << 20, 1 << 20, 16 * 1024, 64L << 20, 200);
        assertThat(refusal(EvidenceOffline.verify(zip(entries), EvidenceTrust.NONE, true, smallFile))).isEqualTo("PACK_TOO_LARGE");
        var smallManifest = new EvidenceArchive.Limits(64L << 20, 500, 2, 1 << 20, 100, 16 * 1024, 64L << 20, 200);
        assertThat(refusal(EvidenceOffline.verify(zip(entries), EvidenceTrust.NONE, true, smallManifest))).isEqualTo("ENTRY_TOO_LARGE");
    }

    @Test void tamperCopyPacksAreServedAsTheyAreAndFailWhileTheOriginalPasses() throws Exception {
        var e = signedExport();
        ObjectNode copy = e.body();
        ObjectNode target = (ObjectNode) copy.path("members").get(0);
        byte[] bytes = blobs.get(target.path("sha256").asText()).clone(); bytes[bytes.length / 2] ^= 1;
        target.put("sha256", blobs.put(bytes));
        copy.put("state", "TAMPER_DEMO_COPY");
        Path tampered = EvidenceArchive.prepare(export(copy), blobs, dir.resolve("tmp"));
        assertThat(offline(tampered, trusted(), false).exitCode()).isEqualTo(5);
        assertThat(offline(pack(e), trusted(), false).exitCode()).isZero();
    }

    // ------------------------------------------------------------------ CLI

    record Run(int code, JsonNode json, String stderr) {}
    Run cli(String... args) throws Exception {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = EvidenceVerifyCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, JSON.readTree(out.toString(StandardCharsets.UTF_8)), err.toString(StandardCharsets.UTF_8));
    }

    @Test void nonObjectRecordPayloadsAreNamedIntegrityFailuresNeverInternalErrors() throws Exception {
        var fixture = new EvidenceMediaLineageTest(); fixture.dir = dir.resolve("media-shapes"); fixture.setUp();
        var sealed = fixture.seal(fixture.withMedia());
        var mediaExport = export(exportBody(sealed.manifestBytes(), sealed.manifestSha256(), sealed.members(), sealed.custody(), "SEALED"));
        Path mediaPack = EvidenceArchive.prepare(mediaExport, fixture.blobs(), dir.resolve("media-pack"));
        for (Path base : List.of(pack(unsignedExport(1)), mediaPack)) {
            var original = entries(base);
            for (String target : original.keySet().stream().filter(n -> n.startsWith("payload/records/")).sorted(Comparator.comparingInt(n -> n.contains("/custody_event/") || n.contains("/media_custody_event/") ? 0 : 1)).toList()) {
                for (String value : List.of("[]", "\"not a record\"", "2", "null", "true")) {
                    var changed = new LinkedHashMap<>(original);
                    byte[] bad = value.getBytes(StandardCharsets.UTF_8); changed.put(target, bad);
                    ObjectNode manifest = (ObjectNode) JSON.readTree(changed.get("manifest.json"));
                    for (JsonNode member : manifest.path("members")) if (target.equals(member.path("path").asText()))
                        ((ObjectNode) member).put("length", bad.length).put("sha256", EvidenceSigning.sha256(bad));
                    changed.put("manifest.json", JSON.writeValueAsBytes(manifest));
                    var result = cli("--pack", zip(changed).toString(), "--allow-unsigned");
                    assertThat(result.code()).as(target + "=" + value + " " + result.json()).isEqualTo(5);
                    assertThat(result.json().path("member_checks")).anySatisfy(check -> {
                        assertThat(check.path("path").asText()).isEqualTo(target);
                        assertThat(check.path("status").asText()).isEqualTo("MEMBER_UNPARSEABLE");
                    });
                }
            }
        }
    }

    @Test void cliArgumentsAndUsageErrors() throws Exception {
        Path p = pack(signedExport());
        assertThat(cli("--pack", p.toString(), "--trust-sha256", key.fingerprint()).code()).isZero();
        assertThat(cli("--pack", p.toString()).code()).isEqualTo(8);
        Path trust = TestKeys.trustFile(dir, key.fingerprint());
        assertThat(cli("--pack", p.toString(), "--trust-file", trust.toString()).code()).isZero();
        for (String[] bad : new String[][]{{}, {"--pack"}, {"--pack", dir.resolve("absent.zip").toString()}, {"--pack", p.toString(), "--bogus"},
                {"--pack", p.toString(), "--trust-sha256", "ABC"}, {"--pack", p.toString(), "--pack", p.toString()},
                {"--pack", p.toString(), "--trust-file", dir.resolve("absent.json").toString()}}) {
            var r = cli(bad);
            assertThat(r.code()).as(String.join(" ", bad)).isEqualTo(2);
            assertThat(r.json().path("result").asText()).isEqualTo("USAGE_ERROR");
        }
    }

    /** A separate JVM in an empty folder: no server, no database, no configuration, no inherited environment. */
    @Test void cleanSeparateJavaProcessGivesTheDocumentedExitCodes() throws Exception {
        Path work = Files.createDirectory(dir.resolve("clean"));
        String packOut = System.getProperty("fp.cases.pack-out");
        Path good = Files.copy(pack(signedExport()), work.resolve("pack.zip"));
        var entries = entries(good);
        String member = new ArrayList<>(entries.keySet()).get(2);
        byte[] b = entries.get(member).clone(); b[b.length / 2] ^= 1;
        var tampered = new LinkedHashMap<>(entries); tampered.put(member, b);
        Path bad = Files.copy(zip(tampered), work.resolve("tampered.zip"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        record Case(Path pack, List<String> extra, int code) {}
        for (var c : List.of(new Case(good, List.of("--trust-sha256", key.fingerprint()), 0), new Case(good, List.of(), 8),
                new Case(bad, List.of("--trust-sha256", key.fingerprint()), 5))) {
            List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), "fuelprint.cases.EvidenceVerifyCli",
                    "--pack", c.pack().getFileName().toString()));
            cmd.addAll(c.extra());
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile());
            pb.environment().clear();
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.getErrorStream().readAllBytes();
            assertThat(proc.waitFor()).as(stdout).isEqualTo(c.code());
            JsonNode out = JSON.readTree(stdout);
            assertThat(out.path("exit_code").asInt()).isEqualTo(c.code());
            assertThat(out.path("axes").fieldNames()).toIterable().hasSize(7);
        }
        try (var s = Files.list(work)) { assertThat(s.map(x -> x.getFileName().toString()).toList()).containsExactlyInAnyOrder("pack.zip", "tampered.zip"); }
        // B5: hand the same packs (and only the public fingerprint) to the packaged-jar launch check outside the repository.
        if (packOut != null && !packOut.isBlank()) {
            Path o = Files.createDirectories(Path.of(packOut));
            Files.copy(good, o.resolve("pack.zip"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(bad, o.resolve("tampered.zip"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(o.resolve("trusted-fingerprint.txt"), key.fingerprint());
        }
    }

    // ------------------------------------------------------------------ R3: byte limits before hashing or loading

    @Test void r3OversizedPackIsRefusedWithoutBeingHashed() throws Exception {
        Path p = pack(signedExport());
        var small = new EvidenceArchive.Limits(500, 500, 2, 1 << 20, 1 << 20, 16 * 1024, 64L << 20, 200);
        var r = EvidenceOffline.verify(p, trusted(), false, small);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(refusal(r)).isEqualTo("PACK_TOO_LARGE");
        assertThat(r.json().path("pack").path("sha256").isNull()).isTrue();
        assertThat(r.json().path("pack").path("bytes").asLong()).isEqualTo(Files.size(p));
        // The bounded snapshot stops at the bound instead of copying (or hashing) the whole input.
        assertThat(EvidenceOffline.snapshot(p, 100)).isNull();
        var full = EvidenceOffline.snapshot(p, Files.size(p));
        assertThat(full).isNotNull();
        assertThat(full.sha256()).isEqualTo(EvidenceSigning.sha256(Files.readAllBytes(p)));
        EvidenceArchive.delete(full.file());
        // Within the bound the pack sha256 is reported.
        assertThat(offline(p, trusted(), false).json().path("pack").path("sha256").asText()).isEqualTo(EvidenceSigning.sha256(Files.readAllBytes(p)));
    }

    @Test void r3BoundedReadsStopAtTheBound() throws Exception {
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[100_000]);
        assertThatThrownBy(() -> EvidenceJson.readBounded(big, 10)).isInstanceOf(EvidenceJson.TooLarge.class);
        assertThat(EvidenceJson.readBounded(big, 100_000)).hasSize(100_000);
    }

    @Test void r3AnOversizedStoredSourceBlobIsNeverLoadedUnboundedly() throws Exception {
        var e = signedExport();
        var members = e.body().path("members");
        String sha = members.get(members.size() - 1).path("sha256").asText();
        Files.write(dir.resolve("blobs").resolve(sha), new byte[(1 << 20) * 3]);   // grown far beyond the 1 MiB entry bound
        Path tmp = dir.resolve("tmp-oversized");
        assertThatThrownBy(() -> EvidenceArchive.prepare(e, blobs, tmp))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_STORAGE"));
        try (var s = Files.list(tmp)) { assertThat(s.toList()).isEmpty(); }
        assertThatThrownBy(() -> EvidenceArchive.boundedBlob(blobs, sha, 1 << 20, "a member"))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.getMessage()).contains("larger than"));
        assertThatThrownBy(() -> EvidenceArchive.boundedBlob(blobs, "a".repeat(64), 1 << 20, "a member"))
                .isInstanceOfSatisfying(EvidenceArchive.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_MISSING"));
    }

    // ------------------------------------------------------------------ R4: JSON depth and encoding on every entry

    /** n nested arrays around one string. */
    static String nest(int n) { return "[".repeat(n) + "\"x\"" + "]".repeat(n); }

    /** Replaces one payload member of an unsigned v1 pack and rewrites the manifest so every hash matches. */
    Path withMember(String member, byte[] bytes) throws Exception {
        var entries = entries(pack(unsignedExport(1)));
        ObjectNode man = (ObjectNode) JSON.readTree(entries.get("manifest.json"));
        for (JsonNode m : man.path("members")) if (m.path("path").asText().equals(member))
            ((ObjectNode) m).put("length", bytes.length).put("sha256", EvidenceSigning.sha256(bytes));
        var out = new LinkedHashMap<>(entries);
        out.put("manifest.json", CanonicalJson.bytes(man));
        out.put(member, bytes);
        return zip(out);
    }
    Path withManifestLimitations(String limitationsJson, boolean v2) throws Exception {
        var entries = entries(pack(unsignedExport(v2 ? 2 : 1)));
        String m = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        int i = m.indexOf("\"limitations\":[") + "\"limitations\":".length();
        int depth = 0, j = i;
        do { char ch = m.charAt(j); if (ch == '[') depth++; else if (ch == ']') depth--; j++; } while (depth > 0);
        var out = new LinkedHashMap<>(entries);
        out.put("manifest.json", (m.substring(0, i) + limitationsJson + m.substring(j)).getBytes(StandardCharsets.UTF_8));
        return zip(out);
    }
    String attemptPath() { return path("attempt", ATTEMPT, 1); }
    String attemptText() throws Exception { return new String(entries(pack(unsignedExport(1))).get(attemptPath()), StandardCharsets.UTF_8); }

    @Test void r4DepthBoundaryInAPayloadMemberAndOneBeyondIt() throws Exception {
        String base = attemptText();
        // Root object is depth 0; "a_deep" is depth 1; its innermost string is depth 1 + n.
        String at = base.replaceFirst("^\\{", "{\"a_deep\":" + nest(63) + ",");
        String beyond = base.replaceFirst("^\\{", "{\"a_deep\":" + nest(64) + ",");
        var ok = offline(withMember(attemptPath(), at.getBytes(StandardCharsets.UTF_8)), EvidenceTrust.NONE, true);
        assertThat(ok.exitCode()).as(ok.json().toString()).isZero();
        var r = offline(withMember(attemptPath(), beyond.getBytes(StandardCharsets.UTF_8)), EvidenceTrust.NONE, true);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(refusal(r)).isEqualTo("JSON_TOO_DEEP");
        // The same rule as the accepted writer: CanonicalJson writes the boundary and refuses one beyond.
        assertThat(CanonicalJson.text(JSON.readTree(at))).isNotEmpty();
        assertThatThrownBy(() -> CanonicalJson.text(JSON.readTree(beyond))).isInstanceOf(CanonicalJson.Unsupported.class);
    }

    @Test void r4DepthBoundaryInV1AndV2Manifests() throws Exception {
        for (boolean v2 : new boolean[]{false, true}) {
            var ok = offline(withManifestLimitations(nest(63), v2), EvidenceTrust.NONE, true);
            assertThat(ok.exitCode()).as((v2 ? "v2 " : "v1 ") + ok.json()).isZero();
            var r = offline(withManifestLimitations(nest(64), v2), EvidenceTrust.NONE, true);
            assertThat(r.exitCode()).as(v2 ? "v2" : "v1").isEqualTo(3);
            assertThat(refusal(r)).isEqualTo("JSON_TOO_DEEP");
        }
    }

    @Test void r4MalformedUtf8DuplicatesAndTrailingContentInPayloadsAreRefused() throws Exception {
        String base = attemptText();
        byte[] invalid = base.replace("\"COMPLETE\"", "\"COMPLETE#\"").getBytes(StandardCharsets.UTF_8);
        for (int k = 0; k < invalid.length; k++) if (invalid[k] == '#') invalid[k] = (byte) 0xC3;
        // Self-consistent hashes; the bytes are refused (never replaced) and the member is named: integrity FAIL, exit 5.
        for (byte[] badBytes : List.of(invalid, base.replaceFirst("^\\{", "{\"state\":\"X\",").getBytes(StandardCharsets.UTF_8),
                (base + "{}").getBytes(StandardCharsets.UTF_8))) {
            var r1 = offline(withMember(attemptPath(), badBytes), EvidenceTrust.NONE, true);
            assertThat(r1.exitCode()).as(r1.json().toString()).isEqualTo(5);
            assertThat(r1.json().path("axes").path("integrity").asText()).isEqualTo("FAIL");
            boolean named = false;
            for (JsonNode c : r1.json().path("member_checks"))
                if (c.path("path").asText().equals(attemptPath())) { named = true; assertThat(c.path("status").asText()).isEqualTo("MEMBER_UNPARSEABLE"); }
            assertThat(named).isTrue();
        }
        // The strict checks themselves.
        assertThat(code(() -> EvidenceJson.check(invalid))).isEqualTo("INVALID_UTF8");
        assertThat(code(() -> EvidenceJson.check(base.replaceFirst("^\\{", "{\"state\":\"X\",").getBytes(StandardCharsets.UTF_8)))).isEqualTo("DUPLICATE_PROPERTY");
        assertThat(code(() -> EvidenceJson.check((base + "{}").getBytes(StandardCharsets.UTF_8)))).isEqualTo("TRAILING_CONTENT");
        assertThat(code(() -> EvidenceJson.check(("{\"a\":" + nest(64) + "}").getBytes(StandardCharsets.UTF_8)))).isEqualTo("JSON_TOO_DEEP");
        // On the server, a self-consistent export whose manifest expects exactly these bytes still refuses them as MEMBER_UNPARSEABLE.
        ObjectNode b = unsignedExport(1).body();
        ObjectNode man = (ObjectNode) JSON.readTree(blobs.get(b.path("manifest").path("sha256").asText()));
        for (JsonNode m : man.path("members")) if (m.path("path").asText().equals(attemptPath()))
            ((ObjectNode) m).put("length", invalid.length).put("sha256", EvidenceSigning.sha256(invalid));
        byte[] mb = CanonicalJson.bytes(man);
        b.put("manifest_sha256", EvidenceSigning.sha256(mb)).put("manifest_length", mb.length);
        ((ObjectNode) b.path("manifest")).put("sha256", blobs.put(mb)).put("length", mb.length);
        for (JsonNode m : b.path("members")) if (m.path("path").asText().equals(attemptPath()))
            ((ObjectNode) m).put("sha256", blobs.put(invalid)).put("length", invalid.length);
        var r = new EvidenceVerifier(blobs).verify(export(b));
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(r.path("member_checks").toString()).contains("MEMBER_UNPARSEABLE");
    }

    @Test void r4ExactLegacyDecimalsStillVerifyOffline() throws Exception {
        var m = graphs.withSpecimen(false);
        m.edit("lab_result", LAB, 1, lab -> ((ObjectNode) lab.path("computed")).put("percent_w_w", new java.math.BigDecimal("1e1000"))
                .put("tiny", new java.math.BigDecimal("1e-1000")).put("big", new java.math.BigInteger("123456789012345678901234567890")));
        var s = new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT);
        ObjectNode b = exportBody(s.manifestBytes(), s.manifestSha256(), s.members(), s.custody(), "SEALED");
        b.putNull("signature");
        Path p = pack(export(b));
        String lab = new String(entries(p).get(path("lab_result", LAB, 1)), StandardCharsets.UTF_8);
        assertThat(lab).contains("1E+1000").contains("1E-1000").contains("123456789012345678901234567890");
        var r = offline(p, EvidenceTrust.NONE, true);
        assertThat(r.exitCode()).as(r.json().toString()).isZero();
    }

    static String code(Runnable r) {
        try { r.run(); } catch (EvidenceJson.Bad b) { return b.code; }
        return "ACCEPTED";
    }

    // ------------------------------------------------------------------ R5/R6: CLI trust file and path text

    @Test void r5TrustFileIsReadOnceBoundedAndStrict() throws Exception {
        Path p = pack(signedExport());
        assertThat(cli("--pack", p.toString(), "--trust-file", TestKeys.trustFile(dir, key.fingerprint()).toString()).code()).isZero();
        assertThat(cli("--pack", p.toString(), "--trust-file", TestKeys.trustFile(dir, other.fingerprint()).toString()).code()).isEqualTo(8);
        // Invalid signature stays 7 with a trusted file.
        var entries = entries(p);
        ObjectNode env = (ObjectNode) JcsManifest.parseCanonical(entries.get("signature.json")).deepCopy();
        byte[] sig = Base64.getDecoder().decode(env.path("signature_b64").asText()); sig[0] ^= 1;
        env.put("signature_b64", Base64.getEncoder().encodeToString(sig));
        var m = new LinkedHashMap<>(entries); m.put("signature.json", JcsManifest.bytes(env));
        assertThat(cli("--pack", zip(m).toString(), "--trust-file", TestKeys.trustFile(dir, key.fingerprint()).toString()).code()).isEqualTo(7);
        String fp = key.fingerprint();
        String one = "{\"key_fingerprint_sha256\":\"" + fp + "\",\"label\":\"a\"}";
        for (String bad : new String[]{"not json", "{\"schema\":\"fp.evidence-trust/1\",\"trusted\":[" + one + "]}{}",
                "{\"schema\":\"fp.evidence-trust/1\",\"schema\":\"fp.evidence-trust/1\",\"trusted\":[" + one + "]}",
                "{\"schema\":\"fp.evidence-trust/1\",\"trusted\":[" + one + "]," + "\"pad\":\"" + "x".repeat(17000) + "\"}"}) {
            Path f = dir.resolve("trust-bad-" + System.nanoTime() + ".json"); Files.writeString(f, bad);
            var r = cli("--pack", p.toString(), "--trust-file", f.toString());
            assertThat(r.code()).as(bad.substring(0, Math.min(40, bad.length()))).isEqualTo(2);
        }
        // Combined file + argument anchors stay within 64.
        String[] sixty = new String[60];
        for (int i = 0; i < 60; i++) sixty[i] = String.format("%064x", i + 1);
        Path f60 = TestKeys.trustFile(dir, sixty);
        assertThat(cli("--pack", p.toString(), "--trust-file", f60.toString()).code()).isEqualTo(8);
        assertThat(cli("--pack", p.toString(), "--trust-file", f60.toString(), "--trust-sha256", fp).code()).as("file and argument anchors are merged").isZero();
        List<String> args = new ArrayList<>(List.of("--pack", p.toString(), "--trust-file", f60.toString()));
        for (int i = 0; i < 5; i++) { args.add("--trust-sha256"); args.add(String.format("%064x", 100 + i)); }
        assertThat(cli(args.toArray(String[]::new)).code()).isEqualTo(2);
    }

    @Test void r6InvalidPathTextIsAUsageErrorAndIsNotEchoed() throws Exception {
        Path p = pack(signedExport());
        for (String[] a : new String[][]{{"--pack", "SECRET\u0000pack.zip"}, {"--pack", p.toString(), "--trust-file", "SECRET\u0000trust.json"}, {"--pack", ""}}) {
            var r = cli(a);
            assertThat(r.code()).isEqualTo(2);
            assertThat(r.json().path("result").asText()).isEqualTo("USAGE_ERROR");
            assertThat(r.json().toString() + r.stderr()).doesNotContain("SECRET");
        }
    }

    // ------------------------------------------------------------------ B5: more adversarial archive structure

    static int find(byte[] b, int from, int... sig) {
        outer:
        for (int i = from; i + sig.length <= b.length; i++) {
            for (int j = 0; j < sig.length; j++) if ((b[i + j] & 0xff) != sig[j]) continue outer;
            return i;
        }
        return -1;
    }
    @Test void centralMetadataMustAgreeWithTheActualLocalEntry() throws Exception {
        byte[] original = Files.readAllBytes(pack(signedExport()));
        int central = find(original, 0, 0x50, 0x4b, 0x01, 0x02);
        for (String field : List.of("method", "crc", "compressed_size")) {
            byte[] changed = original.clone();
            switch (field) {
                case "method" -> { changed[central + 10] = 0; changed[central + 11] = 0; }
                case "crc" -> changed[central + 16] ^= 1;
                case "compressed_size" -> le32(changed, central + 20, le32(changed, central + 20) + 1);
                default -> throw new AssertionError(field);
            }
            var result = offline(bytes(changed), trusted(), false);
            assertThat(result.exitCode()).as("central " + field).isEqualTo(3);
            assertThat(refusal(result)).as(field).isEqualTo("ENTRY_METADATA_MISMATCH");
        }
    }

    static void le32(byte[] b, int at, long v) { for (int k = 0; k < 4; k++) b[at + k] = (byte) (v >>> (8 * k)); }
    static long le32(byte[] b, int at) { long v = 0; for (int k = 3; k >= 0; k--) v = (v << 8) | (b[at + k] & 0xff); return v; }

    @Test void localAndCentralNameDisagreementIsRefused() throws Exception {
        byte[] good = Files.readAllBytes(pack(unsignedExport(1)));
        String name = new ArrayList<>(entries(pack(unsignedExport(1))).keySet()).get(1);
        String renamed = name.substring(0, name.length() - 6) + "9.json";
        assertThat(renamed).isNotEqualTo(name).hasSameSizeAs(name);
        byte[] b = good.clone();
        int central = find(b, 0, 0x50, 0x4b, 0x01, 0x02);
        int at = new String(b, StandardCharsets.ISO_8859_1).indexOf(name, central);
        System.arraycopy(renamed.getBytes(StandardCharsets.US_ASCII), 0, b, at, renamed.length());
        var r = offline(bytes(b), EvidenceTrust.NONE, true);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(refusal(r)).isIn("ENTRY_LIST_MISMATCH", "MALFORMED_ARCHIVE");
    }

    @Test void unsupportedCompressionMethodIsRefused() throws Exception {
        byte[] b = Files.readAllBytes(pack(unsignedExport(1)));
        int local = find(b, 0, 0x50, 0x4b, 0x03, 0x04), central = find(b, 0, 0x50, 0x4b, 0x01, 0x02);
        b[local + 8] = 12; b[local + 9] = 0;      // method 12 = bzip2
        b[central + 10] = 12; b[central + 11] = 0;
        var r = offline(bytes(b), EvidenceTrust.NONE, true);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(refusal(r)).isIn("UNSUPPORTED_ENTRY", "MALFORMED_ARCHIVE");
    }

    @Test void badCentralSizeAndBadDataDescriptorAreRefused() throws Exception {
        byte[] good = Files.readAllBytes(pack(unsignedExport(1)));
        byte[] b = good.clone();
        int central = find(b, 0, 0x50, 0x4b, 0x01, 0x02);
        le32(b, central + 24, le32(b, central + 24) + 1);          // central uncompressed size off by one
        var r = offline(bytes(b), EvidenceTrust.NONE, true);
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(refusal(r)).isIn("SIZE_MISMATCH", "MALFORMED_ARCHIVE");
        byte[] d = good.clone();
        int descriptor = find(d, 0, 0x50, 0x4b, 0x07, 0x08);
        assertThat(descriptor).isPositive();
        le32(d, descriptor + 12, le32(d, descriptor + 12) + 1);    // data descriptor uncompressed size off by one
        var r2 = offline(bytes(d), EvidenceTrust.NONE, true);
        assertThat(r2.exitCode()).isEqualTo(3);
        assertThat(refusal(r2)).isIn("SIZE_MISMATCH", "MALFORMED_ARCHIVE");
        byte[] c = good.clone();
        int desc = find(c, 0, 0x50, 0x4b, 0x07, 0x08);
        c[desc + 4] ^= 1;                                           // data descriptor CRC
        assertThat(offline(bytes(c), EvidenceTrust.NONE, true).exitCode()).isEqualTo(3);
    }
}
