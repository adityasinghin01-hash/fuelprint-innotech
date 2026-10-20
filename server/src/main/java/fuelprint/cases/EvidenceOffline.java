package fuelprint.cases;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.StoredRecord;

/**
 * Store-free verification of one evidence pack file: no server, database, network or configuration. The bounded
 * archive reader supplies exact bytes; the same {@link EvidenceVerifier} core as the server checks them. Offline there
 * is no export record, so the manifest bytes in the pack are their own expectation; changes to a signed manifest are
 * caught by the signature, and an unsigned pack cannot show that its manifest is the original.
 */
final class EvidenceOffline {
    static final String SCOPE_NOTE = "Offline check of these bytes only. A valid signature verifies against the configured key; external trust policy "
            + "determines whether that key is trusted. This does not establish a person's identity, signing time, measurement truth, or legal suitability.";
    static final int MAX_REASONS = 200, MAX_REASON_CHARS = 512, MAX_MEMBER_CHECKS = 500;
    private static final ObjectMapper STRICT = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private EvidenceOffline() {}

    record Result(int exitCode, ObjectNode json) {}

    static Result verify(Path pack, EvidenceTrust trust, boolean allowUnsigned) { return verify(pack, trust, allowUnsigned, EvidenceArchive.Limits.DEFAULT); }

    static Result verify(Path pack, EvidenceTrust trust, boolean allowUnsigned, EvidenceArchive.Limits limits) {
        ObjectNode out = CasesRecords.object().put("verifier", EvidenceVerifier.VERSION);
        ObjectNode packInfo = out.putObject("pack");
        // A fixed private snapshot of at most the compressed bound, hashed while copying; the original is read once.
        Path snapshot = null;
        try {
            try { packInfo.put("bytes", Files.size(pack)); } catch (Exception e) { packInfo.putNull("bytes"); }
            Snapshot s = snapshot(pack, limits.compressedBytes());
            if (s == null) {
                packInfo.putNull("sha256");
                out.putNull("schema");
                out.putObject("archive_check").put("status", "REFUSED").put("code", "PACK_TOO_LARGE");
                out.putNull("axes"); out.putNull("signature_check"); out.putArray("member_checks");
                out.putArray("reasons").add("Archive refused (PACK_TOO_LARGE): the pack file is larger than " + limits.compressedBytes() + " bytes; it was not hashed.");
                return finish(out, 3);
            }
            snapshot = s.file();
            packInfo.put("sha256", s.sha256());
            return verifySnapshot(snapshot, trust, allowUnsigned, limits, out);
        } catch (java.io.IOException e) {
            packInfo.putNull("sha256");
            out.putNull("schema");
            out.putObject("archive_check").put("status", "REFUSED").put("code", "PACK_UNREADABLE");
            out.putNull("axes"); out.putNull("signature_check"); out.putArray("member_checks");
            out.putArray("reasons").add("Archive refused (PACK_UNREADABLE): the pack file cannot be read.");
            return finish(out, 3);
        } finally {
            EvidenceArchive.delete(snapshot);
        }
    }

    record Snapshot(Path file, String sha256) {}

