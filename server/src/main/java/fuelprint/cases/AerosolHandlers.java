package fuelprint.cases;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.core.Ids;
import fuelprint.core.WallTime;
import fuelprint.core.command.*;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Separate aerosol filter media: create_media, append_media_custody and request_media_lab. Every collection and lab
 * number is entered by a person and labelled synthetic teaching; nothing is generated, no Field attempt is invented
 * (Field has no aerosol capture), media never carry a training site label, and fuel specimens and media stay
 * distinct typed records. None of these commands accepts the automation actor.
 */
final class AerosolHandlers {
    private AerosolHandlers() {}
    static final String INPUT_BASIS = "HUMAN_RECORDED_SYNTHETIC_TEACHING";
    static final String ATTEMPT_REASON = "FIELD_AEROSOL_CAPTURE_NOT_AVAILABLE";
    static final String SYNTHETIC_LIMITATION = "Synthetic teaching aerosol filter measurement, entered by a person; no airborne capture was simulated.";
    static final String CAUSATION_LIMITATION = "A usable aerosol result describes only this filter; it does not show that any named source caused the plume.";
    private static final Pattern SITE_LABEL = Pattern.compile("(?i)(?<![A-Za-z0-9])S0[1-6](?![A-Za-z0-9])");

    static void onlyKeys(JsonNode p, Set<String> allowed) {
        CasesRecords.require(p.isObject(), "The command payload must be an object.");
        p.fieldNames().forEachRemaining(k -> CasesRecords.require(allowed.contains(k), "The command payload has an unsupported field: " + k + "."));
    }

    static String bounded(JsonNode p, String field) {
        String v = CasesRecords.requiredText(p, field);
        CasesRecords.require(v.length() <= 256, field + " must be at most 256 characters.");
        return v;
    }

