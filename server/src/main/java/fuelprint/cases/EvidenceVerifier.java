package fuelprint.cases;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.Ids;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.StoredRecord;

/**
 * Verifies an export from the bytes actually located by the export record, against the ORIGINAL expectations
 * of the stored manifest. Nothing is regenerated from the live latest records, and no blob is accepted merely
 * because it matches its own storage key.
 *
 * <p>The export record's {@code manifest_sha256} / {@code manifest_length} are the expectation for the manifest;
 * its {@code manifest.sha256} is the content-addressed location of the manifest bytes actually held. Each
 * member listed in the manifest is expected to have the manifest's length and SHA-256; the export's
 * {@code members[].sha256} says which stored bytes actually stand in for that member. They are equal for an
 * original export and may differ for a tamper copy, which is exactly what fails.
 *
 * <p>Seven axes are reported separately and never combined (AGREEMENT section 6 {@code verification_report}).
 * Integrity PASS says only that stored bytes match the stored manifest; the pack is unsigned.
 */
final class EvidenceVerifier {
    static final List<String> AXES = List.of("integrity", "key_trust", "lineage", "trusted_time",
            "custody_documentation", "measurement_qualification", "legal_suitability");
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static final String VERSION = "fp-evidence-verify/1";

    /** Located bytes by content hash: the server's blob store, or the members of an offline pack. No live records. */
    interface Lookup {
        /** The bytes, or a Read with an explicit failure code (MISSING, CORRUPT, BAD_LOCATOR, UNREADABLE). */
        Read read(String sha);
    }

    private final Lookup lookup;
    private final EvidenceTrust trust;
    private final String trustProblem;

    EvidenceVerifier(BlobStore blobs) { this(blobLookup(blobs), EvidenceTrust.NONE, null); }

    EvidenceVerifier(Lookup lookup, EvidenceTrust trust, String trustProblem) {
        this.lookup = lookup; this.trust = trust == null ? EvidenceTrust.NONE : trust; this.trustProblem = trustProblem;
    }

    /** One located read: the bytes, or the explicit reason there are none. */
    record Read(byte[] bytes, String failure) {}

    static Lookup blobLookup(BlobStore blobs) {
        return sha -> {
            try {
                if (sha == null || !EvidenceVault.SHA256.matcher(sha).matches()) return new Read(null, "BAD_LOCATOR");
                return new Read(blobs.get(sha), null);
            } catch (BlobStore.BlobMissingException e) {
                return new Read(null, "MISSING");
            } catch (BlobStore.BlobCorruptedException e) {
                return new Read(null, "CORRUPT");
            } catch (IllegalArgumentException e) {
                return new Read(null, "BAD_LOCATOR");
            } catch (UncheckedIOException e) {
                return new Read(null, "UNREADABLE");
            }
        };
    }

    private Read read(String sha) {
        if (sha == null || !EvidenceVault.SHA256.matcher(sha).matches()) return new Read(null, "BAD_LOCATOR");
        Read r = lookup.read(sha);
        return r == null ? new Read(null, "MISSING") : r;
    }