    /** Copies at most {@code max} bytes into an owner-only temporary file, hashing while copying; null if larger. */
    static Snapshot snapshot(Path pack, long max) throws java.io.IOException {
        Path tmp = Files.createTempFile("fp-verify-", ".zip");
        boolean keep = false;
        try {
            try { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* non-POSIX file system */ }
            MessageDigest d;
            try { d = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
            long total = 0;
            try (InputStream in = Files.newInputStream(pack); var out = Files.newOutputStream(tmp)) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > max) return null;
                    d.update(b, 0, n); out.write(b, 0, n);
                }
            }
            keep = true;
            return new Snapshot(tmp, java.util.HexFormat.of().formatHex(d.digest()));
        } finally {
            if (!keep) EvidenceArchive.delete(tmp);
        }
    }

    private static Result verifySnapshot(Path pack, EvidenceTrust trust, boolean allowUnsigned, EvidenceArchive.Limits limits, ObjectNode out) {
        out.putNull("schema");
        ObjectNode archive = out.putObject("archive_check");
        EvidenceArchive.Pack p;
        String refusal = null, refusalText = null;
        JsonNode manifest = null;
        try {
            p = EvidenceArchive.read(pack, limits);
            archive.put("payload_entries", p.members().size()).put("metadata_entries", p.signature() == null ? 1 : 2).put("expanded_bytes", p.expandedBytes());
            try { manifest = STRICT.readTree(EvidenceJson.utf8(p.manifest())); } catch (Exception ignored) { manifest = null; }
            if (manifest != null && manifest.isObject()) {
                if (manifest.path("schema").isTextual()) out.put("schema", manifest.path("schema").textValue());
                Set<String> declared = new LinkedHashSet<>();
                boolean listed = manifest.path("members").isArray();
                for (JsonNode m : manifest.path("members")) if (m.path("path").isTextual()) declared.add(m.path("path").textValue());
                if (listed) {
                    Set<String> extra = new HashSet<>(p.members().keySet()); extra.removeAll(declared);
                    Set<String> missing = new HashSet<>(declared); missing.removeAll(p.members().keySet());
                    if (!extra.isEmpty()) { refusal = "UNDECLARED_MEMBER"; refusalText = "The pack holds a payload entry its manifest does not list."; }
                    else if (!missing.isEmpty()) { refusal = "MISSING_MEMBER"; refusalText = "The manifest lists a member the pack does not hold."; }
                }
                boolean declaresSigning = EvidenceVault.SCHEMA_V2.equals(manifest.path("schema").asText(null)) && manifest.path("signing").isObject();
                if (refusal == null && p.signature() != null && !declaresSigning) {
                    refusal = "UNEXPECTED_SIGNATURE_ENVELOPE"; refusalText = "The pack carries signature.json but its manifest declares no signing.";
                }
            }
        } catch (EvidenceArchive.Refusal r) {
            p = null; refusal = r.code; refusalText = r.getMessage();
        }
        if (refusal != null) {
            archive.put("status", "REFUSED").put("code", refusal);
            out.putNull("axes"); out.putNull("signature_check"); out.putArray("member_checks");
            out.putArray("reasons").add("Archive refused (" + refusal + "): " + refusalText);
            return finish(out, 3);
        }
        archive.put("status", "PASS").putNull("code");

        // Synthesize the expectations from the pack itself and verify through the shared core.
        Map<String, byte[]> bySha = new HashMap<>();
        ObjectNode e = CasesRecords.object();
        String manifestSha = EvidenceSigning.sha256(p.manifest());
        bySha.put(manifestSha, p.manifest());
        if (manifest != null && manifest.path("case_id").isTextual()) e.put("case_id", manifest.path("case_id").textValue());
        if (manifest != null && manifest.path("case_revision").isIntegralNumber()) e.set("case_revision", manifest.path("case_revision"));
        if (manifest != null && manifest.path("schema").isTextual()) e.put("schema", manifest.path("schema").textValue());
        e.put("manifest_sha256", manifestSha).put("manifest_length", p.manifest().length);
        e.putObject("manifest").put("path", EvidenceArchive.MANIFEST).put("length", p.manifest().length).put("sha256", manifestSha);
        ArrayNode members = e.putArray("members");
        for (var m : p.members().entrySet()) {
            String sha = EvidenceSigning.sha256(m.getValue());
            bySha.put(sha, m.getValue());
            members.addObject().put("path", m.getKey()).put("length", m.getValue().length).put("sha256", sha);
        }
        if (p.signature() != null) {
            String sha = EvidenceSigning.sha256(p.signature());
            bySha.put(sha, p.signature());
            e.putObject("signature").put("path", EvidenceArchive.SIGNATURE).put("length", p.signature().length).put("sha256", sha);
        } else e.putNull("signature");
        EvidenceVerifier.Lookup lookup = sha -> bySha.containsKey(sha) ? new EvidenceVerifier.Read(bySha.get(sha), null) : new EvidenceVerifier.Read(null, "MISSING");
        StoredRecord export = new StoredRecord("export", "00000000-0000-4000-8000-000000000000", 1, null, "", "offline", e);
        ObjectNode report = new EvidenceVerifier(lookup, trust, null).verify(export);

        out.set("axes", report.path("axes"));
        JsonNode sig = report.path("signature_check");
        if (sig.isObject()) out.set("signature_check", sig);
        else out.putObject("signature_check").put("validity", "NOT_SIGNED").put("trust", "NOT_EVALUATED").put("trust_source", trust.source)
                .putNull("key_fingerprint_sha256").putNull("code").put("reason", "This pack format has no signature.");
        ArrayNode checks = out.putArray("member_checks");
        for (JsonNode c : report.path("member_checks")) {
            if (checks.size() >= MAX_MEMBER_CHECKS) break;
            checks.addObject().put("path", c.path("path").asText()).put("status", c.path("status").asText());
        }
        ArrayNode reasons = out.putArray("reasons");
        for (JsonNode r : report.path("reasons")) reasons.add(r.asText());
        String keyTrust = report.path("axes").path("key_trust").asText();
        if ("NOT_SIGNED".equals(keyTrust))
            reasons.add("Offline, an unsigned pack's manifest is checked only against itself: this cannot show that the manifest is the one originally sealed.");

        // Exit code: the highest-priority finding (2 > 3 > 4 > 5 > 6 > 7 > 8 > 0); every finding stays in the JSON.
        JsonNode axes = report.path("axes");
        String manifestStatus = report.path("manifest_check").path("status").asText();
        String sigCode = sig.path("code").asText("");
        int code = 0;
        if ("NOT_SIGNED".equals(keyTrust) && !allowUnsigned) { code = 8; reasons.add("Unsigned pack: exit 8 unless --allow-unsigned is given for an integrity-only check."); }
        if ("UNTRUSTED_KEY".equals(keyTrust)) code = 8;
        if (Set.of("SIGNATURE_INVALID", "UNVERIFIED").contains(keyTrust)) code = 7;
        if ("FAIL".equals(axes.path("lineage").asText())) code = 6;
        if ("FAIL".equals(axes.path("integrity").asText())) code = 5;
        if ("UNSUPPORTED_VERSION".equals(manifestStatus) || Set.of("UNSUPPORTED_SIGNATURE_VERSION", "UNSUPPORTED_ALGORITHM").contains(sigCode)) code = 4;
        return finish(out, code);
    }

    private static Result finish(ObjectNode out, int code) {
        ArrayNode reasons = (ArrayNode) out.path("reasons");
        ArrayNode bounded = CasesRecords.object().arrayNode();
        for (int i = 0; i < reasons.size() && i < MAX_REASONS; i++) {
            String r = reasons.get(i).asText();
            bounded.add(r.length() > MAX_REASON_CHARS ? r.substring(0, MAX_REASON_CHARS) : r);
        }
        out.set("reasons", bounded);
        out.put("result", code == 0 ? "PASS" : "FAIL").put("exit_code", code).put("scope_note", SCOPE_NOTE);
        return new Result(code, out);
    }

    static List<String> exitCodeTable() {
        return List.of("0 PASS", "1 internal error", "2 usage error", "3 archive refused", "4 unsupported version", "5 integrity FAIL",
                "6 lineage FAIL", "7 signature invalid or unverified", "8 untrusted signer, or unsigned without --allow-unsigned");
    }
}
