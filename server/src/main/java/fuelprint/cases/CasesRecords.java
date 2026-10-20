package fuelprint.cases;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.Ids;
import fuelprint.core.command.Command;
import fuelprint.core.command.Rejection;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;

/** Validates Cases' joins using only recorded public data, never Ops-private helpers. */
final class CasesRecords {
    static final String ORIGIN = "SYNTHETIC_TEACHING";
    static final Set<String> MUTABLE_CASE_STATES = Set.of("INTAKE", "SPECIMEN", "LAB", "FINDING");
    static final Set<String> EXCEPTIONS = Set.of("BROKEN_SEAL", "MISSING_SEAL", "RECIPIENT_ABSENT",
            "REFUSED", "LATE_TRANSFER", "CORRECTION");
    static final Set<Role> CUSTODIANS = Set.of(Role.INSPECTOR_DEMO, Role.LAB_DEMO);

    private CasesRecords() {}
    static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    static String text(JsonNode n, String field) { return n.path(field).isTextual() ? n.path(field).textValue() : null; }
    static String requiredText(JsonNode n, String field) {
        String value = text(n, field);
        require(value != null && !value.isBlank() && value.length() <= 4096, field + " must contain text.");
        return value;
    }
    static ObjectNode own(StoredRecord record) {
        ObjectNode body = record.body();
        RecordChange.COMMON_FIELDS.forEach(body::remove);
        return body;
    }
    static ObjectNode ref(StoredRecord record) {
        return object().put("type", record.type()).put("id", record.id()).put("revision", record.revision());
    }
    static RecordKey subject(Command command, String type, String field) {
        String id = text(command.payload(), field);
        return Ids.isUuid(id) ? new RecordKey(type, id) : null;
    }
    static StoredRecord sameRun(StoreView store, String type, String id, String runId) {
        require(Ids.isUuid(id) && Ids.isUuid(runId), type + " must name a recorded UUID in this run.");
        return store.get(type, id).filter(r -> runId.equals(r.runId()))
                .orElseThrow(() -> invalid(type + " must exist in this run."));
    }
    static StoredRecord run(StoreView store, String runId) {
        return sameRun(store, "run", runId, runId);
    }
    static List<String> strings(JsonNode value, String field, boolean emptyAllowed) {
        require(value.isArray() && (emptyAllowed || !value.isEmpty()), field + " must be an array.");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            require(item.isTextual() && !item.textValue().isBlank(), field + " must contain text values.");
            result.add(item.textValue());
        }
        require(new HashSet<>(result).size() == result.size(), field + " must not contain duplicate values.");
        return result;
    }
    static void append(ObjectNode body, String field, String value) {
        List<String> values = strings(body.path(field), field, true);
        if (!values.contains(value)) body.withArray(field).add(value);
    }
    static StoredRecord investigation(StoreView store, StoredRecord screening) {
        String run = screening.runId();
        JsonNode evidence = screening.body();
        require(Set.of("VALID", "LIMITED", "REJECTED").contains(evidence.path("decision").asText()),
                "Screening decision must remain VALID, LIMITED or REJECTED.");
        require("SYNTHETIC_ONLINE_SCREENING".equals(text(evidence, "named_use")),
                "Cases intake accepts the declared synthetic online screening use only.");
        if (evidence.hasNonNull("origin")) require(ORIGIN.equals(text(evidence, "origin")), "Evidence origin is incompatible.");
        if (evidence.hasNonNull("domain")) require("AIR_SCREENING".equals(text(evidence, "domain")), "Evidence domain is incompatible.");
        strings(evidence.path("reasons"), "screening reasons", true);
        StoredRecord mission = sameRun(store, "mission", text(evidence, "mission_id"), run);
        StoredRecord attempt = sameRun(store, "attempt", text(evidence, "attempt_id"), run);
        require(mission.id().equals(text(attempt.body(), "mission_id")), "Screening attempt belongs to another mission.");
        JsonNode basis = mission.body();
        boolean proposalBasis = basis.hasNonNull("proposal_id"), surveyBasis = basis.hasNonNull("survey_id");
        require(proposalBasis != surveyBasis, "Mission must have exactly one recorded proposal or survey basis.");
        String investigationId;
        if (proposalBasis) {
            StoredRecord proposal = sameRun(store, "proposal", text(basis, "proposal_id"), run);
            StoredRecord analysis = sameRun(store, "analysis", text(proposal.body(), "analysis_id"), run);
            investigationId = text(analysis.body(), "investigation_id");
        } else {
            StoredRecord survey = sameRun(store, "survey", text(basis, "survey_id"), run);
            investigationId = text(survey.body(), "investigation_id");
        }
        return sameRun(store, "investigation", investigationId, run);
    }
    static StoredRecord mutableCase(StoreView store, String id, String runId) {
        return caseInStates(store, id, runId, false);
    }
    static StoredRecord caseForSpecimen(StoreView store, String id, String runId) {
        return caseInStates(store, id, runId, true);
    }
    private static StoredRecord caseInStates(StoreView store, String id, String runId, boolean allowSealed) {
        StoredRecord record = sameRun(store, "case", id, runId);
        String state = record.body().path("state").asText();
        if (!MUTABLE_CASE_STATES.contains(state) && !(allowSealed && "SEALED".equals(state)))
            throw new Invalid(new Rejection("WRONG_STATE", allowSealed
                    ? "Specimens require a case in INTAKE or a later lifecycle state."
                    : "This case must be in INTAKE, SPECIMEN, LAB or FINDING."));
        return record;
    }
    static String holder(JsonNode payload) {
        String holder = requiredText(payload, "to_holder");
        require(holder.length() <= 128 && !Set.of("automation", "anonymous").contains(holder.trim().toLowerCase(java.util.Locale.ROOT)),
                "to_holder must identify a human custodian.");
        return holder;
    }
    static boolean isHolder(Session session, String holder) {
        for (Role role : CUSTODIANS) {
            if (session.roles().contains(role) && session.actorFor(Set.of(role)).equals(holder)) return true;
        }
        return false;
    }
    static String eventTime(JsonNode payload) {
        String value = requiredText(payload, "event_time_utc");
        try { Instant.parse(value); }
        catch (DateTimeParseException e) { throw invalid("event_time_utc must be an ISO 8601 UTC instant."); }
        require(value.endsWith("Z"), "event_time_utc must use UTC (Z).");
        return value;
    }
    static void require(boolean valid, String reason) { if (!valid) throw invalid(reason); }
    static Invalid invalid(String reason) { return new Invalid(Rejection.invalid(reason)); }
    static final class Invalid extends RuntimeException {
        final Rejection rejection;
        Invalid(Rejection rejection) { super(rejection.reasonText()); this.rejection = rejection; }
    }
}
