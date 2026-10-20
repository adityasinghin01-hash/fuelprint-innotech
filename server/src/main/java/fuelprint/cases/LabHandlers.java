package fuelprint.cases;

import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** request_lab: the lab person's numbers, checked by exact decimal rules. The server never invents a value. */
@Component @Profile("!stub")
final class LabHandlers extends CasesHandlers.Handler {
    static final String CAUSATION_LIMITATION =
            "A usable lab result describes only this specimen; it does not show that the specimen, its material or any named source caused the plume.";
    private final CasesMethod method;

    LabHandlers(ObjectProvider<ClockControl> clock, CasesMethod method) { super(clock); this.method = method; }

    public String commandType() { return "request_lab"; }
    /** AGREEMENT section 5 lists lab_demo only. Section 12's AUTO means the maths runs on the lab person's numbers. */
    public Set<Role> allowedRoles() { return Set.of(Role.LAB_DEMO); }
    public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "specimen", "specimen_id"); }

    HandlerOutcome apply(Command c, Session session, StoreView store) {
        JsonNode p = c.payload();
        StoredRecord specimen = CasesRecords.sameRun(store, "specimen", CasesRecords.text(p, "specimen_id"), c.runId());
        StoredRecord caseRecord = CasesRecords.mutableCase(store, CasesRecords.text(specimen.body(), "case_id"), c.runId());
        JsonNode s = specimen.body();
        String requested = CasesRecords.requiredText(p, "method_id");
        CasesRecords.require(method.id().equals(requested), "Choose the declared teaching fuel assay method.");
        CasesRecords.require(method.id().equals(CasesRecords.text(s, "method_id")), "The specimen was taken for a different method.");
        CasesRecords.require(method.material().equals(CasesRecords.text(s, "material")), "The method does not match the specimen's actual material.");
        CasesRecords.require(CasesRecords.ORIGIN.equals(CasesRecords.text(s, "origin")), "Lab work requires a synthetic teaching specimen.");
        JsonNode qc = p.path("qc_inputs");
        CasesRecords.require(qc.isMissingNode() || qc.isNull() || qc.isObject(), "qc_inputs must be an object of numbers, or null if none were measured.");
        CasesRecords.require(!qc.has("mass_mg"), "mass_mg comes from the recorded specimen, not from qc_inputs.");
        StoredRecord custody = CasesRecords.sameRun(store, "custody_event", CasesRecords.text(s, "current_custody_event_id"), c.runId());
        CasesRecords.require(specimen.id().equals(CasesRecords.text(custody.body(), "specimen_id")), "Current custody linkage is incomplete or inconsistent.");

        Double sealed = s.path("sealed_sim_time_s").isNumber() ? s.path("sealed_sim_time_s").doubleValue() : null;
        Double analysis = simTime(c, store);
        ObjectNode result = LabMath.evaluate(qc.isObject() ? qc : null, s.path("mass_mg"), sealed, analysis, method);
        ObjectNode body = CasesRecords.object().put("specimen_id", specimen.id()).put("method_id", method.id())
                .put("origin", CasesRecords.ORIGIN);
        body.set("qc_inputs", qc.isObject() ? qc.deepCopy() : com.fasterxml.jackson.databind.node.NullNode.getInstance());
        result.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
        body.set("specimen_ref", CasesRecords.ref(specimen));
        body.set("case_ref", CasesRecords.ref(caseRecord));
        body.set("custody_ref", CasesRecords.ref(custody));
        body.set("method_limits", method.limits());
        if (sealed == null) body.putNull("sealed_sim_time_s"); else body.put("sealed_sim_time_s", sealed);
        body.set("sealed_sim_time_reason", s.path("sealed_sim_time_reason").isTextual() ? s.path("sealed_sim_time_reason") : com.fasterxml.jackson.databind.node.NullNode.getInstance());
        if (analysis == null) body.putNull("analysis_sim_time_s"); else body.put("analysis_sim_time_s", analysis);
        body.put("analysis_sim_time_reason", analysis == null ? "CURRENT_RUN_CLOCK_UNAVAILABLE" : null);
        body.put("analyzed_by", session.actorFor(allowedRoles()));
        var limitations = body.putArray("limitations");
        limitations.add("Synthetic teaching measurement only.");
        limitations.add(CAUSATION_LIMITATION);
        for (JsonNode exception : s.path("custody_exceptions"))
            if (exception.isTextual()) limitations.add("Custody exception recorded for this specimen: " + exception.textValue() + ".");
        String holder = CasesRecords.text(s, "current_holder");
        if (holder != null && !CasesRecords.isHolder(session, holder))
            limitations.add("The lab request was recorded while custody was with " + holder + ", not this lab session.");

        String resultId = Ids.newId();
        ObjectNode nextCase = CasesRecords.own(caseRecord);
        if (Set.of("INTAKE", "SPECIMEN").contains(nextCase.path("state").asText())) nextCase.put("state", "LAB");
        if (!nextCase.has("lab_result_ids")) nextCase.putArray("lab_result_ids");
        CasesRecords.append(nextCase, "lab_result_ids", resultId);
        return HandlerResult.builder()
                .save(RecordChange.create("lab_result", resultId, c.runId(), body), "LAB_RESULT_RECORDED", analysis)
                .save(RecordChange.next(caseRecord, nextCase), "CASE_LAB_ADDED", analysis).build();
    }
}
