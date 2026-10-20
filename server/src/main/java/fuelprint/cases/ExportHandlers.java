package fuelprint.cases;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.ClockControl;
import fuelprint.core.Ids;
import fuelprint.core.WallTime;
import fuelprint.core.command.*;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * seal_export, verify_export and tamper_copy (AGREEMENT section 5). All writes are ordinary command results, so the
 * bus saves the records and their events in one transaction or not at all. Blobs are content addressed and are
 * written before the records that name them; a blob left by a rolled-back command is harmless.
 *
 * <p>Unsigned integrity is the only thing sealed or verified here. Nothing in these records claims who made the
 * evidence, when, that a measurement is true, or legal suitability.
 */
final class ExportHandlers {
    private ExportHandlers() {}

    static final String SCOPE_NOTE = "Unsigned integrity check of stored bytes only. It does not show who made the pack, "
            + "when, whether any measurement is true, or legal suitability.";
    static final String SIGNED_SCOPE_NOTE = "Integrity check of stored bytes and an Ed25519 signature over the exact manifest bytes. A valid signature "
            + "verifies against the named key; external trust policy decides whether that key is trusted. It does not establish a person's identity, "
            + "signing time, measurement truth, or legal suitability.";

    /** Text appended to an automatic step's refusal so a replayed event can recognise it already ran. */
    static String marker(JsonNode cause) {
        return "[cause=" + cause.path("type").asText() + "/" + cause.path("id").asText() + "/r" + cause.path("revision").asText() + "]";
    }

    private static Rejection marked(Rejection r, JsonNode payload) {
        JsonNode cause = payload.path("cause");
        if (!cause.isObject()) return r;
        return new Rejection(r.reasonCode(), r.reasonText() + " " + marker(cause));
    }

    /** Same type, id and revision number, whatever numeric node type carried the revision. */
    private static boolean sameRef(JsonNode a, JsonNode b) {
        return a.isObject() && b.isObject() && a.size() == 3 && b.size() == 3
                && a.path("type").isTextual() && a.path("type").equals(b.path("type")) && a.path("id").isTextual() && a.path("id").equals(b.path("id"))
                && a.path("revision").isIntegralNumber() && b.path("revision").isIntegralNumber() && a.path("revision").longValue() == b.path("revision").longValue();
    }

    private static void onlyKeys(JsonNode payload, Set<String> allowed) {
        CasesRecords.require(payload.isObject(), "The command payload must be an object.");
        var names = payload.fieldNames();
        while (names.hasNext()) CasesRecords.require(allowed.contains(names.next()), "The command payload has an unsupported field.");
    }

    private static boolean autoSealOff(Session session, ObjectProvider<AutomationSettings> settings) {
        if (!session.isAutomation()) return false;
        AutomationSettings current = settings.getIfAvailable();
        return current == null || !current.isOn(AutomationSettings.AUTO_SEAL);
    }

    private static byte[] located(BlobStore blobs, String sha) {
        try {
            return blobs.get(sha);
        } catch (BlobStore.BlobMissingException | BlobStore.BlobCorruptedException | IllegalArgumentException | UncheckedIOException e) {
            return null;
        }
    }