    /** The verification_report's own fields, without {@code export_id}, {@code export_ref} and {@code verified_by}. */
    ObjectNode verify(StoredRecord export) {
        JsonNode e = export.body();
        List<String> reasons = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        ArrayNode checks = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        ObjectNode manifestCheck = CasesRecords.object();
        boolean integrity = true;

        // ---- manifest bytes against the stored expectation ----
        String expectedSha = CasesRecords.text(e, "manifest_sha256");
        JsonNode expectedLen = e.path("manifest_length");
        JsonNode locator = e.path("manifest");
        String locatorSha = locator.isObject() ? CasesRecords.text(locator, "sha256") : null;
        manifestCheck.put("expected_sha256", expectedSha);
        manifestCheck.set("expected_length", expectedLen.isIntegralNumber() ? expectedLen : com.fasterxml.jackson.databind.node.NullNode.getInstance());
        manifestCheck.put("locator_sha256", locatorSha);
        JsonNode manifest = null;
        String status;
        String canonicalProblem = null;
        byte[] manifestBytes = null;
        if (expectedSha == null || !EvidenceVault.SHA256.matcher(expectedSha).matches() || !nonnegativeLong(expectedLen)) {
            status = "EXPORT_RECORD_MALFORMED";
            manifestCheck.putNull("actual_sha256").putNull("actual_length");
        } else {
            Read manifestRead = read(locatorSha);
            if (manifestRead.bytes() == null) {
                status = manifestRead.failure();
                manifestCheck.putNull("actual_sha256").putNull("actual_length");
            } else {
                byte[] actual = manifestRead.bytes();
                manifestBytes = actual;
                String actualSha = EvidenceSigning.sha256(actual);
                manifestCheck.put("actual_sha256", actualSha).put("actual_length", actual.length);
                if (actual.length != expectedLen.longValue()) status = "LENGTH_MISMATCH";
                else if (!actualSha.equals(expectedSha)) status = "HASH_MISMATCH";
                else {
                    status = "PASS";
                    try { EvidenceJson.check(actual); manifest = STRICT.readTree(EvidenceJson.utf8(actual)); }
                    catch (Exception parse) { manifest = null; status = "MANIFEST_UNPARSEABLE"; }
                    if (manifest != null) {
                        String schema = manifest.path("schema").isTextual() ? manifest.path("schema").textValue() : null;
                        if (EvidenceVault.SCHEMA_V2.equals(schema)) {
                            // Version 2 manifests must be exactly in the restricted canonical form; never reinterpreted as v1.
                            try { manifest = JcsManifest.parseCanonical(actual); }
                            catch (JcsManifest.Unsupported u) { manifest = null; status = "MANIFEST_INVALID"; canonicalProblem = u.code; }
                        } else if (!EvidenceVault.SCHEMA.equals(schema)) {
                            manifest = null; status = "UNSUPPORTED_VERSION";
                        }
                    }
                }
            }
        }
        String manifestProblem = status.equals("PASS") ? validateManifest(manifest, e)
                : canonicalProblem != null ? "not in the " + JcsManifest.PROFILE + " canonical form (" + canonicalProblem + ")" : null;
        if (manifestProblem != null) { status = "MANIFEST_INVALID"; manifest = null; }
        manifestCheck.put("status", status);
        if (!status.equals("PASS")) {
            integrity = false;
            reasons.add("The manifest bytes did not match the stored manifest expectation (" + status
                    + (manifestProblem == null ? "" : ": " + manifestProblem) + "). The member inventory cannot be trusted.");
            failed.add(EvidenceVault.MANIFEST_PATH);
        }

        // ---- members: the actual located bytes against the ORIGINAL manifest inventory ----
        Map<String, JsonNode> located = new LinkedHashMap<>();
        boolean allReadable = true;
        Map<String, JsonNode> bodies = new LinkedHashMap<>();
        if (manifest != null) {
            for (JsonNode m : e.path("members")) {
                if (m.isObject() && m.path("path").isTextual()) located.putIfAbsent(m.path("path").textValue(), m);
            }
            if (!e.path("members").isArray() || located.size() != e.path("members").size()) {
                integrity = false;
                reasons.add("The export's member locations are malformed or repeat a path.");
            }
            Set<String> expectedPaths = new HashSet<>();
            for (JsonNode expected : manifest.path("members")) {
                String path = expected.path("path").textValue();
                expectedPaths.add(path);
                ObjectNode check = checks.addObject().put("path", path);
                check.put("expected_length", expected.path("length").longValue()).put("expected_sha256", expected.path("sha256").textValue());
                JsonNode loc = located.get(path);
                String memberStatus;
                if (loc == null) {
                    memberStatus = "NOT_LOCATED";
                    check.putNull("locator_sha256").putNull("actual_length").putNull("actual_sha256");
                } else {
                    check.put("locator_sha256", CasesRecords.text(loc, "sha256"));
                    Read r = read(CasesRecords.text(loc, "sha256"));
                    if (r.bytes() == null) {
                        memberStatus = r.failure();
                        check.putNull("actual_length").putNull("actual_sha256");
                    } else {
                        String sha = EvidenceSigning.sha256(r.bytes());
                        check.put("actual_length", r.bytes().length).put("actual_sha256", sha);
                        if (r.bytes().length != expected.path("length").longValue()) memberStatus = "LENGTH_MISMATCH";
                        else if (!sha.equals(expected.path("sha256").textValue())) memberStatus = "HASH_MISMATCH";
                        else {
                            memberStatus = "PASS";
                            // Strict UTF-8 and the archive JSON rules; the exact bytes and their hash are unchanged.
                            try {
                                EvidenceJson.check(r.bytes());
                                JsonNode body = STRICT.readTree(EvidenceJson.utf8(r.bytes()));
                                if (body == null || !body.isObject()) memberStatus = "MEMBER_UNPARSEABLE";
                                else bodies.put(path, body);
                            }
                            catch (Exception parse) { memberStatus = "MEMBER_UNPARSEABLE"; }
                        }
                    }
                }
                check.put("status", memberStatus);
                if (!memberStatus.equals("PASS")) {
                    integrity = false; allReadable = false;
                    failed.add(path);
                    reasons.add("Member " + path + " failed: " + memberStatus + ".");
                }
            }
            for (String path : located.keySet()) {
                if (expectedPaths.contains(path)) continue;
                integrity = false; allReadable = false;
                checks.addObject().put("path", path).put("status", "UNLISTED").putNull("expected_length").putNull("expected_sha256")
                        .put("locator_sha256", CasesRecords.text(located.get(path), "sha256")).putNull("actual_length").putNull("actual_sha256");
                failed.add(path);
                reasons.add("Member " + path + " is held by the export but is not in its stored manifest.");
            }
        } else {
            allReadable = false;
            for (JsonNode m : e.path("members")) {
                if (!m.isObject() || !m.path("path").isTextual()) continue;
                checks.addObject().put("path", m.path("path").textValue()).put("status", "UNVERIFIED")
                        .putNull("expected_length").putNull("expected_sha256").putNull("actual_length").putNull("actual_sha256");
            }
        }

        // ---- axes ----
        ObjectNode axes = CasesRecords.object();
        axes.put("integrity", integrity ? "PASS" : "FAIL");
        String exportSchema = CasesRecords.text(e, "schema");
        boolean v2 = EvidenceVault.SCHEMA_V2.equals(exportSchema) || (manifest != null && EvidenceVault.SCHEMA_V2.equals(CasesRecords.text(manifest, "schema")));
        JsonNode sigLocator = e.path("signature");
        boolean signatureRelevant = v2 || (sigLocator.isObject());
        ObjectNode signatureCheck = signatureRelevant ? signature(e, manifest, manifestBytes, v2, reasons) : null;
        axes.put("key_trust", signatureCheck == null ? "NOT_SIGNED" : signatureCheck.path("key_trust").asText());
        if (signatureCheck != null) signatureCheck.remove("key_trust");
        if (integrity) reasons.add(0, signatureCheck != null && "VALID".equals(signatureCheck.path("validity").asText())
                ? "Every stored byte matches the stored manifest, and the manifest signature verifies against the key it names; whether that key is trusted is reported separately under key_trust. This does not establish a person's identity, signing time, measurement truth, or legal suitability."
                : signatureCheck != null && !"NOT_SIGNED".equals(signatureCheck.path("validity").asText())
                ? "Every stored byte matches the stored manifest, but its declared signature is invalid or could not be verified. This does not establish who made the bytes, when, or that the measurements are true."
                : "Every stored byte matches the stored manifest. The pack is unsigned, so this shows the bytes are unchanged since sealing, not who made them, when, or that the measurements are true.");
        String lineage;
        if (manifest == null || !allReadable) {
            lineage = "UNVERIFIED";
            reasons.add("Lineage could not be checked because a member or the manifest failed integrity.");
        } else {
            List<String> problems = lineage(manifest, bodies);
            lineage = problems.isEmpty() ? "PASS" : "FAIL";
            problems.forEach(p -> reasons.add("Lineage: " + p));
        }
        axes.put("lineage", lineage);
        axes.put("trusted_time", "UNVERIFIED");
        axes.put("custody_documentation", custody(e, manifest, bodies, reasons));
        axes.put("measurement_qualification", "SYNTHETIC_SCOPE_ONLY");
        axes.put("legal_suitability", "NOT_ASSESSED");

        ObjectNode out = CasesRecords.object();
        out.set("axes", axes);
        ArrayNode r = out.putArray("reasons");
        reasons.forEach(r::add);
        ArrayNode f = out.putArray("failed_members");
        failed.forEach(f::add);
        out.set("manifest_check", manifestCheck);
        out.set("member_checks", checks);
        if (signatureCheck != null) {
            out.set("signature_check", signatureCheck);
            out.put("verifier_version", VERSION);
        }
        return out;
    }

