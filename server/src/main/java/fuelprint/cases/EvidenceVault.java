package fuelprint.cases;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.Ids;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;

/**
 * Builds an evidence pack from exact saved record revisions: every member is the canonical bytes of one
 * record revision, stored once in the blob store; the manifest (schema {@code fp.evidence/1}) lists each
 * member's path, length and SHA-256 and is stored the same way. Nothing here is signed.
 *
 * <p>Only public case lineage may enter a pack. A reference to anything else (alert sign-ups, notifications,
 * wording, models, hidden-truth or training data) or a record that carries a phone-number or truth field is
 * refused, and so is a missing record or an unsafe member path. Nothing is guessed or substituted.
 */
final class EvidenceVault {
    static final String SCHEMA = "fp.evidence/1";
    static final String SCHEMA_V2 = "fp.evidence/2";
    static final String MEMBER_ENCODING = "fp-canonical-json/1";
    static final String SIGNATURE_PATH = "signature.json";
    static final String SIGNATURE_SCHEMA = "fp.evidence-signature/1";
    static final String UNSIGNED_LIMITATION = "Unsigned integrity record only: matching hashes show these stored bytes are unchanged since sealing, "
            + "not who made them, when, or that any measurement is true.";
    static final String SIGNED_LIMITATION = "Signed integrity record: a valid signature from a trusted key shows these stored bytes are unchanged "
            + "since that key signed them, not when, or that any measurement is true.";
    static final String MANIFEST_PATH = "manifest.json";
    static final int MAX_MEMBERS = 500;
    static final int MAX_MEMBER_BYTES = 1 << 20;

    static final String MISSING = "EVIDENCE_MISSING";
    static final String FORBIDDEN = "EVIDENCE_FORBIDDEN";
    static final String UNSAFE = "EVIDENCE_UNSAFE";
    static final String INCOHERENT = "EVIDENCE_INCOHERENT";
    static final String STORAGE = "EVIDENCE_STORAGE";
    static final String SIGNING = "EVIDENCE_SIGNING_FAILED";

