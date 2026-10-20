package fuelprint.cases;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.core.Ids;
import fuelprint.core.WallTime;
import fuelprint.core.command.*;
import fuelprint.core.run.Runs;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Intake, separate material specimens, and append-only custody. */
final class CasesHandlers {
    private CasesHandlers() {}
    abstract static class Handler implements CommandHandler {
        private final ObjectProvider<ClockControl> clock;
        Handler(ObjectProvider<ClockControl> clock) { this.clock = clock; }
        public final HandlerOutcome handle(Command c, Session session, StoreView store) {
            try {
                CasesRecords.run(store, c.runId());
                CasesRecords.require(!fuelprint.api.ContactText.contains(c.payload()),
                        "Contact text must not be included in public case evidence.");
                return apply(c, session, store);
            } catch (CasesRecords.Invalid invalid) { return invalid.rejection; }
        }
        abstract HandlerOutcome apply(Command c, Session session, StoreView store);
        /** The current clock cannot date another run. Unknown timing stays null with a reason. */
        final Double simTime(Command c, StoreView store) {
            var current = Runs.current(store.listAll("run")).orElse(null);
            if (current == null || !c.runId().equals(current.id())
                    || !Set.of("RUNNING", "PAUSED").contains(current.body().path("state").asText())) return null;
            double value = clock.getObject().simTimeS();
            return Double.isFinite(value) && value >= 0 ? value : null;
        }
    }

    @Component @Profile("!stub")
    static final class CreateCase extends Handler {
        CreateCase(ObjectProvider<ClockControl> clock) { super(clock); }
        public String commandType() { return "create_case"; }
        public Set<Role> allowedRoles() { return Set.of(Role.CASE_REVIEWER); }
        HandlerOutcome apply(Command c, Session session, StoreView store) {
            String purpose = CasesRecords.requiredText(c.payload(), "purpose");
            List<String> ids = CasesRecords.strings(c.payload().path("screening_record_ids"), "screening_record_ids", false);
            ObjectNode body = CasesRecords.object().put("purpose", purpose).put("state", "INTAKE").put("origin", CasesRecords.ORIGIN);
            var screenings = body.putArray("screening_record_ids");
            var refs = body.putArray("screening_refs");
            var limitations = body.putArray("limitations");
            limitations.add("Synthetic teaching evidence only; air screening does not establish material identity, plume causation or legal suitability.");
            var missions = body.putArray("mission_ids");
            Set<String> seenMissions = new HashSet<>();
            String investigationId = null;
            for (String id : ids) {
                StoredRecord evidence = CasesRecords.sameRun(store, "screening_record", id, c.runId());
                StoredRecord investigation = CasesRecords.investigation(store, evidence);
                CasesRecords.require(investigationId == null || investigationId.equals(investigation.id()),
                        "All screening records must belong to the same investigation.");
                investigationId = investigation.id();
                screenings.add(id); refs.add(CasesRecords.ref(evidence));
                JsonNode b = evidence.body();
                String mission = CasesRecords.text(b, "mission_id");
                if (seenMissions.add(mission)) missions.add(mission);
                String decision = CasesRecords.text(b, "decision");
                if (!"VALID".equals(decision)) limitations.add(id + ": " + decision + " screening retained without promoting its usability.");
                for (String reason : CasesRecords.strings(b.path("reasons"), "screening reasons", true))
                    limitations.add(id + ": " + reason);
            }
            body.put("investigation_id", investigationId);
            return HandlerResult.builder().save(RecordChange.create("case", Ids.newId(), c.runId(), body), "CASE_CREATED", simTime(c, store)).build();
        }
    }