    /**
     * Signature validity and key trust, kept apart. The signed message is the exact held manifest bytes. Signed intent
     * declared in the manifest, the envelope's algorithm/fingerprint/manifest hash, the embedded key's actual
     * fingerprint and the signature itself must all agree. An embedded key never makes itself trusted.
     */
    private ObjectNode signature(JsonNode e, JsonNode manifest, byte[] manifestBytes, boolean v2, List<String> reasons) {
        ObjectNode c = CasesRecords.object();
        c.put("validity", "UNVERIFIED").put("trust", "NOT_EVALUATED").put("trust_source", trust.source).putNull("key_fingerprint_sha256").putNull("code");
        JsonNode loc = e.path("signature");
        boolean hasLocator = loc.isObject();
        if (!v2) return sigResult(c, "SIGNATURE_INVALID", "INVALID", "UNEXPECTED_SIGNATURE_ENVELOPE",
                "A format 1 pack has no signature slot, but its export names a signature.", reasons);
        if (manifest == null) return sigResult(c, "UNVERIFIED", "UNVERIFIED", "MANIFEST_UNVERIFIED",
                "The signature could not be checked because the manifest failed integrity.", reasons);
        JsonNode decl = manifest.path("signing");
        if (decl.isNull()) {
            if (hasLocator) return sigResult(c, "SIGNATURE_INVALID", "INVALID", "UNEXPECTED_SIGNATURE_ENVELOPE",
                    "The manifest declares no signing but the export carries a signature envelope.", reasons);
            c.put("validity", "NOT_SIGNED");
            reasons.add("The manifest declares no signature (format 2, unsigned by policy); key_trust is NOT_SIGNED.");
            c.put("key_trust", "NOT_SIGNED");
            return c;
        }
        String declared = CasesRecords.text(decl, "key_fingerprint_sha256");
        c.put("key_fingerprint_sha256", declared);
        if (!hasLocator) return sigResult(c, "SIGNATURE_INVALID", "INVALID", "SIGNED_INTENT_ENVELOPE_MISSING",
                "The manifest declares a signature but no signature envelope is present.", reasons);
        Read r = read(CasesRecords.text(loc, "sha256"));
        if (r.bytes() == null) return sigResult(c, "UNVERIFIED", "UNVERIFIED", "ENVELOPE_" + r.failure(),
                "The signature envelope bytes could not be read (" + r.failure() + ").", reasons);
        byte[] env = r.bytes();
        if (!nonnegativeLong(loc.path("length")) || loc.path("length").longValue() != env.length)
            return sigResult(c, "SIGNATURE_INVALID", "INVALID", "ENVELOPE_LENGTH_MISMATCH", "The signature envelope length differs from its record.", reasons);
        JsonNode en;
        try { en = JcsManifest.parseCanonical(env, 16 * 1024); }
        catch (JcsManifest.Unsupported u) {
            return sigResult(c, "UNVERIFIED", "UNVERIFIED", "ENVELOPE_" + u.code, "The signature envelope is not in canonical form (" + u.code + ").", reasons);
        }
        Set<String> keys = new HashSet<>();
        en.fieldNames().forEachRemaining(keys::add);
        if (!EvidenceVault.SIGNATURE_SCHEMA.equals(CasesRecords.text(en, "schema")))
            return sigResult(c, "UNVERIFIED", "UNVERIFIED", "UNSUPPORTED_SIGNATURE_VERSION", "The signature envelope version is not supported.", reasons);
        if (!keys.equals(ENVELOPE_KEYS)) return sigResult(c, "UNVERIFIED", "UNVERIFIED", "ENVELOPE_MALFORMED", "The signature envelope has unexpected or missing fields.", reasons);
        if (!"Ed25519".equals(CasesRecords.text(en, "algorithm")) || !"Ed25519".equals(CasesRecords.text(decl, "algorithm")))
            return sigResult(c, "UNVERIFIED", "UNVERIFIED", "UNSUPPORTED_ALGORITHM", "Only Ed25519 signatures are supported.", reasons);
        if (!EvidenceSigning.sha256(manifestBytes).equals(CasesRecords.text(en, "manifest_sha256")))
            return sigResult(c, "SIGNATURE_INVALID", "INVALID", "ENVELOPE_MANIFEST_MISMATCH", "The signature envelope names a different manifest.", reasons);
        byte[] spki, sig;
        try {
            spki = java.util.Base64.getDecoder().decode(CasesRecords.text(en, "public_key_spki_b64"));
            sig = java.util.Base64.getDecoder().decode(CasesRecords.text(en, "signature_b64"));
        } catch (RuntimeException bad) {
            return sigResult(c, "SIGNATURE_INVALID", "INVALID", "ENVELOPE_ENCODING", "The signature envelope's key or signature is not valid base64.", reasons);
        }
        String actual = EvidenceSigning.sha256(spki);
        c.put("key_fingerprint_sha256", actual);
        if (!actual.equals(CasesRecords.text(en, "key_fingerprint_sha256")) || !actual.equals(declared)
                || (loc.has("key_fingerprint_sha256") && !actual.equals(CasesRecords.text(loc, "key_fingerprint_sha256"))))
            return sigResult(c, "SIGNATURE_INVALID", "INVALID", "KEY_FINGERPRINT_MISMATCH",
                    "The embedded key's fingerprint differs from the signer the manifest declares.", reasons);
        java.security.PublicKey key;
        try { key = EvidenceSigning.publicKey(spki); }
        catch (EvidenceSigning.KeyProblem p) {
            return sigResult(c, "UNVERIFIED", "UNVERIFIED", p.code.startsWith("UNSUPPORTED") ? "UNSUPPORTED_ALGORITHM" : "KEY_INVALID",
                    "The embedded public key is not a usable Ed25519 key.", reasons);
        }
        if (!EvidenceSigning.verify(manifestBytes, sig, key))
            return sigResult(c, "SIGNATURE_INVALID", "INVALID", "SIGNATURE_MISMATCH", "The signature does not verify over the exact manifest bytes.", reasons);
        c.put("validity", "VALID");
        if (trust.trusts(actual)) {
            c.put("trust", "TRUSTED").put("key_trust", "TRUSTED");
            reasons.add("The signature is valid and its key fingerprint " + actual.substring(0, 12) + " is in the externally supplied trust anchors.");
        } else {
            c.put("trust", "UNTRUSTED").put("key_trust", "UNTRUSTED_KEY");
            reasons.add("The signature is valid, but key fingerprint " + actual.substring(0, 12) + " is not in the externally supplied trust anchors"
                    + (trustProblem == null ? "" : " (trust anchors unavailable: " + trustProblem + ")") + "; a key carried inside the pack is never trusted by itself.");
        }
        return c;
    }

