package fuelprint.cases;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * revise_finding: a person's finding. The session decides the actor; the automation actor is never allowed.
 * One stable finding record per case; every change is a new finding revision, and the case revision that
 * records it pins that exact finding revision in {@code finding_ref}.
 */
@Component @Profile("!stub")
final class FindingHandlers extends CasesHandlers.Handler {
    static final Set<String> OUTCOMES = Set.of("SUPPORTED_WITHIN_SCOPE", "REJECTED_FOR_USE", "INCONCLUSIVE");
    static final Set<String> CITABLE = Set.of("screening_record", "lab_result", "analysis");
    static final List<String> SERVER_LIMITATIONS = List.of(
            "Synthetic teaching observation only; this finding does not assess legal suitability.",
            LabHandlers.CAUSATION_LIMITATION);
    /** The sentences the server itself writes into saved lab results and findings. Whole-string match only. */
    static final Set<String> TRUSTED = Set.copyOf(java.util.stream.Stream.concat(SERVER_LIMITATIONS.stream(),
            java.util.stream.Stream.of(LabHandlers.CAUSATION_LIMITATION, "Synthetic teaching measurement only.")).toList());
    /**
     * The claim policy (conservative, enforced the same way for limitations and alternatives):
     * <ol>
     * <li>HARD words (guilt, proof, probability, coal) are refused anywhere, even when negated.</li>
     * <li>The text is cut into clauses at punctuation, brackets, dashes and joining words (and, but, though, which ...).</li>
     * <li>A clause with no causation, source, fuel-identity or legal word is neutral and accepted.</li>
     * <li>A clause that has one is accepted ONLY if the whole clause is a supported negative disclaimer, matching one
     *     anchored sentence form: "[this result] does not show/establish/identify ... [that it caused the plume |
     *     legal admissibility | the fuel type ...]", "Nothing here shows ...", or "[legal suitability] is not assessed".
     *     Anything else around a claim word (an extra phrase, another proposition, a different object such as
     *     "uncertainty about ...") is refused. There is no "a negation somewhere earlier excuses it" rule.</li>
     * </ol>
     * The server's own saved sentences are accepted unchanged by exact whole-string match (see {@link #TRUSTED}).
     * This is a small supported grammar, not language understanding: it refuses many harmless variants (rewrite
     * them in one of the supported forms) and a claim phrased without any listed word (for example "S02 made the
     * smoke") is not detected.
     */
    private static final Pattern HARD = Pattern.compile(
            "\\b(guilty|guilt|unlawful|illegal|culpable|liable|proved|proven|proof|prove|coal|coke|probability|probable|probably|chance)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM = Pattern.compile(
            "\\b(caus\\w*|responsib\\w*|emit\\w*|emission\\w*|polluter|culprit|source of|origin of|originat\\w*|attributable|attribut\\w*|due to|result(?:s|ed)? from|burn\\w*"
            + "|fuel\\w*|diesel|petrol|gasoline|kerosene|lignite|charcoal|biomass|furnace oil|identif\\w*"
            + "|legal\\w*|admissib\\w*|court|tribunal|lawful|prosecut\\w*|violat\\w*|offen[cs]e|penalt\\w*|complian\\w*|compl(?:y|ies|ied)|breach\\w*|convict\\w*|sanction\\w*|enforc\\w*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAUSE_BREAK = Pattern.compile(
            "[.;:!?,()\\[\\]\\n\\u2014\\u2013]|\\s-\\s|\\b(?:and|but|yet|however|although|though|while|whereas|because|since|so|then|or|which|who|whom|whose|if|unless|except|therefore|thus|hence|as)\\b",
            Pattern.CASE_INSENSITIVE);