    @Component @Profile("!stub")
    static final class CreateSpecimen extends Handler {
        private final CasesMethod method;
        private final Clock wall;
        CreateSpecimen(ObjectProvider<ClockControl> clock, CasesMethod method, Clock wall) { super(clock); this.method = method; this.wall = wall; }
        public String commandType() { return "create_specimen"; }
        public Set<Role> allowedRoles() { return Set.of(Role.INSPECTOR_DEMO); }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "case", "case_id"); }
        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            StoredRecord caseRecord = CasesRecords.caseForSpecimen(store, CasesRecords.text(p, "case_id"), c.runId());
            CasesRecords.require(CasesRecords.ORIGIN.equals(CasesRecords.text(caseRecord.body(), "origin")), "Specimens require a synthetic teaching case.");
            method.validate(p);
            String site = CasesRecords.requiredText(p, "site_id");
            CasesRecords.require(site.matches("S0[1-6]"), "site_id must be a fictional teaching site S01 to S06.");
            String container = CasesRecords.requiredText(p, "container"), seal = CasesRecords.requiredText(p, "seal_id");
            String specimenId = Ids.newId(), eventId = Ids.newId();
            String holder = session.actorFor(allowedRoles()), now = WallTime.now(wall);
            Double sim = simTime(c, store);
            ObjectNode specimen = CasesRecords.object().put("case_id", caseRecord.id()).put("site_id", site)
                    .put("material", CasesRecords.text(p, "material")).put("method_id", method.id()).put("container", container)
                    .put("seal_id", seal).put("authority_basis", "INSPECTOR_DEMO")
                    .put("origin", CasesRecords.ORIGIN).put("collector_id", holder).put("current_holder", holder)
                    .put("current_custody_event_id", eventId).put("seal_state", "INTACT").put("sealed_utc", now)
                    .put("sealed_sim_time_s", sim).put("sealed_sim_time_reason", sim == null ? "CURRENT_RUN_CLOCK_UNAVAILABLE" : null);
            specimen.set("mass_mg", p.path("mass_mg").deepCopy());
            specimen.putArray("custody_exceptions");
            ObjectNode custody = custody(specimenId, null, holder, holder, "INTACT", now, now, List.of(), session.actorFor(allowedRoles()));
            ObjectNode nextCase = CasesRecords.own(caseRecord);
            if ("INTAKE".equals(nextCase.path("state").asText())) nextCase.put("state", "SPECIMEN");
            if (!nextCase.has("specimen_ids")) nextCase.putArray("specimen_ids");
            CasesRecords.append(nextCase, "specimen_ids", specimenId);
            return HandlerResult.builder().save(RecordChange.create("specimen", specimenId, c.runId(), specimen), "SPECIMEN_CREATED", sim)
                    .save(RecordChange.create("custody_event", eventId, c.runId(), custody), "CUSTODY_STARTED", sim)
                    .save(RecordChange.next(caseRecord, nextCase), "CASE_SPECIMEN_ADDED", sim).build();
        }
    }

    @Component @Profile("!stub")
    static final class AppendCustody extends Handler {
        private final Clock wall;
        AppendCustody(ObjectProvider<ClockControl> clock, Clock wall) { super(clock); this.wall = wall; }
        public String commandType() { return "append_custody"; }
        public Set<Role> allowedRoles() { return CasesRecords.CUSTODIANS; }
        public RecordKey revisionSubject(Command c) { return CasesRecords.subject(c, "specimen", "specimen_id"); }
        HandlerOutcome apply(Command c, Session session, StoreView store) {
            JsonNode p = c.payload();
            CasesRecords.require(!p.has("previous_event_id") && !p.has("corrects_event_id"),
                    "Corrections refer to the immediately previous custody event; arbitrary correction targets are not supported.");
            StoredRecord specimen = CasesRecords.sameRun(store, "specimen", CasesRecords.text(p, "specimen_id"), c.runId());
            CasesRecords.mutableCase(store, CasesRecords.text(specimen.body(), "case_id"), c.runId());
            String holder = CasesRecords.requiredText(specimen.body(), "current_holder");
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
            StoredRecord previous = CasesRecords.sameRun(store, "custody_event", CasesRecords.text(specimen.body(), "current_custody_event_id"), c.runId());
            CasesRecords.require(specimen.id().equals(CasesRecords.text(previous.body(), "specimen_id"))
                    && holder.equals(CasesRecords.text(previous.body(), "to_holder")), "Current custody linkage is incomplete or inconsistent.");
            // Absence/refusal documents a failed handoff. A correction documents the previous event;
            // changing the holder needs its own transfer event.
            boolean noTransfer = exceptions.contains("RECIPIENT_ABSENT") || exceptions.contains("REFUSED") || exceptions.contains("CORRECTION");
            if (exceptions.contains("CORRECTION")) CasesRecords.require(holder.equals(to), "A correction cannot silently transfer custody.");
            String actualTo = noTransfer ? holder : to;
            String eventId = Ids.newId(), now = WallTime.now(wall);
            ObjectNode event = custody(specimen.id(), previous.id(), holder, actualTo, seal, eventTime, now, exceptions, session.actorFor(allowedRoles()));
            if (noTransfer && !holder.equals(to)) event.put("attempted_to_holder", to);
            ObjectNode next = CasesRecords.own(specimen).put("current_holder", actualTo).put("current_custody_event_id", eventId).put("seal_state", seal);
            for (String exception : exceptions) CasesRecords.append(next, "custody_exceptions", exception);
            Double sim = simTime(c, store);
            return HandlerResult.builder().save(RecordChange.create("custody_event", eventId, c.runId(), event), "CUSTODY_APPENDED", sim)
                    .save(RecordChange.next(specimen, next), "SPECIMEN_CUSTODY_UPDATED", sim).build();
        }
    }
    private static ObjectNode custody(String specimen, String previous, String from, String to, String seal,
                                      String eventTime, String recorded, List<String> exceptions, String actor) {
        ObjectNode body = CasesRecords.object().put("specimen_id", specimen).put("previous_event_id", previous)
                .put("from_holder", from).put("to_holder", to).put("seal_state", seal).put("event_time_utc", eventTime)
                .put("recorded_utc", recorded).put("recorded_by", actor);
        var array = body.putArray("exceptions"); exceptions.forEach(array::add);
        return body;
    }
}