    private static final Set<String> ENVELOPE_KEYS = Set.of("schema", "algorithm", "manifest_sha256", "public_key_spki_b64",
            "key_fingerprint_sha256", "signature_b64");

    private static ObjectNode sigResult(ObjectNode c, String keyTrust, String validity, String code, String reason, List<String> reasons) {
        c.put("validity", validity).put("code", code).put("key_trust", keyTrust).put("reason", reason);
        reasons.add("Signature: " + reason);
        return c;
    }

    /** Structure of the stored manifest against the export record. Null when it is acceptable. */
    private static String validateManifest(JsonNode m, JsonNode export) {
        if (m == null || !m.isObject()) return "not a JSON object";
        Set<String> keys = new HashSet<>();
        m.fieldNames().forEachRemaining(keys::add);
        String schema = CasesRecords.text(m, "schema"), exportSchema = CasesRecords.text(export, "schema");
        if (EvidenceVault.SCHEMA.equals(schema)) {
            if (!keys.equals(V1_KEYS)) return "unexpected or missing fields";
            if (exportSchema != null && !exportSchema.equals(schema)) return "the export record names a different schema";
        } else if (EvidenceVault.SCHEMA_V2.equals(schema)) {
            if (!keys.equals(V2_KEYS)) return "unexpected or missing fields";
            if (!EvidenceVault.SCHEMA_V2.equals(exportSchema)) return "the export record does not name " + EvidenceVault.SCHEMA_V2;
            if (!JcsManifest.PROFILE.equals(CasesRecords.text(m, "canonicalization"))) return "canonicalization is not " + JcsManifest.PROFILE;
            if (!EvidenceVault.MEMBER_ENCODING.equals(CasesRecords.text(m, "member_encoding"))) return "member_encoding is not " + EvidenceVault.MEMBER_ENCODING;
            String signing = signingProblem(m.path("signing"));
            if (signing != null) return signing;
        } else {
            return "schema is not supported";
        }
        if (!Ids.isUuid(CasesRecords.text(m, "case_id")) || !CasesRecords.text(m, "case_id").equals(CasesRecords.text(export, "case_id")))
            return "case_id differs from the export";
        if (!positiveLong(m.path("case_revision")) || !positiveLong(export.path("case_revision"))
                || m.path("case_revision").longValue() != export.path("case_revision").longValue())
            return "case_revision differs from the export";
        if (!m.path("created_utc").isTextual()) return "created_utc missing";
        if (!m.path("limitations").isArray()) return "limitations missing";
        if (!m.path("members").isArray() || m.path("members").isEmpty()) return "no members";
        Set<String> seen = new HashSet<>(), folded = new HashSet<>();
        for (JsonNode member : m.path("members")) {
            if (!member.isObject() || member.size() != 3) return "a member is malformed";
            String path = CasesRecords.text(member, "path"), sha = CasesRecords.text(member, "sha256");
            if (!EvidenceVault.safePath(path)) return "an unsafe member path";
            if (!seen.add(path) || !folded.add(path.toLowerCase(java.util.Locale.ROOT))) return "a repeated or colliding member path";
            if (sha == null || !EvidenceVault.SHA256.matcher(sha).matches()) return "a member hash is malformed";
            if (!nonnegativeLong(member.path("length"))) return "a member length is malformed";
        }
        return null;
    }

