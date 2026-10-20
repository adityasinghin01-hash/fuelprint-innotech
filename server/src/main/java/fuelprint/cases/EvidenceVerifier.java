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

    private final BlobStore blobs;

    EvidenceVerifier(BlobStore blobs) { this.blobs = blobs; }

    /** One located read: the bytes, or the explicit reason there are none. */
    private record Read(byte[] bytes, String failure) {}

    private Read read(String sha) {
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
        if (expectedSha == null || !EvidenceVault.SHA256.matcher(expectedSha).matches() || !expectedLen.isIntegralNumber() || expectedLen.longValue() < 0) {
            status = "EXPORT_RECORD_MALFORMED";
            manifestCheck.putNull("actual_sha256").putNull("actual_length");
        } else {
            Read manifestRead = read(locatorSha);
            if (manifestRead.bytes() == null) {
                status = manifestRead.failure();
                manifestCheck.putNull("actual_sha256").putNull("actual_length");
            } else {
                byte[] actual = manifestRead.bytes();
                String actualSha = BlobStore.sha256(actual);
                manifestCheck.put("actual_sha256", actualSha).put("actual_length", actual.length);
                if (actual.length != expectedLen.longValue()) status = "LENGTH_MISMATCH";
                else if (!actualSha.equals(expectedSha)) status = "HASH_MISMATCH";
                else {
                    status = "PASS";
                    try { manifest = STRICT.readTree(new String(actual, StandardCharsets.UTF_8)); }
                    catch (Exception parse) { status = "MANIFEST_UNPARSEABLE"; }
                }
            }
        }
        String manifestProblem = status.equals("PASS") ? validateManifest(manifest, e) : null;
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
                        String sha = BlobStore.sha256(r.bytes());
                        check.put("actual_length", r.bytes().length).put("actual_sha256", sha);
                        if (r.bytes().length != expected.path("length").longValue()) memberStatus = "LENGTH_MISMATCH";
                        else if (!sha.equals(expected.path("sha256").textValue())) memberStatus = "HASH_MISMATCH";
                        else {
                            memberStatus = "PASS";
                            try { bodies.put(path, STRICT.readTree(new String(r.bytes(), StandardCharsets.UTF_8))); }
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
        axes.put("key_trust", "NOT_SIGNED");
        if (integrity) reasons.add(0, "Every stored byte matches the stored manifest. The pack is unsigned, so this shows the bytes are unchanged since sealing, not who made them, when, or that the measurements are true.");
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
        return out;
    }

    /** Structure of the stored manifest against the export record. Null when it is acceptable. */
    private static String validateManifest(JsonNode m, JsonNode export) {
        if (m == null || !m.isObject()) return "not a JSON object";
        Set<String> keys = new HashSet<>();
        m.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of("schema", "case_id", "case_revision", "created_utc", "members", "limitations")))
            return "unexpected or missing fields";
        if (!EvidenceVault.SCHEMA.equals(CasesRecords.text(m, "schema"))) return "schema is not " + EvidenceVault.SCHEMA;
        if (!Ids.isUuid(CasesRecords.text(m, "case_id")) || !CasesRecords.text(m, "case_id").equals(CasesRecords.text(export, "case_id")))
            return "case_id differs from the export";
        if (!m.path("case_revision").isIntegralNumber() || m.path("case_revision").longValue() != export.path("case_revision").longValue())
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
            if (!member.path("length").isIntegralNumber() || member.path("length").longValue() < 0) return "a member length is malformed";
        }
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
            if (!p.group(2).equals(b.path("id").asText()) || Long.parseLong(p.group(3)) != b.path("revision").asLong(-1))
                problems.add(entry.getKey() + " does not match the id and revision inside it");
            if (run == null ? !b.path("run_id").isNull() : !run.equals(b.path("run_id").asText(null)))
                problems.add(entry.getKey() + " belongs to another run");
            if (type.equals("specimen") && !caseId.equals(b.path("case_id").asText())) problems.add(entry.getKey() + " belongs to another case");
            if (type.equals("lab_result")) {
                for (String ref : List.of("specimen_ref", "case_ref", "custody_ref"))
                    if (!has(bodies, b.path(ref))) problems.add(entry.getKey() + " " + ref + " is not a member");
            }
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

    private static boolean has(Map<String, JsonNode> bodies, JsonNode ref) {
        return ref.isObject() && ref.path("revision").canConvertToLong() && ref.path("id").isTextual()
                && ref.path("type").isTextual() && bodies.containsKey(pathOf(ref));
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
                if (!p.matches() || !Set.of("specimen", "custody_event").contains(p.group(1))) continue;
                bearingPaths.add(m.path("path").textValue());
                JsonNode b = bodies.get(m.path("path").textValue());
                if (b == null) { bearingFailed = true; continue; }
                var record = new StoredRecord(p.group(1), p.group(2), Long.parseLong(p.group(3)), null, "", "", (ObjectNode) b);
                (p.group(1).equals("specimen") ? specimens : events).add(record);
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
