package fuelprint.ai;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.core.store.*;

/** Public-case eligibility and count comparison only; never creates a model or asserts held-out evaluation. */
final class LearningRules {
    private static final List<String> COUNTS = List.of("nights", "first_correct", "in_top_3",
            "wrong_when_no_source", "wrong_when_unregistered", "misses");
    private LearningRules() { }

    /** Caller must establish the same held-out nights; counts alone cannot establish that provenance. */
    static boolean gate(JsonNode candidate, JsonNode parent) {
        if (!countsValid(candidate) || !countsValid(parent)) return false;
        return candidate.path("nights").longValue() == parent.path("nights").longValue()
                && candidate.path("first_correct").longValue() >= parent.path("first_correct").longValue() - 2;
    }

    private static boolean countsValid(JsonNode counts) {
        if (counts == null || !counts.isObject()) return false;
        for (String name : COUNTS) {
            JsonNode n = counts.path(name);
            if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 0) return false;
        }
        long nights = counts.path("nights").longValue();
        return nights > 0 && COUNTS.stream().allMatch(name -> counts.path(name).longValue() <= nights)
                && counts.path("first_correct").longValue() <= counts.path("in_top_3").longValue();
    }

    static ArrayNode lessons(StoreView store) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (StoredRecord c : store.listAll("case")) {
            ObjectNode row = result.addObject().put("case_id", c.id()).put("status", "SKIPPED");
            row.set("case_ref", ref(c)); row.putNull("site_id");
            String reason = "Case is not sealed; not a lesson.";
            if ("SEALED".equals(c.body().path("state").asText())) {
                StoredRecord finding = exact(store, c.body().path("finding_ref"), "finding", c.runId());
                if (finding == null || !c.id().equals(finding.body().path("case_id").asText())) {
                    reason = "Exact case finding is unavailable; not a lesson.";
                } else {
                    row.set("finding_ref", ref(finding));
                    String outcome = finding.body().path("outcome").asText();
                    if (!"SUPPORTED_WITHIN_SCOPE".equals(outcome)) reason = outcome + ", not a lesson.";
                    else reason = assessSupported(store, c, finding, row);
                }
            }
            row.put("reason", reason);
        }
        return result;
    }

    private static String assessSupported(StoreView store, StoredRecord c, StoredRecord finding, ObjectNode row) {
        List<StoredRecord> usable = new ArrayList<>(); Set<String> labels = new HashSet<>();
        for (JsonNode citation : finding.body().path("cited_refs")) {
            if (!"lab_result".equals(citation.path("type").asText())) continue;
            StoredRecord lab = exact(store, citation, "lab_result", c.runId());
            if (lab == null || !"PASS".equals(lab.body().path("qc_status").asText())) continue;
            StoredRecord specimen = exact(store, lab.body().path("specimen_ref"), "specimen", c.runId());
            StoredRecord caseInput = exact(store, lab.body().path("case_ref"), "case", c.runId());
            if (specimen == null || !specimen.id().equals(lab.body().path("specimen_id").asText())
                    || !c.id().equals(specimen.body().path("case_id").asText())
                    || caseInput == null || !c.id().equals(caseInput.id())) continue;
            String site = specimen.body().path("site_id").asText();
            if (!site.matches("S0[1-6]")) continue;
            labels.add(site); usable.add(lab);
        }
        if (usable.isEmpty()) return "No cited PASS lab with exact same-case specimen lineage; not a lesson.";
        if (labels.size() != 1) return "Conflicting specimen site labels; not a lesson.";
        row.put("status", "ELIGIBLE").put("site_id", labels.iterator().next());
        row.set("lab_ref", ref(usable.get(0)));
        return "Supported finding and cited PASS specimen lab; eligible for future training, not yet trained.";
    }

    static StoredRecord exact(StoreView store, JsonNode ref, String type, String run) {
        if (!ref.isObject() || !type.equals(ref.path("type").asText()) || !ref.path("id").isTextual()
                || !ref.path("revision").isIntegralNumber() || !ref.path("revision").canConvertToLong()
                || ref.path("revision").longValue() < 1) return null;
        return store.get(type, ref.path("id").textValue(), ref.path("revision").longValue())
                .filter(r -> Objects.equals(run, r.runId())).orElse(null);
    }

    static ObjectNode ref(StoredRecord r) {
        return JsonNodeFactory.instance.objectNode().put("type", r.type()).put("id", r.id()).put("revision", r.revision());
    }
}