    @Component @Profile("!stub")
    static final class SealExport extends CasesHandlers.Handler {
        private final BlobStore blobs;
        private final Clock wall;
        private final ObjectProvider<AutomationSettings> settings;
        private final EvidenceConfig config;
        SealExport(ObjectProvider<ClockControl> clock, BlobStore blobs, Clock wall, ObjectProvider<AutomationSettings> settings, EvidenceConfig config) {
            super(clock); this.blobs = blobs; this.wall = wall; this.settings = settings; this.config = config;
        }
        public String commandType() { return "seal_export"; }
        public Set<Role> allowedRoles() { return Set.of(Role.EXPORTER, Role.AUTOMATION); }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "case", "case_id"); }

        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            try {
                if (autoSealOff(session, settings)) return Rejection.notAllowed("Automatic sealing is switched off (auto_seal).");
                onlyKeys(p, Set.of("case_id", "case_revision", "cause"));
                String caseId = CasesRecords.text(p, "case_id");
                CasesRecords.require(Ids.isUuid(caseId), "case_id must name a case.");
                JsonNode rev = p.path("case_revision");
                CasesRecords.require(rev.isIntegralNumber() && rev.canConvertToLong() && rev.longValue() >= 1, "case_revision must be a positive whole number.");
                StoredRecord caseRecord = CasesRecords.sameRun(store, "case", caseId, c.runId());
                String state = caseRecord.body().path("state").asText();
                if (!"FINDING".equals(state))
                    return marked(new Rejection("WRONG_STATE", "SEALED".equals(state) ? "This case is already sealed."
                            : "Only a case in FINDING state, with a saved finding, can be sealed."), p);
                if (rev.longValue() != caseRecord.revision())
                    return marked(Rejection.staleRevision("The case is at revision " + caseRecord.revision() + ", not " + rev.longValue() + "."), p);
                JsonNode cause = p.path("cause");
                if (!cause.isMissingNode())
                    CasesRecords.require(sameRef(cause, caseRecord.body().path("finding_ref")), "cause must be the exact finding revision the case records.");

                // External format/signing configuration. Invalid configuration or a key that cannot sign refuses the
                // seal; it never falls back to an unsigned or legacy pack.
                EvidenceConfig.Settings format = config.settings();
                if (!format.ok()) return marked(new Rejection(format.code(), format.reason()), p);
                EvidenceVault.Sealed sealed;
                try {
                    sealed = new EvidenceVault(blobs).seal(store, caseRecord, WallTime.now(wall), format.options());
                } catch (EvidenceVault.Refusal r) {
                    return marked(new Rejection(r.code, r.getMessage()), p);
                }
                String exportId = Ids.newId(), now = WallTime.now(wall);
                ObjectNode body = CasesRecords.object();
                body.put("case_id", caseRecord.id()).put("case_revision", caseRecord.revision()).put("schema", sealed.schema());
                body.set("case_ref", sealed.caseRef());
                body.set("finding_ref", sealed.findingRef());
                body.put("manifest_sha256", sealed.manifestSha256()).put("manifest_length", sealed.manifestBytes().length);
                body.putObject("manifest").put("path", EvidenceVault.MANIFEST_PATH).put("length", sealed.manifestBytes().length).put("sha256", sealed.manifestSha256());
                ArrayNode members = body.putArray("members");
                for (EvidenceVault.Member m : sealed.members()) members.addObject().put("path", m.path()).put("length", m.length()).put("sha256", m.sha256());
                ArrayNode refs = body.putArray("member_refs");
                sealed.memberRefs().forEach(refs::add);
                if (EvidenceVault.SCHEMA.equals(sealed.schema())) {
                    body.putNull("signature").put("signature_reason", "NOT_SIGNED_UNTIL_FINALE");
                } else if (sealed.signatureBytes() == null) {
                    body.put("signing_policy", "UNSIGNED").putNull("signature").put("signature_reason", "UNSIGNED_BY_POLICY");
                } else {
                    body.put("signing_policy", "SIGN_REQUIRED");
                    body.putObject("signature").put("path", EvidenceVault.SIGNATURE_PATH).put("length", sealed.signatureBytes().length)
                            .put("sha256", sealed.signatureSha256()).put("algorithm", "Ed25519").put("key_fingerprint_sha256", sealed.keyFingerprint());
                    body.putNull("signature_reason");
                }
                body.set("custody_summary", sealed.custody());
                body.put("state", "SEALED").put("kind", "ORIGINAL").putNull("copy_of");
                body.put("origin", CasesRecords.ORIGIN).put("sealed_by", session.actorFor(allowedRoles()))
                        .put("scope_note", sealed.signatureBytes() == null ? SCOPE_NOTE : SIGNED_SCOPE_NOTE);

                ObjectNode nextCase = CasesRecords.own(caseRecord).put("state", "SEALED").put("export_id", exportId).put("sealed_utc", now);
                nextCase.set("export_ref", CasesRecords.object().put("type", "export").put("id", exportId).put("revision", 1));
                Double sim = simTime(c, store);
                return HandlerResult.builder().save(RecordChange.create("export", exportId, c.runId(), body), "EXPORT_SEALED", sim)
                        .save(RecordChange.next(caseRecord, nextCase), "CASE_SEALED", sim).build();
            } catch (CasesRecords.Invalid invalid) {
                return marked(invalid.rejection, p);
            }
        }
    }

    @Component @Profile("!stub")
    static final class VerifyExport extends CasesHandlers.Handler {
        private final BlobStore blobs;
        private final ObjectProvider<AutomationSettings> settings;
        private final EvidenceConfig config;
        VerifyExport(ObjectProvider<ClockControl> clock, BlobStore blobs, ObjectProvider<AutomationSettings> settings, EvidenceConfig config) {
            super(clock); this.blobs = blobs; this.settings = settings; this.config = config;
        }
        public String commandType() { return "verify_export"; }
        public Set<Role> allowedRoles() { return Set.of(Role.EXPORTER, Role.AUTOMATION); }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "export", "export_id"); }

        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            try {
                if (autoSealOff(session, settings)) return Rejection.notAllowed("Automatic verification is switched off (auto_seal).");
                onlyKeys(p, Set.of("export_id", "cause"));
                String exportId = CasesRecords.text(p, "export_id");
                CasesRecords.require(Ids.isUuid(exportId), "export_id must name an export.");
                StoredRecord export = CasesRecords.sameRun(store, "export", exportId, c.runId());
                String state = export.body().path("state").asText();
                CasesRecords.require(Set.of("SEALED", "TAMPER_DEMO_COPY").contains(state), "This record is not a verifiable export.");
                if (session.isAutomation() && !"SEALED".equals(state))
                    return marked(Rejection.notAllowed("Automation verifies original sealed exports only, never a tamper demo copy."), p);
                EvidenceConfig.Trust trust = config.trust();
                ObjectNode body = new EvidenceVerifier(EvidenceVerifier.blobLookup(blobs), trust.trust(), trust.problem()).verify(export);
                body.put("export_id", export.id()).put("subject_kind", state).put("origin", CasesRecords.ORIGIN)
                        .put("verified_by", session.actorFor(allowedRoles()))
                        .put("scope_note", body.has("signature_check") ? SIGNED_SCOPE_NOTE : SCOPE_NOTE);
                body.set("export_ref", CasesRecords.ref(export));
                return HandlerResult.builder().save(RecordChange.create("verification_report", Ids.newId(), c.runId(), body), "EXPORT_VERIFIED", simTime(c, store)).build();
            } catch (CasesRecords.Invalid invalid) {
                return marked(invalid.rejection, p);
            }
        }
    }

    @Component @Profile("!stub")
    static final class TamperCopy extends CasesHandlers.Handler {
        private final BlobStore blobs;
        TamperCopy(ObjectProvider<ClockControl> clock, BlobStore blobs) { super(clock); this.blobs = blobs; }
        public String commandType() { return "tamper_copy"; }
        public Set<Role> allowedRoles() { return Set.of(Role.EXPORTER); }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "export", "export_id"); }

        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            try {
                onlyKeys(p, Set.of("export_id", "member_path"));
                String exportId = CasesRecords.text(p, "export_id"), path = CasesRecords.text(p, "member_path");
                CasesRecords.require(Ids.isUuid(exportId), "export_id must name an export.");
                StoredRecord export = CasesRecords.sameRun(store, "export", exportId, c.runId());
                if (!"SEALED".equals(export.body().path("state").asText()))
                    return new Rejection("WRONG_STATE", "Only an original sealed export can be copied for the tamper demo.");
                CasesRecords.require(EvidenceVault.safePath(path), "member_path must be one of the export's member paths.");
                ObjectNode copy = CasesRecords.own(export);
                ArrayNode members = (ArrayNode) copy.path("members");
                JsonNode target = null;
                Set<String> seen = new HashSet<>();
                for (JsonNode m : members) {
                    CasesRecords.require(seen.add(m.path("path").asText()), "The export lists a member path twice.");
                    if (path.equals(m.path("path").asText())) target = m;
                }
                CasesRecords.require(target != null, "member_path must be one of the export's member paths.");
                byte[] original = located(blobs, target.path("sha256").asText());
                if (original == null || original.length == 0)
                    return new Rejection(EvidenceVault.MISSING, "The original bytes of that member cannot be read, so no copy was made.");
                byte[] altered = original.clone();
                int offset = altered.length / 2;
                altered[offset] ^= 0x01;
                String alteredSha;
                try {
                    alteredSha = blobs.put(altered);
                } catch (RuntimeException e) {
                    return new Rejection(EvidenceVault.STORAGE, "The evidence store could not keep the altered copy.");
                }
                String originalSha = target.path("sha256").asText();
                ((ObjectNode) target).put("sha256", alteredSha);
                copy.put("state", "TAMPER_DEMO_COPY").put("kind", "TAMPER_DEMO_COPY");
                copy.set("copy_of", CasesRecords.ref(export));
                copy.putObject("tamper").put("member_path", path).put("byte_offset", offset).put("xor_mask", 1)
                        .put("original_sha256", originalSha).put("altered_sha256", alteredSha);
                copy.put("tampered_by", session.actorFor(allowedRoles()));
                copy.put("demo_notice", "Teaching copy with one altered byte. Its manifest expectations are the original's, so verification must fail. Not evidence.");
                return HandlerResult.builder().save(RecordChange.create("export", Ids.newId(), c.runId(), copy), "TAMPER_COPY_CREATED", simTime(c, store)).build();
            } catch (CasesRecords.Invalid invalid) {
                return invalid.rejection;
            }
        }
    }
}