    // ---- the supported negative-disclaimer sentence forms (each matches a whole clause) ----
    private static final String SUBJECT = "(?:(?:the|this|that|these)\\s+)?(?:(?:lab|laboratory|specimen|sample|synthetic|teaching|screening|air|online|measurement|test|assay)\\s+){0,2}"
            + "(?:result|results|specimen|sample|finding|measurement|test|assay|analysis|record|report|evidence|data|material)|it";
    private static final String NEGATION = "(?:does\\s+not|do\\s+not|did\\s+not|cannot|can\\s+not|could\\s+not|may\\s+not|might\\s+not|will\\s+not|would\\s+not|doesn't|don't|didn't|can't|couldn't)";
    private static final String VERB = "(?:show|establish|identify|confirm|demonstrate|indicate|determine|assess|support|imply|mean)";
    private static final String NOTHING = "(?:nothing(?:\\s+here|\\s+in\\s+this\\s+(?:result|finding|record))?|no\\s+(?:evidence|result|data|measurement)(?:\\s+here)?)\\s+"
            + "(?:shows|establishes|identifies|confirms|demonstrates|indicates|determines|supports|implies|means)";
    private static final String ENTITY = "(?:it|the\\s+specimen|this\\s+specimen|the\\s+sample|this\\s+sample|the\\s+material|this\\s+material|the\\s+fuel|the\\s+lab\\s+result|this\\s+result|the\\s+result|"
            + "any\\s+(?:named\\s+)?source|a\\s+(?:named\\s+)?source|the\\s+source|S0[1-6]|any\\s+factory)";
    private static final String PROPOSITION = ENTITY + "\\s+(?:caused|emitted|produced|came\\s+from|is\\s+responsible\\s+for|was\\s+responsible\\s+for|originated\\s+from)\\s+(?:the|this)\\s+(?:plume|smoke|emissions?|sulphur\\s+rise|signal)";
    private static final String TOPIC = "(?:legal\\s+(?:admissibility|suitability|status|standing)|causation|the\\s+cause|the\\s+origin|(?:the\\s+)?fuel(?:\\s+(?:type|identity))?|"
            + "the\\s+source(?:\\s+(?:that\\s+(?:caused|emitted|produced)\\s+the\\s+plume|of\\s+the\\s+plume))?|any\\s+(?:named\\s+)?source)";
    private static final String OBJECT = "(?:(?:that|whether)\\s+" + PROPOSITION + "|" + TOPIC + ")";
    private static final Pattern NEGATED_SHOW = Pattern.compile("^(?:(?:" + SUBJECT + ")\\s+" + NEGATION + "\\s+" + VERB + "|" + NOTHING + ")\\s+" + OBJECT + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_ASSESSED = Pattern.compile("^(?:legal\\s+(?:admissibility|suitability|status|standing)|causation|the\\s+cause|the\\s+origin|fuel\\s+identity|the\\s+fuel\\s+type|the\\s+source)"
            + "(?:\\s+of\\s+(?:the|this)\\s+(?:specimen|result|finding))?\\s+(?:is|are|was|were|remains|has|have)\\s+not\\s+(?:been\\s+)?(?:assessed|established|determined|identified)$", Pattern.CASE_INSENSITIVE);

    FindingHandlers(ObjectProvider<ClockControl> clock) { super(clock); }

    public String commandType() { return "revise_finding"; }
    public Set<Role> allowedRoles() { return Set.of(Role.CASE_REVIEWER); }
    public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "case", "case_id"); }

    HandlerOutcome apply(Command c, Session session, StoreView store) {
        JsonNode p = c.payload();
        CasesRecords.require(!p.has("legal_suitability"), "legal_suitability is always NOT_ASSESSED and cannot be set.");
        StoredRecord caseRecord = CasesRecords.mutableCase(store, CasesRecords.text(p, "case_id"), c.runId());
        JsonNode caseBody = caseRecord.body();
        String outcome = CasesRecords.text(p, "outcome");
        CasesRecords.require(outcome != null && OUTCOMES.contains(outcome),
                "outcome must be SUPPORTED_WITHIN_SCOPE, REJECTED_FOR_USE or INCONCLUSIVE.");
        List<String> limitations = CasesRecords.strings(p.path("limitations"), "limitations", false);
        List<String> alternatives = CasesRecords.strings(p.path("alternatives"), "alternatives", true);
        for (String text : concat(limitations, alternatives)) CasesRecords.require(text.length() <= 4096, "Finding text is too long.");
        // The server's own saved disclaimers may be resubmitted unchanged (an exact whole-string match only;
        // never a prefix, a contained sentence or an appended claim).
        for (String text : concat(limitations, alternatives)) if (!TRUSTED.contains(text)) refuseUnsupportedClaims(text);

        CasesRecords.require(p.path("cited_refs").isArray(), "cited_refs must be an array of exact record revisions.");
        List<ObjectNode> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean usable = false, rejected = false, needsAlternatives = false;
        for (JsonNode ref : p.path("cited_refs")) {
            CasesRecords.require(ref.isObject() && ref.size() == 3 && ref.path("type").isTextual() && ref.path("id").isTextual()
                    && ref.path("revision").isIntegralNumber() && ref.path("revision").canConvertToLong() && ref.path("revision").longValue() >= 1,
                    "Each cited ref needs exactly a type, an id and a positive whole revision.");
            String type = ref.path("type").textValue(), id = ref.path("id").textValue();
            long revision = ref.path("revision").longValue();
            CasesRecords.require(CITABLE.contains(type), "A finding may cite only screening_record, lab_result or analysis records.");
            CasesRecords.require(Ids.isUuid(id), "Cited record ids must be UUIDs.");
            CasesRecords.require(seen.add(type + "/" + id), "A record may be cited once.");
            StoredRecord cited = store.get(type, id, revision).filter(r -> c.runId().equals(r.runId()))
                    .orElseThrow(() -> CasesRecords.invalid("Cited " + type + " revision must exist in this run."));
            JsonNode b = cited.body();
            switch (type) {
                case "screening_record" -> {
                    CasesRecords.require(pinned(caseBody, cited), "A cited screening record must be the exact revision recorded in this case.");
                    String decision = CasesRecords.text(b, "decision");
                    usable |= "VALID".equals(decision);
                    rejected |= "REJECTED".equals(decision);
                }
                case "lab_result" -> {
                    StoredRecord specimen = store.get("specimen", CasesRecords.text(b, "specimen_id"))
                            .filter(r -> c.runId().equals(r.runId()))
                            .orElseThrow(() -> CasesRecords.invalid("The cited lab result has no specimen in this run."));
                    CasesRecords.require(caseRecord.id().equals(CasesRecords.text(specimen.body(), "case_id")),
                            "The cited lab result belongs to another case.");
                    String qc = CasesRecords.text(b, "qc_status");
                    usable |= "PASS".equals(qc);
                    rejected |= "FAIL".equals(qc);
                }
                default -> {
                    CasesRecords.require(Ids.isUuid(CasesRecords.text(b, "investigation_id"))
                            && CasesRecords.text(b, "investigation_id").equals(CasesRecords.text(caseBody, "investigation_id")),
                            "The cited analysis belongs to another investigation.");
                    needsAlternatives |= !"PROVISIONAL_SINGLE".equals(CasesRecords.text(b, "outcome"));
                }
            }
            refs.add(CasesRecords.ref(cited));
        }
        switch (outcome) {
            case "SUPPORTED_WITHIN_SCOPE" -> CasesRecords.require(usable,
                    "A supported finding must cite a VALID screening record or a lab result whose QC passed.");
            case "REJECTED_FOR_USE" -> CasesRecords.require(rejected,
                    "A rejected-for-use finding must cite a REJECTED screening record or a lab result whose QC failed.");
            default -> { }
        }
        CasesRecords.require(!needsAlternatives || !alternatives.isEmpty(),
                "A cited analysis that is not a single provisional source needs at least one alternative.");

        StoredRecord prior = null;
        JsonNode pinned = caseBody.path("finding_ref");
        if (caseBody.hasNonNull("finding_ref")) {
            CasesRecords.require("finding".equals(CasesRecords.text(pinned, "type")) && Ids.isUuid(CasesRecords.text(pinned, "id")),
                    "The case's recorded finding link is inconsistent.");
            prior = store.get("finding", pinned.path("id").textValue())
                    .filter(f -> c.runId().equals(f.runId()) && caseRecord.id().equals(CasesRecords.text(f.body(), "case_id")))
                    .orElseThrow(() -> CasesRecords.invalid("The case's recorded finding link is inconsistent."));
        }
        ObjectNode body = CasesRecords.object().put("case_id", caseRecord.id()).put("outcome", outcome).put("origin", CasesRecords.ORIGIN);
        body.putArray("cited_refs").addAll(refs);
        var alt = body.putArray("alternatives");
        alternatives.forEach(alt::add);
        var lim = body.putArray("limitations");
        limitations.forEach(lim::add);
        for (String fixed : SERVER_LIMITATIONS) if (!limitations.contains(fixed)) lim.add(fixed);
        body.put("legal_suitability", "NOT_ASSESSED");
        body.set("case_ref", CasesRecords.ref(caseRecord));
        if (prior == null) body.putNull("prior_finding_ref"); else body.set("prior_finding_ref", CasesRecords.ref(prior));
        body.put("reviewed_by", session.actorFor(allowedRoles()));

        String findingId = prior == null ? Ids.newId() : prior.id();
        RecordChange finding = prior == null ? RecordChange.create("finding", findingId, c.runId(), body) : RecordChange.next(prior, body);
        // The case revision that records this finding pins the exact finding revision it saved.
        long findingRevision = prior == null ? 1 : prior.revision() + 1;
        ObjectNode nextCase = CasesRecords.own(caseRecord).put("state", "FINDING");
        nextCase.set("finding_ref", CasesRecords.object().put("type", "finding").put("id", findingId).put("revision", findingRevision));
        Double sim = simTime(c, store);
        return HandlerResult.builder().save(finding, "FINDING_REVISED", sim)
                .save(RecordChange.next(caseRecord, nextCase), "CASE_FINDING_SET", sim).build();
    }

    private static boolean pinned(JsonNode caseBody, StoredRecord cited) {
        for (JsonNode ref : caseBody.path("screening_refs"))
            if (cited.id().equals(ref.path("id").asText()) && ref.path("revision").canConvertToLong()
                    && cited.revision() == ref.path("revision").longValue()) return true;
        return false;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** People may disclose limits; they may not write guilt, legal or fuel claims, or say a specimen or source caused the plume. */
    static void refuseUnsupportedClaims(String text) {
        CasesRecords.require(!HARD.matcher(text).find(),
                "Finding text must not contain guilt, legal-liability, proof, probability or fuel-identity claims.");
        for (String clause : CLAUSE_BREAK.split(text)) {
            if (!CLAIM.matcher(clause).find()) continue;
            String whole = clause.trim().replaceAll("\\s+", " ");
            CasesRecords.require(NEGATED_SHOW.matcher(whole).matches() || NOT_ASSESSED.matcher(whole).matches(),
                    "Finding text must not claim legal status, fuel identity or that a specimen or source caused the plume; "
                    + "say only what the evidence does not show, for example \"This result does not show that it caused the plume.\"");
        }
    }
}