    static final Set<String> V1_KEYS = Set.of("schema", "case_id", "case_revision", "created_utc", "members", "limitations");
    static final Set<String> V2_KEYS = Set.of("schema", "canonicalization", "member_encoding", "case_id", "case_revision",
            "created_utc", "members", "limitations", "signing");

    /** The v2 signing declaration: null (unsigned) or exactly the signed-intent object. */
    static String signingProblem(JsonNode s) {
        if (s.isNull()) return null;
        if (!s.isObject() || s.size() != 3 || !"SIGN_REQUIRED".equals(CasesRecords.text(s, "policy"))
                || !"Ed25519".equals(CasesRecords.text(s, "algorithm"))
                || CasesRecords.text(s, "key_fingerprint_sha256") == null
                || !EvidenceVault.SHA256.matcher(CasesRecords.text(s, "key_fingerprint_sha256")).matches())
            return "the signing declaration is malformed or names an unsupported algorithm";
        return null;
    }

    /** Lineage from the verified member bytes only. */
    private static List<String> lineage(JsonNode manifest, Map<String, JsonNode> bodies) {
        List<String> problems = new ArrayList<>();
        String caseId = manifest.path("case_id").textValue();
        long caseRevision = manifest.path("case_revision").longValue();
        String casePath = EvidenceVault.path("case", caseId, caseRevision);
        JsonNode caseBody = bodies.get(casePath);
        if (caseBody == null) { problems.add("the sealed case revision is not a member"); return problems; }
        String run = caseBody.path("run_id").isTextual() ? caseBody.path("run_id").textValue() : null;
        for (Map.Entry<String, JsonNode> entry : bodies.entrySet()) {
            Matcher p = EvidenceVault.MEMBER_PATH.matcher(entry.getKey());
            if (!p.matches()) { problems.add(entry.getKey() + " is not a safe member path"); continue; }
            String type = p.group(1);
            JsonNode b = entry.getValue();
            if (!EvidenceVault.ALLOWED.contains(type)) problems.add(entry.getKey() + " is a record type that may not be in a pack");
            if (!p.group(2).equals(b.path("id").asText()) || !positiveLong(b.path("revision"))
                    || Long.parseLong(p.group(3)) != b.path("revision").longValue())
                problems.add(entry.getKey() + " does not match the id and revision inside it");
            if (run == null ? !b.path("run_id").isNull() : !run.equals(b.path("run_id").asText(null)))
                problems.add(entry.getKey() + " belongs to another run");
            if (type.equals("specimen") && !caseId.equals(b.path("case_id").asText())) problems.add(entry.getKey() + " belongs to another case");
            if (type.equals("lab_result")) {
                for (String ref : List.of("specimen_ref", "case_ref", "custody_ref"))
                    if (!has(bodies, b.path(ref))) problems.add(entry.getKey() + " " + ref + " is not a member");
            }
            semantic(entry.getKey(), type, b, caseId, bodies, problems);
        }
        JsonNode findingRef = caseBody.path("finding_ref");
        JsonNode finding = has(bodies, findingRef) ? bodies.get(pathOf(findingRef)) : null;
        if (finding == null) { problems.add("the case's finding revision is not a member"); return problems; }
        if (!caseId.equals(finding.path("case_id").asText())) problems.add("the finding belongs to another case");
        for (JsonNode cited : finding.path("cited_refs"))
            if (!has(bodies, cited)) problems.add("cited " + cited.path("type").asText("?") + " " + cited.path("id").asText("?") + " is not a member");
        for (JsonNode screening : caseBody.path("screening_refs"))
            if (!has(bodies, screening)) problems.add("a screening record of the case is not a member");
        return problems;
    }

