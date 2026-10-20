package fuelprint.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** One atomic analysis/proposal revision built from public inputs and public science services. */
@Component
@Profile("!stub")
final class AnalysisHandler implements CommandHandler {

    private final Ranker ranker;
    private final Planner planner;
    private final ObjectProvider<LearnedRanker> learned;
    private final WorldClock world;
    private final ObjectProvider<ClockControl> clock;
    private final VehicleSource vehicles;
    private final ObjectMapper mapper;

    AnalysisHandler(Ranker ranker, Planner planner, ObjectProvider<LearnedRanker> learned, WorldClock world,
                    ObjectProvider<ClockControl> clock, VehicleSource vehicles, ObjectMapper mapper) {
        this.ranker = ranker;
        this.planner = planner;
        this.learned = learned;
        this.world = world;
        this.clock = clock;
        this.vehicles = vehicles;
        this.mapper = mapper;
    }

    @Override
    public String commandType() {
        return "run_analysis";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.AUTOMATION, Role.ANALYST);
    }

    @Override
    public RecordKey revisionSubject(Command command) {
        String id = OpsRecords.text(command.payload(), "investigation_id");
        return Ids.isUuid(id) ? new RecordKey("investigation", id) : null;
    }

    @Override
    public HandlerOutcome handle(Command command, Session session, StoreView store) {
        Rejection problem = OpsRecords.runProblem(command, store);
        if (problem != null) return problem;
        StoredRecord investigation = OpsRecords.sameRun(store, "investigation",
                OpsRecords.text(command.payload(), "investigation_id"), command.runId());
        if (investigation == null) return Rejection.invalid("investigation_id must name an investigation in this run.");
        if ("CLOSED".equals(investigation.body().path("state").asText())) {
            return new Rejection("WRONG_STATE", "This investigation is closed.");
        }
        if (!OpsRecords.validCause(command, store, investigation)) {
            return Rejection.invalid("The analysis cause must be an exact same-run linked revision.");
        }
        WindNow wind = world.wind();
        double now = clock.getObject().simTimeS();
        ObjectNode basis = OpsRecords.basis(store, investigation, mapper.valueToTree(wind));
        if (session.isAutomation() && OpsRecords.sameBasis(basis, investigation.body().path("analysis_basis"))) {
            return HandlerResult.nothing();
        }

        List<Window> windows = new ArrayList<>();
        try {
            for (StoredRecord record : store.list("window", command.runId())) {
                ObjectNode body = OpsRecords.own(record);
                // Window's primitive numbers must never default missing/unknown values to zero.
                for (String field : List.of("start_s", "end_s", "valid_seconds")) {
                    if (!body.path(field).isNumber() || !Double.isFinite(body.path(field).asDouble())) {
                        return Rejection.invalid("A recorded window has an unknown " + field + "; no analysis was saved.");
                    }
                }
                if (OpsRecords.text(body, "sensor_or_vehicle_id") == null || OpsRecords.text(body, "channel") == null) {
                    return Rejection.invalid("A recorded window needs its public sensor/vehicle and channel.");
                }
                body.put("id", record.id());
                windows.add(mapper.treeToValue(body, Window.class));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return Rejection.invalid("A recorded public window is malformed; no analysis was saved.");
        }

        AnalysisInput input = new AnalysisInput(command.runId(), investigation.id(), now, windows, wind);
        Analysis physics = ranker.analyze(input);
        Analysis combined;
        String failure = null;
        boolean nothingToOrder = physics.outcome() == Outcome.INVALID || physics.outcome() == Outcome.NO_SIGNAL;
        try {
            LearnedRanker model = learned.getIfAvailable();
            LearnedOrder order = nothingToOrder ? LearnedOrder.none(LearnedStatus.NOT_APPLICABLE, null, null)
                    : model == null ? LearnedOrder.none(LearnedStatus.MODEL_MISSING, null, null)
                    : model.order(input, physics);
            combined = physics.withLearned(order);
        } catch (RuntimeException e) {
            // Separate diagnostic: every physics limitation and candidate status stays exactly as returned.
            failure = "LEARNED_INVOCATION_FAILED";
            combined = physics.withLearned(LearnedOrder.none(LearnedStatus.FAILED, null, null));
        }

        // Reserve the identity used by the planner, then commit all three records atomically through the bus.
        String analysisId = Ids.newId();
        Proposal proposal = planner.plan(combined,
                new PlanContext(command.runId(), analysisId, now, wind, vehicles.vehicles()));
        if (proposal == null || !analysisId.equals(proposal.analysisId())) {
            return Rejection.invalid("The planner did not return this analysis identity.");
        }
        if (proposal.result() == ProposalResult.PENDING
                && (proposal.bestActionId() != null || proposal.cheaperActionId() != null)) {
            return Rejection.invalid("A pending plan cannot recommend an action.");
        }
        // Presentation only, after planning: planner/science receive the original complete result.
        ObjectNode analysis = mapper.valueToTree(fuelprint.ai.DemoRanking.orderForDemo(combined));
        analysis.put("investigation_id", investigation.id()).put("sim_time_s", now);
        analysis.set("wind", mapper.valueToTree(wind));
        analysis.set("investigation_ref", OpsRecords.ref(investigation));
        analysis.set("input_window_refs", basis.path("windows"));
        analysis.set("screening_record_refs", basis.path("screening_records"));
        analysis.set("cause", command.payload().has("cause") ? command.payload().get("cause") : OpsRecords.ref(investigation));
        analysis.put("learned_failure_reason", failure);
        ObjectNode plan = mapper.valueToTree(proposal);
        plan.put("investigation_id", investigation.id());
        ObjectNode next = OpsRecords.own(investigation);
        OpsRecords.append(next, "analysis_ids", analysisId);
        List<StoredRecord> evidence = OpsRecords.evidence(store, investigation);
        evidence.forEach(record -> OpsRecords.append(next, "screening_record_ids", record.id()));
        next.put("state", evidence.isEmpty() ? "ANALYSED" : "EVIDENCE_BACK");
        next.set("analysis_basis", basis);
        return HandlerResult.builder()
                .save(RecordChange.create("analysis", analysisId, command.runId(), analysis), "ANALYSIS_SAVED", now)
                .save(RecordChange.create("proposal", Ids.newId(), command.runId(), plan), "PROPOSAL_SAVED", now)
                .save(RecordChange.next(investigation, next), evidence.isEmpty() ? "INVESTIGATION_ANALYSED" : "EVIDENCE_BACK", now)
                .build();
    }
}