    @Component @Profile("!stub")
    static final class CreateMedia extends CasesHandlers.Handler {
        static final Set<String> KEYS = Set.of("case_id", "material", "method_id", "media_type", "container", "seal_id", "location_label", "collection");
        private final Clock wall;
        CreateMedia(ObjectProvider<ClockControl> clock, Clock wall) { super(clock); this.wall = wall; }
        public String commandType() { return "create_media"; }
        public Set<Role> allowedRoles() { return Set.of(Role.INSPECTOR_DEMO); }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "case", "case_id"); }

        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            onlyKeys(p, KEYS);
            StoredRecord caseRecord = CasesRecords.mutableCase(store, CasesRecords.text(p, "case_id"), c.runId());
            CasesRecords.require(CasesRecords.ORIGIN.equals(CasesRecords.text(caseRecord.body(), "origin")), "Media require a synthetic teaching case.");
            CasesRecords.require(AerosolMethod.MATERIAL.equals(CasesRecords.text(p, "material")), "material must be " + AerosolMethod.MATERIAL + ".");
            CasesRecords.require(AerosolMethod.ID.equals(CasesRecords.text(p, "method_id")), "method_id must be the declared " + AerosolMethod.ID + ".");
            CasesRecords.require("FILTER".equals(CasesRecords.text(p, "media_type")), "media_type must be FILTER.");
            String container = bounded(p, "container"), seal = bounded(p, "seal_id"), location = bounded(p, "location_label");
            CasesRecords.require(!SITE_LABEL.matcher(location).find(),
                    "location_label must describe the sampling place, not a source label (S01 to S06 are not accepted on media).");
            Double sim = simTime(c, store);
            ObjectNode collected;
            try { collected = AerosolMath.collection(p.path("collection"), sim); }
            catch (AerosolMath.Refused r) { throw CasesRecords.invalid(r.getMessage()); }

            String mediaId = Ids.newId(), eventId = Ids.newId(), now = WallTime.now(wall);
            String holder = session.actorFor(allowedRoles());
            ObjectNode media = CasesRecords.object().put("case_id", caseRecord.id()).put("media_type", "FILTER").put("material", AerosolMethod.MATERIAL)
                    .put("method_id", AerosolMethod.ID).put("size_class", "UNCLASSIFIED").put("container", container).put("seal_id", seal)
                    .put("location_label", location);
            collected.fields().forEachRemaining(e -> media.set(e.getKey(), e.getValue()));
            media.putNull("attempt_ref").put("attempt_ref_reason", ATTEMPT_REASON).put("origin", CasesRecords.ORIGIN).put("input_basis", INPUT_BASIS)
                    .put("authority_basis", "INSPECTOR_DEMO").put("collector_id", holder).put("current_holder", holder)
                    .put("current_media_custody_event_id", eventId).put("seal_state", "INTACT").put("recorded_utc", now)
                    .put("recorded_sim_time_s", sim).put("recorded_sim_time_reason", sim == null ? "CURRENT_RUN_CLOCK_UNAVAILABLE" : null);
            media.putArray("custody_exceptions");
            media.set("case_ref", CasesRecords.ref(caseRecord));
            ObjectNode event = custody(mediaId, null, holder, holder, "INTACT", now, now, List.of(), holder);
            ObjectNode nextCase = CasesRecords.own(caseRecord);
            if ("INTAKE".equals(nextCase.path("state").asText())) nextCase.put("state", "SPECIMEN");
            if (!nextCase.has("media_ids")) nextCase.putArray("media_ids");
            CasesRecords.append(nextCase, "media_ids", mediaId);
            return HandlerResult.builder().save(RecordChange.create("media", mediaId, c.runId(), media), "MEDIA_CREATED", sim)
                    .save(RecordChange.create("media_custody_event", eventId, c.runId(), event), "MEDIA_CUSTODY_STARTED", sim)
                    .save(RecordChange.next(caseRecord, nextCase), "CASE_MEDIA_ADDED", sim).build();
        }
    }

    @Component @Profile("!stub")
    static final class AppendMediaCustody extends CasesHandlers.Handler {
        static final Set<String> KEYS = Set.of("media_id", "to_holder", "seal_state", "event_time_utc", "exceptions");
        private final Clock wall;
        AppendMediaCustody(ObjectProvider<ClockControl> clock, Clock wall) { super(clock); this.wall = wall; }
        public String commandType() { return "append_media_custody"; }
        public Set<Role> allowedRoles() { return CasesRecords.CUSTODIANS; }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "media", "media_id"); }

        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            onlyKeys(p, KEYS);
            StoredRecord media = CasesRecords.sameRun(store, "media", CasesRecords.text(p, "media_id"), c.runId());
            CasesRecords.mutableCase(store, CasesRecords.text(media.body(), "case_id"), c.runId());
            String holder = CasesRecords.requiredText(media.body(), "current_holder");
            String to = CasesRecords.holder(p), seal = CasesRecords.requiredText(p, "seal_state");
            String eventTime = CasesRecords.eventTime(p);
            List<String> exceptions = CasesRecords.strings(p.path("exceptions"), "exceptions", true);
            CasesRecords.require(CasesRecords.EXCEPTIONS.containsAll(exceptions), "Choose documented custody exceptions only.");
            CasesRecords.require(Set.of("INTACT", "BROKEN", "MISSING").contains(seal), "seal_state must be INTACT, BROKEN or MISSING.");
            CasesRecords.require(!"BROKEN".equals(seal) || exceptions.contains("BROKEN_SEAL"), "A broken seal needs BROKEN_SEAL.");
            CasesRecords.require(!"MISSING".equals(seal) || exceptions.contains("MISSING_SEAL"), "A missing seal needs MISSING_SEAL.");
            CasesRecords.require(!exceptions.contains("BROKEN_SEAL") || "BROKEN".equals(seal), "BROKEN_SEAL must agree with seal_state.");
            CasesRecords.require(!exceptions.contains("MISSING_SEAL") || "MISSING".equals(seal), "MISSING_SEAL must agree with seal_state.");
            if (!CasesRecords.isHolder(session, holder) && exceptions.isEmpty())
                return Rejection.notAllowed("Only the current holder may transfer without a documented exception.");
            StoredRecord previous = CasesRecords.sameRun(store, "media_custody_event", CasesRecords.text(media.body(), "current_media_custody_event_id"), c.runId());
            CasesRecords.require(media.id().equals(CasesRecords.text(previous.body(), "media_id"))
                    && holder.equals(CasesRecords.text(previous.body(), "to_holder")), "Current media custody linkage is incomplete or inconsistent.");
            boolean noTransfer = exceptions.contains("RECIPIENT_ABSENT") || exceptions.contains("REFUSED") || exceptions.contains("CORRECTION");
            if (exceptions.contains("CORRECTION")) CasesRecords.require(holder.equals(to), "A correction cannot silently transfer custody.");
            String actualTo = noTransfer ? holder : to;
            String eventId = Ids.newId(), now = WallTime.now(wall);
            ObjectNode event = custody(media.id(), previous.id(), holder, actualTo, seal, eventTime, now, exceptions, session.actorFor(allowedRoles()));
            if (noTransfer && !holder.equals(to)) event.put("attempted_to_holder", to);
            ObjectNode next = CasesRecords.own(media).put("current_holder", actualTo).put("current_media_custody_event_id", eventId).put("seal_state", seal);
            for (String e : exceptions) CasesRecords.append(next, "custody_exceptions", e);
            Double sim = simTime(c, store);
            return HandlerResult.builder().save(RecordChange.create("media_custody_event", eventId, c.runId(), event), "MEDIA_CUSTODY_APPENDED", sim)
                    .save(RecordChange.next(media, next), "MEDIA_CUSTODY_UPDATED", sim).build();
        }
    }

    @Component @Profile("!stub")
    static final class RequestMediaLab extends CasesHandlers.Handler {
        static final Set<String> KEYS = Set.of("media_id", "method_id", "qc_inputs");
        private final AerosolMethod method;
        RequestMediaLab(ObjectProvider<ClockControl> clock, AerosolMethod method) { super(clock); this.method = method; }
        public String commandType() { return "request_media_lab"; }
        public Set<Role> allowedRoles() { return Set.of(Role.LAB_DEMO); }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "media", "media_id"); }

        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            onlyKeys(p, KEYS);
            StoredRecord media = CasesRecords.sameRun(store, "media", CasesRecords.text(p, "media_id"), c.runId());
            JsonNode m = media.body();
            StoredRecord caseRecord = CasesRecords.mutableCase(store, CasesRecords.text(m, "case_id"), c.runId());
            CasesRecords.require(AerosolMethod.ID.equals(CasesRecords.text(p, "method_id")), "Choose the declared teaching aerosol filter method.");
            CasesRecords.require(AerosolMethod.ID.equals(CasesRecords.text(m, "method_id")) && AerosolMethod.MATERIAL.equals(CasesRecords.text(m, "material")),
                    "The method does not match this media's recorded material and method.");
            CasesRecords.require(CasesRecords.ORIGIN.equals(CasesRecords.text(m, "origin")), "Lab work requires synthetic teaching media.");
            JsonNode qc = p.path("qc_inputs");
            CasesRecords.require(qc.isMissingNode() || qc.isNull() || qc.isObject(), "qc_inputs must be an object of numbers, or null if none were measured.");
            if (qc.isObject()) qc.fieldNames().forEachRemaining(k -> CasesRecords.require(AerosolMath.QC_FIELDS.contains(k),
                    "qc_inputs has an unsupported field: " + k + " (gross_ug is the sulfur-assay mass before blank correction; filter weights are not accepted)."));
            StoredRecord custody = CasesRecords.sameRun(store, "media_custody_event", CasesRecords.text(m, "current_media_custody_event_id"), c.runId());
            CasesRecords.require(media.id().equals(CasesRecords.text(custody.body(), "media_id")), "Current media custody linkage is incomplete or inconsistent.");
            Double analysis = simTime(c, store);
            ObjectNode result = AerosolMath.evaluate(qc.isObject() ? qc : null, m, analysis, method);
            ObjectNode body = CasesRecords.object().put("media_id", media.id()).put("method_id", AerosolMethod.ID).put("origin", CasesRecords.ORIGIN)
                    .put("input_basis", INPUT_BASIS);
            body.set("qc_inputs", qc.isObject() ? qc.deepCopy() : NullNode.getInstance());
            result.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
            if (analysis == null) body.putNull("analysis_sim_time_s"); else body.put("analysis_sim_time_s", analysis);
            body.put("analysis_sim_time_reason", analysis == null ? "CURRENT_RUN_CLOCK_UNAVAILABLE" : null);
            body.set("media_ref", CasesRecords.ref(media));
            body.set("case_ref", CasesRecords.ref(caseRecord));
            body.set("custody_ref", CasesRecords.ref(custody));
            body.set("method_limits", method.limits());
            body.put("analyzed_by", session.actorFor(allowedRoles()));
            var limitations = body.putArray("limitations");
            limitations.add(SYNTHETIC_LIMITATION);
            limitations.add(CAUSATION_LIMITATION);
            if ("LIMITED_BELOW_LOQ".equals(body.path("result_use").asText()))
                limitations.add("The blank-corrected sulfur mass is below the " + method.loqUg().toPlainString() + " ug reporting limit; the result is limited, not zero.");
            for (JsonNode e : m.path("custody_exceptions")) if (e.isTextual()) limitations.add("Custody exception recorded for this media: " + e.textValue() + ".");
            String holder = CasesRecords.text(m, "current_holder");
            if (holder != null && !CasesRecords.isHolder(session, holder))
                limitations.add("The lab request was recorded while custody was with " + holder + ", not this lab session.");

            String resultId = Ids.newId();
            ObjectNode nextCase = CasesRecords.own(caseRecord);
            if (Set.of("INTAKE", "SPECIMEN").contains(nextCase.path("state").asText())) nextCase.put("state", "LAB");
            if (!nextCase.has("media_lab_result_ids")) nextCase.putArray("media_lab_result_ids");
            CasesRecords.append(nextCase, "media_lab_result_ids", resultId);
            return HandlerResult.builder().save(RecordChange.create("media_lab_result", resultId, c.runId(), body), "MEDIA_LAB_RESULT_RECORDED", analysis)
                    .save(RecordChange.next(caseRecord, nextCase), "CASE_MEDIA_LAB_ADDED", analysis).build();
        }
    }

    static ObjectNode custody(String media, String previous, String from, String to, String seal, String eventTime, String recorded,
                              List<String> exceptions, String actor) {
        ObjectNode b = CasesRecords.object().put("media_id", media).put("previous_event_id", previous).put("from_holder", from).put("to_holder", to)
                .put("seal_state", seal).put("event_time_utc", eventTime).put("recorded_utc", recorded).put("recorded_by", actor);
        var a = b.putArray("exceptions"); exceptions.forEach(a::add);
        return b;
    }
}