    /**
     * Exact type/id/revision and same-case linkage among specimens, media, custody and lab members, from member bytes
     * only: existence of a referenced path is not enough.
     */
    private static void semantic(String path, String type, JsonNode b, String caseId, Map<String, JsonNode> bodies, List<String> problems) {
        switch (type) {
            case "lab_result" -> {
                if (!typed(b.path("specimen_ref"), "specimen") || !typed(b.path("case_ref"), "case") || !typed(b.path("custody_ref"), "custody_event")) {
                    problems.add(path + " has a reference of the wrong record type"); return;
                }
                if (!b.path("specimen_ref").path("id").asText().equals(b.path("specimen_id").asText())) problems.add(path + " names a different specimen than it records");
                if (!caseId.equals(b.path("case_ref").path("id").asText())) problems.add(path + " was made for another case");
                JsonNode custody = has(bodies, b.path("custody_ref")) ? bodies.get(pathOf(b.path("custody_ref"))) : null;
                if (custody != null && !b.path("specimen_id").asText().equals(custody.path("specimen_id").asText()))
                    problems.add(path + " cites a custody step of another specimen");
            }
            case "media" -> {
                if (!caseId.equals(b.path("case_id").asText())) problems.add(path + " belongs to another case");
                if (!AerosolMethod.MATERIAL.equals(b.path("material").asText()) || !AerosolMethod.ID.equals(b.path("method_id").asText()))
                    problems.add(path + " does not carry the declared aerosol material and method");
                JsonNode current = byId(bodies, "media_custody_event", b.path("current_media_custody_event_id").asText(null));
                if (current == null) problems.add(path + " current media custody event is not a member");
                else if (!b.path("id").asText().equals(current.path("media_id").asText()) || !current.path("to_holder").asText().equals(b.path("current_holder").asText()))
                    problems.add(path + " current media custody event belongs to other media or another holder");
            }
            case "media_custody_event" -> {
                JsonNode owner = byId(bodies, "media", b.path("media_id").asText(null));
                if (owner == null) problems.add(path + " belongs to media that is not a member");
                else if (!caseId.equals(owner.path("case_id").asText())) problems.add(path + " belongs to media of another case");
                String previous = b.path("previous_event_id").isTextual() ? b.path("previous_event_id").textValue() : null;
                if (previous != null) {
                    JsonNode prior = byId(bodies, "media_custody_event", previous);
                    if (prior == null || !prior.path("media_id").asText().equals(b.path("media_id").asText()))
                        problems.add(path + " previous custody event is not a member event of the same media");
                }
                // A matching previous-event identity does not establish a valid chain: a self-loop or
                // longer cycle can have perfectly matching hashes. Traverse saved members, never live rows.
                Set<String> visited = new HashSet<>();
                JsonNode step = b;
                while (step != null) {
                    String eventId = step.path("id").asText();
                    if (!visited.add(eventId)) {
                        problems.add(path + " media custody chain contains a cycle");
                        break;
                    }
                    String priorId = step.path("previous_event_id").isTextual()
                            ? step.path("previous_event_id").textValue() : null;
                    if (priorId == null) break;
                    step = byId(bodies, "media_custody_event", priorId);
                    if (step != null && !step.path("media_id").asText().equals(b.path("media_id").asText())) break;
                }
            }
            case "media_lab_result" -> {
                if (!typed(b.path("media_ref"), "media") || !typed(b.path("case_ref"), "case") || !typed(b.path("custody_ref"), "media_custody_event")) {
                    problems.add(path + " has a reference of the wrong record type"); return;
                }
                for (String ref : List.of("media_ref", "case_ref", "custody_ref"))
                    if (!has(bodies, b.path(ref))) problems.add(path + " " + ref + " is not a member");
                if (!b.path("media_ref").path("id").asText().equals(b.path("media_id").asText())) problems.add(path + " names different media than it records");
                if (!caseId.equals(b.path("case_ref").path("id").asText())) problems.add(path + " was made for another case");
                JsonNode media = has(bodies, b.path("media_ref")) ? bodies.get(pathOf(b.path("media_ref"))) : null;
                if (media != null) {
                    if (!caseId.equals(media.path("case_id").asText())) problems.add(path + " uses media of another case");
                    if (!media.path("method_id").asText().equals(b.path("method_id").asText())) problems.add(path + " method differs from its media's method");
                }
                JsonNode custody = has(bodies, b.path("custody_ref")) ? bodies.get(pathOf(b.path("custody_ref"))) : null;
                if (custody != null && !b.path("media_id").asText().equals(custody.path("media_id").asText()))
                    problems.add(path + " cites a custody step of other media");
            }
            default -> { }
        }
    }