    /** The only record types that may be members. Anything else reached from a reference is refused. */
    static final Set<String> ALLOWED = Set.of("case", "finding", "specimen", "custody_event", "lab_result",
            "screening_record", "analysis", "attempt", "mission", "proposal", "investigation", "survey",
            "trigger", "window", "media", "media_custody_event", "media_lab_result");
    static final Pattern MEMBER_PATH = Pattern.compile(
            "payload/records/([a-z][a-z0-9_]{0,63})/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-r([1-9][0-9]{0,17})\\.json");
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?i).*(phone|msisdn|mobile|contact_number|subscriber|truth|training_label|gateway_password).*");
    private static final Pattern PHONE_VALUE = Pattern.compile("(?<![0-9A-Za-z])(?:\\+?91[\\s-]?)?[6-9][0-9]{4}[\\s-]?[0-9]{5}(?![0-9A-Za-z])");
    private static final Pattern HEX_LIKE = Pattern.compile("[0-9a-fA-F-]{32,}");

    /** The pack cannot be built; {@code code} is one of the constants above. */
    static final class Refusal extends RuntimeException {
        final String code;
        Refusal(String code, String text) { super(text); this.code = code; }
    }

    record Member(String path, long length, String sha256) {}

    /** Everything the export record needs, after the manifest and all members were written and read back. */
    record Sealed(byte[] manifestBytes, String manifestSha256, List<Member> members, ObjectNode custody,
                  ObjectNode caseRef, ObjectNode findingRef, List<ObjectNode> memberRefs,
                  String schema, byte[] signatureBytes, String signatureSha256, String keyFingerprint) {}

    /** Produces an Ed25519 signature over exact manifest bytes; never exposes key material. */
    interface Signer {
        String fingerprint();
        byte[] publicKeySpki();
        byte[] sign(byte[] manifest);
    }

    /** Format 1 (legacy, unsigned) or format 2 with an optional signer (null = unsigned v2). */
    record Options(int format, Signer signer) {
        static final Options V1 = new Options(1, null);
        Options {
            if (format != 1 && format != 2) throw new IllegalArgumentException("format");
            if (format == 1 && signer != null) throw new IllegalArgumentException("v1 cannot be signed");
        }
    }

    private final BlobStore blobs;

    EvidenceVault(BlobStore blobs) { this.blobs = blobs; }

    static String path(String type, String id, long revision) {
        return "payload/records/" + type + "/" + id + "-r" + revision + ".json";
    }

    static boolean safePath(String path) {
        return path != null && path.length() <= 200 && MEMBER_PATH.matcher(path).matches();
    }

    Sealed seal(StoreView store, StoredRecord caseRecord, String createdUtc) { return seal(store, caseRecord, createdUtc, Options.V1); }

    Sealed seal(StoreView store, StoredRecord caseRecord, String createdUtc, Options options) {
        boolean v2 = options.format() == 2;
        Pack pack = new Pack(store, caseRecord, v2 && options.signer() != null);
        pack.collect();
        // Pass 1: every member's bytes are produced and checked before anything is written, so a refusal
        // leaves no partial pack in the blob store.
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, StoredRecord> e : pack.byPath.entrySet()) {
            StoredRecord r = e.getValue();
            byte[] bytes;
            try { bytes = CanonicalJson.bytes(r.body()); }
            catch (CanonicalJson.Unsupported u) { throw new Refusal(UNSAFE, label(r) + " cannot be written as canonical JSON: " + u.getMessage()); }
            if (bytes.length > MAX_MEMBER_BYTES) throw new Refusal(UNSAFE, label(r) + " is larger than the evidence member limit.");
            files.put(e.getKey(), bytes);
        }
        // Pass 2: store and read back.
        List<Member> members = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : files.entrySet())
            members.add(new Member(e.getKey(), e.getValue().length, store(e.getValue(), e.getKey())));
        ObjectNode manifest = CasesRecords.object();
        manifest.put("schema", v2 ? SCHEMA_V2 : SCHEMA).put("case_id", caseRecord.id()).put("case_revision", caseRecord.revision())
                .put("created_utc", createdUtc);
        ArrayNode list = manifest.putArray("members");
        for (Member m : members) list.addObject().put("path", m.path()).put("length", m.length()).put("sha256", m.sha256());
        ArrayNode limits = manifest.putArray("limitations");
        pack.limitations().forEach(limits::add);
        Signer signer = options.signer();
        if (v2) {
            manifest.put("canonicalization", JcsManifest.PROFILE).put("member_encoding", MEMBER_ENCODING);
            if (signer == null) manifest.putNull("signing");
            else manifest.putObject("signing").put("policy", "SIGN_REQUIRED").put("algorithm", "Ed25519")
                    .put("key_fingerprint_sha256", signer.fingerprint());
        }
        byte[] manifestBytes;
        try { manifestBytes = v2 ? JcsManifest.bytes(manifest) : CanonicalJson.bytes(manifest); }
        catch (CanonicalJson.Unsupported | JcsManifest.Unsupported u) { throw new Refusal(UNSAFE, "The manifest cannot be written in its canonical form: " + u.getMessage()); }
        String manifestSha = store(manifestBytes, "manifest");
        byte[] envelope = null;
        String envelopeSha = null;
        if (signer != null) {
            byte[] signature;
            try { signature = signer.sign(manifestBytes); }
            catch (RuntimeException e) { throw new Refusal(SIGNING, "SIGNING_FAILED: the manifest could not be signed."); }
            ObjectNode env = CasesRecords.object().put("schema", SIGNATURE_SCHEMA).put("algorithm", "Ed25519")
                    .put("manifest_sha256", manifestSha)
                    .put("public_key_spki_b64", java.util.Base64.getEncoder().encodeToString(signer.publicKeySpki()))
                    .put("key_fingerprint_sha256", signer.fingerprint())
                    .put("signature_b64", java.util.Base64.getEncoder().encodeToString(signature));
            envelope = JcsManifest.bytes(env);
            envelopeSha = store(envelope, "signature");
        }
        List<ObjectNode> refs = new ArrayList<>();
        pack.byPath.values().forEach(r -> refs.add(CasesRecords.ref(r)));
        return new Sealed(manifestBytes, manifestSha, members, pack.custodySummary(), CasesRecords.ref(caseRecord),
                CasesRecords.ref(pack.finding), refs, v2 ? SCHEMA_V2 : SCHEMA, envelope, envelopeSha,
                signer == null ? null : signer.fingerprint());
    }

    /** Writes bytes and reads them back from disk, so the pack never names bytes that are not really stored. */
    private String store(byte[] bytes, String what) {
        try {
            String sha = blobs.put(bytes);
            if (!Arrays.equals(blobs.get(sha), bytes)) throw new Refusal(STORAGE, "Stored bytes for " + what + " did not read back identically.");
            return sha;
        } catch (Refusal r) {
            throw r;
        } catch (RuntimeException e) {
            throw new Refusal(STORAGE, "The evidence store could not keep the bytes for " + what + ".");
        }
    }

    private static String label(StoredRecord r) { return r.type() + " " + r.id() + " revision " + r.revision(); }

    /** The state of one collection. Every addition is checked against the case's run and the allow-list. */
    private static final class Pack {
        final StoreView store;
        final StoredRecord caseRecord;
        final Map<String, StoredRecord> byPath = new TreeMap<>();
        final List<StoredRecord> specimens = new ArrayList<>();
        final List<StoredRecord> custodyEvents = new ArrayList<>();
        final List<StoredRecord> media = new ArrayList<>();
        final List<StoredRecord> mediaEvents = new ArrayList<>();
        StoredRecord finding;
        final boolean signed;

        Pack(StoreView store, StoredRecord caseRecord, boolean signed) { this.store = store; this.caseRecord = caseRecord; this.signed = signed; }

        void collect() {
            JsonNode body = caseRecord.body();
            if (!CasesRecords.ORIGIN.equals(CasesRecords.text(body, "origin")))
                throw new Refusal(INCOHERENT, "Only a synthetic teaching case can be sealed.");
            add(caseRecord);
            finding = exact(body.path("finding_ref"), "finding", "the case's finding");
            JsonNode fb = finding.body();
            if (!caseRecord.id().equals(CasesRecords.text(fb, "case_id")))
                throw new Refusal(INCOHERENT, "The case's finding belongs to another case.");
            if (!"NOT_ASSESSED".equals(CasesRecords.text(fb, "legal_suitability")))
                throw new Refusal(INCOHERENT, "The finding does not record legal suitability as NOT_ASSESSED.");
            if (store.latestRevision("finding", finding.id()) != finding.revision())
                throw new Refusal(INCOHERENT, "The finding changed after the case recorded it; the case must be revised first.");
            add(finding);
            Set<String> citedScreenings = new HashSet<>();
            if (!fb.path("cited_refs").isArray()) throw new Refusal(INCOHERENT, "The finding's cited_refs is not a list.");
            for (JsonNode ref : fb.path("cited_refs")) {
                String type = ref.path("type").asText("");
                if (!FindingHandlers.CITABLE.contains(type))
                    throw new Refusal(FORBIDDEN, "The finding cites a " + (type.isBlank() ? "record of no type" : type)
                            + " record, which may not enter an evidence pack.");
                StoredRecord cited = exact(ref, type, "a record the finding cites");
                if (type.equals("screening_record")) citedScreenings.add(cited.id());
                add(cited);
                lineage(cited);
            }
            JsonNode screenings = body.path("screening_refs");
            if (!screenings.isArray() || screenings.isEmpty())
                throw new Refusal(MISSING, "The case has no exact screening record references.");
            for (JsonNode ref : screenings) {
                StoredRecord screening = exact(ref, "screening_record", "a screening record of the case");
                add(screening);
                lineage(screening);
            }
            for (String c : citedScreenings)
                if (!byPath.containsKey(pathOfPinned(screenings, c)))
                    throw new Refusal(INCOHERENT, "The finding cites a screening record revision the case did not record.");
            specimens(body);
            labs(body);
            media(body);
        }

        /** Aerosol filter media, their full media custody chains and media lab results (separate from fuel specimens). */
        private void media(JsonNode body) {
            Set<String> mediaIds = new HashSet<>();
            for (String id : ids(body, "media_ids")) {
                StoredRecord m = latest("media", id, "a media item of the case");
                if (!caseRecord.id().equals(CasesRecords.text(m.body(), "case_id")))
                    throw new Refusal(INCOHERENT, "Media " + id + " belongs to another case.");
                add(m); media.add(m); mediaIds.add(id);
                mediaChain(m);
            }
            Set<String> labIds = new LinkedHashSet<>(ids(body, "media_lab_result_ids"));
            for (JsonNode ref : finding.body().path("cited_refs"))
                if ("media_lab_result".equals(ref.path("type").asText()) && !labIds.contains(ref.path("id").asText()))
                    throw new Refusal(INCOHERENT, "The finding cites a media lab result that the case did not record.");
            for (String id : labIds) addMediaLab(latest("media_lab_result", id, "a media lab result of the case"), mediaIds);
            for (JsonNode ref : finding.body().path("cited_refs"))
                if ("media_lab_result".equals(ref.path("type").asText())) addMediaLab(exact(ref, "media_lab_result", "a cited media lab result"), mediaIds);
        }

        private void mediaChain(StoredRecord m) {
            String next = CasesRecords.text(m.body(), "current_media_custody_event_id");
            Set<String> seen = new LinkedHashSet<>();
            while (next != null) {
                if (!seen.add(next) || seen.size() > 1000) throw new Refusal(INCOHERENT, "The custody chain of media " + m.id() + " loops.");
                StoredRecord event = latest("media_custody_event", next, "a media custody event");
                if (!m.id().equals(CasesRecords.text(event.body(), "media_id")))
                    throw new Refusal(INCOHERENT, "A media custody event on the chain belongs to other media.");
                add(event); mediaEvents.add(event);
                next = CasesRecords.text(event.body(), "previous_event_id");
            }
            if (seen.isEmpty()) throw new Refusal(MISSING, "Media " + m.id() + " has no custody event.");
            long recorded = store.list("media_custody_event", caseRecord.runId()).stream()
                    .filter(e -> m.id().equals(CasesRecords.text(e.body(), "media_id"))).count();
            if (recorded != seen.size()) throw new Refusal(INCOHERENT, "Media " + m.id() + " has custody events that are not on its chain.");
        }

        private void addMediaLab(StoredRecord lab, Set<String> mediaIds) {
            JsonNode b = lab.body();
            if (!mediaIds.contains(CasesRecords.text(b, "media_id")))
                throw new Refusal(INCOHERENT, "Media lab result " + lab.id() + " belongs to media outside this case.");
            add(lab);
            StoredRecord pinnedCase = exact(b.path("case_ref"), "case", "the case a media lab result used");
            if (!caseRecord.id().equals(pinnedCase.id())) throw new Refusal(INCOHERENT, "A media lab result was made for another case.");
            add(pinnedCase);
            StoredRecord pinnedMedia = exact(b.path("media_ref"), "media", "the media a media lab result used");
            if (!pinnedMedia.id().equals(CasesRecords.text(b, "media_id"))) throw new Refusal(INCOHERENT, "A media lab result names different media than it records.");
            add(pinnedMedia);
            StoredRecord pinnedCustody = exact(b.path("custody_ref"), "media_custody_event", "the media custody step a media lab result used");
            if (!pinnedMedia.id().equals(CasesRecords.text(pinnedCustody.body(), "media_id")))
                throw new Refusal(INCOHERENT, "A media lab result's custody step belongs to other media.");
            add(pinnedCustody);
        }

        private String pathOfPinned(JsonNode screenings, String id) {
            for (JsonNode ref : screenings)
                if (id.equals(ref.path("id").asText())) return path("screening_record", id, ref.path("revision").asLong());
            return "";
        }

        private void specimens(JsonNode body) {
            for (String id : ids(body, "specimen_ids")) {
                StoredRecord specimen = latest("specimen", id, "a specimen of the case");
                if (!caseRecord.id().equals(CasesRecords.text(specimen.body(), "case_id")))
                    throw new Refusal(INCOHERENT, "Specimen " + id + " belongs to another case.");
                add(specimen);
                specimens.add(specimen);
                custodyChain(specimen);
            }
        }

        private void custodyChain(StoredRecord specimen) {
            String next = CasesRecords.text(specimen.body(), "current_custody_event_id");
            Set<String> seen = new LinkedHashSet<>();
            while (next != null) {
                if (!seen.add(next) || seen.size() > 1000)
                    throw new Refusal(INCOHERENT, "The custody chain of specimen " + specimen.id() + " loops.");
                StoredRecord event = latest("custody_event", next, "a custody event");
                if (!specimen.id().equals(CasesRecords.text(event.body(), "specimen_id")))
                    throw new Refusal(INCOHERENT, "A custody event on the chain belongs to another specimen.");
                add(event);
                custodyEvents.add(event);
                next = CasesRecords.text(event.body(), "previous_event_id");
            }
            if (seen.isEmpty()) throw new Refusal(MISSING, "Specimen " + specimen.id() + " has no custody event.");
            long recorded = store.list("custody_event", caseRecord.runId()).stream()
                    .filter(e -> specimen.id().equals(CasesRecords.text(e.body(), "specimen_id"))).count();
            if (recorded != seen.size())
                throw new Refusal(INCOHERENT, "Specimen " + specimen.id() + " has custody events that are not on its chain.");
        }

        private void labs(JsonNode body) {
            Set<String> specimenIds = new HashSet<>();
            specimens.forEach(s -> specimenIds.add(s.id()));
            Set<String> labIds = new LinkedHashSet<>(ids(body, "lab_result_ids"));
            for (JsonNode ref : finding.body().path("cited_refs"))
                if ("lab_result".equals(ref.path("type").asText()) && !labIds.contains(ref.path("id").asText()))
                    throw new Refusal(INCOHERENT, "The finding cites a lab result that the case did not record.");
            for (String id : labIds) addLab(latest("lab_result", id, "a lab result of the case"), specimenIds);
            for (JsonNode ref : finding.body().path("cited_refs"))
                if ("lab_result".equals(ref.path("type").asText()))
                    addLab(exact(ref, "lab_result", "a cited lab result"), specimenIds);
        }

        private void addLab(StoredRecord lab, Set<String> specimenIds) {
            JsonNode b = lab.body();
            if (!specimenIds.contains(CasesRecords.text(b, "specimen_id")))
                throw new Refusal(INCOHERENT, "Lab result " + lab.id() + " belongs to a specimen outside this case.");
            add(lab);
            StoredRecord pinnedCase = exact(b.path("case_ref"), "case", "the case a lab result used");
            if (!caseRecord.id().equals(pinnedCase.id())) throw new Refusal(INCOHERENT, "A lab result was made for another case.");
            add(pinnedCase);
            StoredRecord pinnedSpecimen = exact(b.path("specimen_ref"), "specimen", "the specimen a lab result used");
            if (!specimenIds.contains(pinnedSpecimen.id())) throw new Refusal(INCOHERENT, "A lab result names a different specimen than it records.");
            add(pinnedSpecimen);
            add(exact(b.path("custody_ref"), "custody_event", "the custody step a lab result used"));
        }

        /** The records an evidence record needs to be understood: its mission, attempt, basis, analysis and investigation. */
        private void lineage(StoredRecord record) {
            JsonNode b = record.body();
            String caseInvestigation = CasesRecords.text(caseRecord.body(), "investigation_id");
            switch (record.type()) {
                case "screening_record" -> {
                    StoredRecord mission = latest("mission", CasesRecords.text(b, "mission_id"), "the mission of a screening record");
                    StoredRecord attempt = latest("attempt", CasesRecords.text(b, "attempt_id"), "the attempt of a screening record");
                    if (!mission.id().equals(CasesRecords.text(attempt.body(), "mission_id")))
                        throw new Refusal(INCOHERENT, "A screening attempt belongs to another mission.");
                    add(mission); add(attempt);
                    JsonNode m = mission.body();
                    boolean proposal = m.hasNonNull("proposal_id"), survey = m.hasNonNull("survey_id");
                    if (proposal == survey) throw new Refusal(INCOHERENT, "A mission must have exactly one proposal or survey basis.");
                    if (proposal) {
                        StoredRecord p = latest("proposal", CasesRecords.text(m, "proposal_id"), "the proposal of a mission");
                        add(p);
                        StoredRecord analysis = latest("analysis", CasesRecords.text(p.body(), "analysis_id"), "the analysis of a proposal");
                        add(analysis);
                        analysisLineage(analysis, caseInvestigation);
                    } else {
                        StoredRecord s = latest("survey", CasesRecords.text(m, "survey_id"), "the survey of a mission");
                        add(s);
                        investigation(CasesRecords.text(s.body(), "investigation_id"), caseInvestigation);
                    }
                }
                case "analysis" -> analysisLineage(record, caseInvestigation);
                default -> { }
            }
        }

        private void analysisLineage(StoredRecord analysis, String caseInvestigation) {
            investigation(CasesRecords.text(analysis.body(), "investigation_id"), caseInvestigation);
            JsonNode windows = analysis.body().path("input_window_ids");
            if (windows.isMissingNode() || windows.isNull()) return;
            for (String id : CasesRecords.strings(windows, "input_window_ids", true)) add(latest("window", id, "an input window of an analysis"));
        }

        private void investigation(String id, String expected) {
            if (id == null || !id.equals(expected)) throw new Refusal(INCOHERENT, "Evidence belongs to a different investigation than the case.");
            StoredRecord inv = latest("investigation", id, "the investigation");
            add(inv);
            String trigger = CasesRecords.text(inv.body(), "trigger_id");
            if (trigger != null) add(latest("trigger", trigger, "the trigger of the investigation"));
        }

        private List<String> ids(JsonNode body, String field) {
            JsonNode value = body.path(field);
            if (value.isMissingNode() || value.isNull()) return List.of();
            try { return CasesRecords.strings(value, field, true); }
            catch (CasesRecords.Invalid i) { throw new Refusal(INCOHERENT, "The case's " + field + " is malformed."); }
        }

        StoredRecord exact(JsonNode ref, String type, String what) {
            if (!ref.isObject() || !type.equals(ref.path("type").asText()) || !ref.path("id").isTextual()
                    || !ref.path("revision").isIntegralNumber() || !ref.path("revision").canConvertToLong() || ref.path("revision").longValue() < 1)
                throw new Refusal(MISSING, "A reference to " + what + " is missing or malformed.");
            String id = ref.path("id").textValue();
            long revision = ref.path("revision").longValue();
            if (!Ids.isUuid(id)) throw new Refusal(MISSING, "A reference to " + what + " does not name a record.");
            return store.get(type, id, revision).filter(r -> Objects.equals(caseRecord.runId(), r.runId()))
                    .orElseThrow(() -> new Refusal(MISSING, "The exact " + type + " revision " + revision + " cited as " + what + " is not recorded in this run."));
        }

        StoredRecord latest(String type, String id, String what) {
            if (id == null || !Ids.isUuid(id)) throw new Refusal(MISSING, "A reference to " + what + " does not name a record.");
            return store.get(type, id).filter(r -> Objects.equals(caseRecord.runId(), r.runId()))
                    .orElseThrow(() -> new Refusal(MISSING, "The " + type + " named as " + what + " is not recorded in this run."));
        }

        void add(StoredRecord r) {
            if (!ALLOWED.contains(r.type()))
                throw new Refusal(FORBIDDEN, "A " + r.type() + " record may not enter an evidence pack.");
            if (!Objects.equals(caseRecord.runId(), r.runId())) throw new Refusal(INCOHERENT, label(r) + " belongs to another run.");
            String path = path(r.type(), r.id(), r.revision());
            if (!safePath(path)) throw new Refusal(UNSAFE, "The member path for " + label(r) + " is not a safe evidence path.");
            privateScan(r);
            StoredRecord prior = byPath.putIfAbsent(path, r);
            if (prior == null && byPath.size() > MAX_MEMBERS) throw new Refusal(UNSAFE, "The evidence pack would have more than " + MAX_MEMBERS + " members.");
        }

        List<String> limitations() {
            List<String> all = new ArrayList<>();
            all.add(signed ? SIGNED_LIMITATION : UNSIGNED_LIMITATION);
            all.add("Synthetic teaching evidence only; no physical measurement, authenticity or legal suitability is assessed.");
            for (JsonNode l : finding.body().path("limitations")) if (l.isTextual()) all.add(l.textValue());
            ObjectNode custody = custodySummary();
            if (!"PASS".equals(custody.path("status").asText()))
                all.add("Custody documentation status: " + custody.path("status").asText() + (custody.path("exceptions").isEmpty() ? "."
                        : " (exceptions: " + String.join(", ", exceptionNames(custody)) + ")."));
            return all;
        }

        private static List<String> exceptionNames(ObjectNode custody) {
            List<String> names = new ArrayList<>();
            custody.path("exceptions").forEach(n -> names.add(n.asText()));
            return names;
        }

        ObjectNode custodySummary() {
            List<StoredRecord> items = new ArrayList<>(specimens), events = new ArrayList<>(custodyEvents);
            items.addAll(media); events.addAll(mediaEvents);
            return summarizeCustody(items, events);
        }
    }

    /** PASS, LIMITED or NOT_APPLICABLE from exact saved custody records; used at sealing and kept in the export record. */
    static ObjectNode summarizeCustody(List<StoredRecord> specimens, List<StoredRecord> events) {
        Set<String> exceptions = new LinkedHashSet<>();
        boolean notIntact = false;
        for (StoredRecord s : specimens)
            for (JsonNode e : s.body().path("custody_exceptions")) if (e.isTextual()) exceptions.add(e.textValue());
        for (StoredRecord e : events) {
            for (JsonNode x : e.body().path("exceptions")) if (x.isTextual()) exceptions.add(x.textValue());
            notIntact |= !"INTACT".equals(e.body().path("seal_state").asText());
        }
        ObjectNode summary = CasesRecords.object();
        summary.put("status", specimens.isEmpty() ? "NOT_APPLICABLE" : (exceptions.isEmpty() && !notIntact ? "PASS" : "LIMITED"));
        ArrayNode list = summary.putArray("exceptions");
        exceptions.forEach(list::add);
        summary.put("seal_not_intact", notIntact).put("specimen_count", specimens.size()).put("event_count", events.size());
        return summary;
    }

    /** Refuses a record that carries a phone-number, contact or truth field. Never echoes the offending value. */
    static void privateScan(StoredRecord r) {
        String where = fuelprint.api.ContactText.contains(r.body()) ? "phone-number-like text" : scan(r.body());
        if (where != null)
            throw new Refusal(FORBIDDEN, "Record " + r.type() + " " + r.id() + " carries a private or truth-related field (" + where + ") and cannot enter an evidence pack.");
    }

    private static String scan(JsonNode node) {
        if (node.isObject()) {
            var names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (PRIVATE_KEY.matcher(name).matches()) return "key " + name;
                String inner = scan(node.get(name));
                if (inner != null) return inner;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) { String inner = scan(child); if (inner != null) return inner; }
        } else if (node.isTextual()) {
            String t = node.textValue();
            if (!HEX_LIKE.matcher(t).matches() && PHONE_VALUE.matcher(t).find()) return "a phone-number-like text value";
        }
        return null;
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