    private static boolean typed(JsonNode ref, String type) { return ref.isObject() && type.equals(ref.path("type").asText()); }

    private static JsonNode byId(Map<String, JsonNode> bodies, String type, String id) {
        if (id == null) return null;
        String prefix = "payload/records/" + type + "/" + id + "-r";
        JsonNode found = null;
        long best = -1;
        for (var e : bodies.entrySet())
            if (e.getKey().startsWith(prefix)) {
                if (!positiveLong(e.getValue().path("revision"))) continue;
                long rev = e.getValue().path("revision").longValue();
                if (rev > best) { best = rev; found = e.getValue(); }
            }
        return found;
    }

    private static boolean has(Map<String, JsonNode> bodies, JsonNode ref) {
        return ref.isObject() && ref.size() == 3 && positiveLong(ref.path("revision"))
                && ref.path("id").isTextual() && Ids.isUuid(ref.path("id").textValue())
                && ref.path("type").isTextual() && bodies.containsKey(pathOf(ref));
    }

    /** Structural counters/identities are integral JSON values in range; measurement decimals are untouched. */
    private static boolean nonnegativeLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private static boolean positiveLong(JsonNode value) {
        return nonnegativeLong(value) && value.longValue() > 0;
    }

    private static String pathOf(JsonNode ref) {
        return EvidenceVault.path(ref.path("type").asText(), ref.path("id").asText(), ref.path("revision").asLong());
    }

    /**
     * Custody from the member bytes that passed integrity; if any custody-bearing member failed, the summary
     * recorded at sealing time stands (a failed integrity check never erases a recorded exception).
     */
    private static String custody(JsonNode export, JsonNode manifest, Map<String, JsonNode> bodies, List<String> reasons) {
        JsonNode summary = export.path("custody_summary");
        String sealed = summary.isObject() ? CasesRecords.text(summary, "status") : null;
        if (sealed != null && !Set.of("PASS", "LIMITED", "NOT_APPLICABLE").contains(sealed)) sealed = null;
        boolean bearingFailed = false;
        List<StoredRecord> specimens = new ArrayList<>(), events = new ArrayList<>();
        Set<String> bearingPaths = new HashSet<>();
        if (manifest != null) {
            for (JsonNode m : manifest.path("members")) {
                Matcher p = EvidenceVault.MEMBER_PATH.matcher(m.path("path").textValue());
                if (!p.matches() || !Set.of("specimen", "custody_event", "media", "media_custody_event").contains(p.group(1))) continue;
                bearingPaths.add(m.path("path").textValue());
                JsonNode b = bodies.get(m.path("path").textValue());
                if (b == null) { bearingFailed = true; continue; }
                var record = new StoredRecord(p.group(1), p.group(2), Long.parseLong(p.group(3)), null, "", "", (ObjectNode) b);
                (p.group(1).equals("specimen") || p.group(1).equals("media") ? specimens : events).add(record);
            }
        } else {
            bearingFailed = true;
        }
        if (bearingFailed) {
            if (sealed == null) {
                reasons.add("Custody documentation is UNVERIFIED: custody members failed integrity and no sealed summary is available.");
                return "UNVERIFIED";
            }
            reasons.add("Custody documentation is taken from the summary recorded at sealing because a custody member or the manifest failed integrity: " + sealed + ".");
            return sealed;
        }
        String fromBytes = EvidenceVault.summarizeCustody(specimens, events).path("status").asText();
        if (sealed != null && !sealed.equals(fromBytes)) {
            reasons.add("The custody summary recorded at sealing (" + sealed + ") differs from the sealed custody bytes (" + fromBytes + "); the more cautious is reported.");
            return rank(sealed) >= rank(fromBytes) ? sealed : fromBytes;
        }
        if ("LIMITED".equals(fromBytes)) reasons.add("Custody documentation is LIMITED: the sealed custody records contain an exception or a seal that was not intact.");
        if ("NOT_APPLICABLE".equals(fromBytes)) reasons.add("No specimen custody is in this pack (air screening only); custody documentation is NOT_APPLICABLE, not PASS.");
        return fromBytes;
    }

    private static int rank(String status) {
        return switch (status) { case "LIMITED" -> 2; case "NOT_APPLICABLE" -> 1; default -> 0; };
    }
}
