package fuelprint.ops;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Operations owns proposal and human authority; Field owns flight states. */
final class MissionHandlers {
    private MissionHandlers() {}
    static final Set<String> ACTIVE = Set.of("APPROVED", "LEASED", "OUTBOUND", "AT_TARGET", "COLLECTING", "RETURNING");

    @Component @Profile("!stub")
    static class Support {
        final WorldClock world;
        final ObjectProvider<ClockControl> clock;
        final VehicleSource vehicles;
        final ObjectProvider<AutomationSettings> settings;
        final ObjectMapper mapper;
        Support(WorldClock world, ObjectProvider<ClockControl> clock, VehicleSource vehicles,
                ObjectProvider<AutomationSettings> settings, ObjectMapper mapper) {
            this.world=world; this.clock=clock; this.vehicles=vehicles; this.settings=settings; this.mapper=mapper;
        }
        double now() {
            double now=clock.getObject().simTimeS();
            if (!Double.isFinite(now) || now < 0) throw new IllegalArgumentException("Simulation time is unknown.");
            return now;
        }
        String staleReason(StoreView store, StoredRecord mission, double now) {
            JsonNode expiry=mission.body().path("expires_s");
            if (!expiry.isNumber() || !Double.isFinite(expiry.doubleValue())) return "NEEDS_NEW_APPROVAL";
            if (now >= expiry.doubleValue()) return "TIMED_OUT";
            try {
                String current=MissionDependencies.hash(MissionDependencies.current(store, OpsRecords.own(mission), mission.runId(), world.wind()));
                return current.equals(OpsRecords.text(mission.body(), "dependency_hash")) ? null : "NEEDS_NEW_APPROVAL";
            } catch (IllegalArgumentException e) { return "NEEDS_NEW_APPROVAL"; }
        }
        /** Shared by human approval and Field's command-thread initial-departure port. */
        Rejection approvalAuthorityProblem(StoreView store, StoredRecord mission, double now) {
            StoredRecord run = OpsRecords.sameRun(store, "run", mission.runId(), mission.runId());
            if (run == null) return Rejection.invalid("A recorded run_id is required.");
            if (!Set.of("RUNNING", "PAUSED").contains(run.body().path("state").asText()))
                return new Rejection("WRONG_STATE", "This run has ended; start or select the current run.");
            String reason = staleReason(store, mission, now);
            return reason == null ? null : new Rejection(reason,
                    "This approval has expired or its public dependencies changed; propose it again.");
        }
        VehicleState available(StoreView store, String run, String vehicleId, SurveyCatalog.Action action) {
            return vehicles.vehicles().stream().filter(v -> vehicleId == null || vehicleId.equals(v.vehicleId()))
                    .filter(v -> SurveyCatalog.available(v, action))
                    .filter(v -> store.list("mission",run).stream().noneMatch(m -> ACTIVE.contains(m.body().path("state").asText())
                            && v.vehicleId().equals(OpsRecords.text(m.body(),"vehicle_id"))))
                    .findFirst().orElse(null);
        }
    }

    /** Durable auto-proposal consumption includes rejected/expired/completed missions. Human retries use the bus receipt. */
    static boolean proposalConsumed(StoreView store, String run, String proposalId) {
        return store.list("mission", run).stream().anyMatch(m -> proposalId.equals(OpsRecords.text(m.body(), "proposal_id")));
    }

    static RecordKey missionSubject(Command c) {
        String id=OpsRecords.text(c.payload(),"mission_id"); return Ids.isUuid(id)?new RecordKey("mission",id):null;
    }
    static Rejection wrongState() { return new Rejection("WRONG_STATE", "This mission is no longer in the required state."); }

    @Component @Profile("!stub")
    static class Propose implements CommandHandler {
        private final Support support;
        Propose(Support support) {this.support=support;}
        public String commandType(){return "propose_mission";}
        public Set<Role> allowedRoles(){return Set.of(Role.ANALYST,Role.AUTOMATION);}
        public RecordKey revisionSubject(Command c){
            String key=c.payload().hasNonNull("survey_id")?"survey_id":"proposal_id";
            String id=OpsRecords.text(c.payload(),key);return Ids.isUuid(id)?new RecordKey(key.equals("survey_id")?"survey":"proposal",id):null;
        }
        public HandlerOutcome handle(Command c,Session session,StoreView store){
            Rejection run=OpsRecords.runProblem(c,store);if(run!=null)return run;
            try {
                ObjectNode payload=c.payload(); boolean survey=payload.hasNonNull("survey_id");
                if (survey == payload.hasNonNull("proposal_id")) return Rejection.invalid("Choose exactly one proposal_id or survey_id.");
                if (session.isAutomation() && !support.settings.getObject().isOn(AutomationSettings.AUTO_PROPOSE))
                    return new Rejection("AUTOMATION_DISABLED","Automatic mission proposing is switched off.");
                if (session.isAutomation() && survey) return new Rejection("NOT_BEST_ACTION","Only a planner best_action_id may be proposed automatically.");
                StoredRecord investigation=MissionDependencies.investigation(store,payload,c.runId());
                StoredRecord basis=OpsRecords.sameRun(store,survey?"survey":"proposal",OpsRecords.text(payload,survey?"survey_id":"proposal_id"),c.runId());
                String actionId=OpsRecords.text(payload,"action_id");
                ObjectNode mission=OpsRecords.object().put(survey?"survey_id":"proposal_id",basis.id()).put("action_id",actionId);
                JsonNode target,route=null,channels; String method,kind,vehicleId=null;
                if (survey) {
                    SurveyCatalog.Action action=SurveyCatalog.find(actionId).orElse(null);
                    if (action==null || !actionId.equals(OpsRecords.text(basis.body(),"action_id")))
                        return Rejection.invalid("Choose the action recorded by this public survey.");
                    target=basis.body().get("target"); method=OpsRecords.text(basis.body(),"method_id");
                    channels=basis.body().get("channel_ids");kind=OpsRecords.text(basis.body(),"vehicle_kind");
                    vehicleId=OpsRecords.text(basis.body(),"vehicle_id");
                    if (!Ids.isUuid(vehicleId)) return Rejection.invalid("The survey vehicle is unknown.");
                } else {
                    String result=OpsRecords.text(basis.body(),"result");
                    if (!"BEST_ACTION".equals(result)) return new Rejection("PENDING".equals(result)?"PENDING":"NO_USEFUL_MEASUREMENT","This plan has no useful recommended mission.");
                    if (session.isAutomation() && !Objects.equals(actionId,OpsRecords.text(basis.body(),"best_action_id")))
                        return new Rejection("NOT_BEST_ACTION","Automation may propose only best_action_id.");
                    JsonNode action=null;
                    for (JsonNode row:basis.body().path("actions")) if (Objects.equals(actionId,OpsRecords.text(row,"action_id"))) {action=row;break;}
                    if (action==null) return Rejection.invalid("action_id must name an action in this proposal.");
                    if (!action.path("feasible").isBoolean() || !action.path("feasible").booleanValue())
                        return new Rejection("NOT_FEASIBLE","This action is not feasible: "+action.path("rejection_reason").asText("No eligible measurement."));
                    target=action.get("target");kind=OpsRecords.text(action,"vehicle");
                    // The public ProposalAction has a target, not private route/procedure details.
                    // Its declared waypoint uses Field's normal safe-path planning; this teaching method is public.
                    method=action.hasNonNull("method_id")?OpsRecords.text(action,"method_id"):"TEACH-NODE-ONLINE-2";
                    channels=action.hasNonNull("channel_ids")?action.get("channel_ids"):support.mapper.valueToTree(List.of("SO2_ppb","CO2_ppm"));
                    route=action.has("route")?action.get("route"):basis.body().get("route");
                }
                if (session.isAutomation() && proposalConsumed(store, c.runId(), basis.id())) return HandlerResult.nothing();
                MissionDependencies.point(target);
                if (payload.hasNonNull("vehicle_id")) {
                    String requested=OpsRecords.text(payload,"vehicle_id");
                    if (!Ids.isUuid(requested) || (vehicleId!=null&&!vehicleId.equals(requested)))
                        return new Rejection("VEHICLE_UNAVAILABLE","The requested public vehicle is unavailable for this action.");
                    vehicleId=requested;
                }
                boolean pending=store.list("mission",c.runId()).stream().anyMatch(m -> Set.of("PROPOSED","APPROVED").contains(m.body().path("state").asText())
                        && Objects.equals(OpsRecords.investigationForMission(store,m),investigation));
                if (pending) return session.isAutomation()?HandlerResult.nothing():new Rejection("MISSION_PENDING","This investigation already has a proposed or approved mission.");
                var action=new SurveyCatalog.Action(actionId,support.mapper.treeToValue(target,Enu.class),method,OpsRecords.strings(channels),kind);
                VehicleState vehicle=support.available(store,c.runId(),vehicleId,action);
                if(vehicle==null)return new Rejection("VEHICLE_UNAVAILABLE","No available public vehicle can carry out this action.");
                mission.put("vehicle_id",vehicle.vehicleId()).put("method_id",method).put("state","PROPOSED").put("pilot","NONE");
                mission.set("target",target);mission.set("channel_ids",channels);
                if(route==null){var declared=mission.putArray("route");declared.add(target);}else mission.set("route",route);
                double now=support.now();mission.put("proposed_s",now).put("expires_s",now+300);
                ObjectNode dependencies=MissionDependencies.current(store,mission,c.runId(),support.world.wind());
                mission.set("dependencies",dependencies);mission.put("dependency_hash",MissionDependencies.hash(dependencies));
                ObjectNode next=OpsRecords.own(investigation).put("state","MISSION_PROPOSED");
                return HandlerResult.builder().save(RecordChange.create("mission",Ids.newId(),c.runId(),mission),"MISSION_PROPOSED",now)
                        .save(RecordChange.next(investigation,next),"MISSION_PROPOSED",now).build();
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {return new Rejection("STALE_DEPENDENCY",e.getMessage());}
        }
    }

    @Component @Profile("!stub")
    static class Approve implements CommandHandler {
        final Support support; Approve(Support support){this.support=support;}
        public String commandType(){return "approve_mission";}
        public Set<Role> allowedRoles(){return Set.of(Role.SUPERVISOR);}
        public RecordKey revisionSubject(Command c){return missionSubject(c);}
        public HandlerOutcome handle(Command c,Session s,StoreView store){
            Rejection run=OpsRecords.runProblem(c,store);if(run!=null)return run;
            StoredRecord mission=OpsRecords.sameRun(store,"mission",OpsRecords.text(c.payload(),"mission_id"),c.runId());
            if(mission==null)return Rejection.invalid("mission_id must name a mission in this run.");
            if(!"PROPOSED".equals(mission.body().path("state").asText())
                    || !"NONE".equals(mission.body().path("pilot").asText()))return wrongState();
            try {
                double now=support.now();Rejection authority=support.approvalAuthorityProblem(store,mission,now);
                if(authority!=null)return authority;
                String supplied=OpsRecords.text(c.payload(),"dependency_hash");
                if(supplied==null||!supplied.equals(OpsRecords.text(mission.body(),"dependency_hash")))
                    return new Rejection("STALE_DEPENDENCY","The supplied dependency hash does not match this mission.");
                ObjectNode body=OpsRecords.own(mission);
                var action=new SurveyCatalog.Action(body.path("action_id").asText(),support.mapper.treeToValue(body.path("target"),Enu.class),body.path("method_id").asText(),OpsRecords.strings(body.path("channel_ids")),
                        support.vehicles.vehicles().stream().filter(v->v.vehicleId().equals(body.path("vehicle_id").asText())).map(VehicleState::kind).findFirst().orElse("UNKNOWN"));
                if(support.available(store,c.runId(),body.path("vehicle_id").asText(),action)==null)
                    return new Rejection("VEHICLE_UNAVAILABLE","The public vehicle is no longer available.");
                body.put("state","APPROVED").put("approved_s",now).put("approved_by",s.actorLabel());
                StoredRecord investigation=MissionDependencies.investigation(store,body,c.runId());
                return HandlerResult.builder().save(RecordChange.next(mission,body),"MISSION_APPROVED",now)
                        .save(RecordChange.next(investigation,OpsRecords.own(investigation).put("state","MISSION_APPROVED")),"MISSION_APPROVED",now).build();
            }catch(IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e){return new Rejection("STALE_DEPENDENCY",e.getMessage());}
        }
    }

    @Component @Profile("!stub")
    static class Reject implements CommandHandler {
        final Support support;Reject(Support support){this.support=support;}
        public String commandType(){return "reject_mission";}
        public Set<Role> allowedRoles(){return Set.of(Role.SUPERVISOR);}
        public RecordKey revisionSubject(Command c){return missionSubject(c);}
        public HandlerOutcome handle(Command c,Session s,StoreView store){
            Rejection run=OpsRecords.runProblem(c,store);if(run!=null)return run;
            StoredRecord mission=OpsRecords.sameRun(store,"mission",OpsRecords.text(c.payload(),"mission_id"),c.runId());
            if(mission==null)return Rejection.invalid("mission_id must name a mission in this run.");
            if(!"PROPOSED".equals(mission.body().path("state").asText()))return wrongState();
            String reason=OpsRecords.text(c.payload(),"reason");if(reason==null||reason.isBlank())return Rejection.invalid("Give a reason for rejecting the mission.");
            if(fuelprint.api.ContactText.contains(c.payload()))return Rejection.invalid("Contact text must not be included in public mission evidence.");
            return HandlerResult.builder().save(RecordChange.next(mission,OpsRecords.own(mission).put("state","REJECTED").put("reason",reason)),"MISSION_REJECTED",support.now()).build();
        }
    }

    @Component @Profile("!stub")
    static class Expire implements CommandHandler {
        final Support support;Expire(Support support){this.support=support;}
        public String commandType(){return "expire_mission";}
        public Set<Role> allowedRoles(){return Set.of(Role.AUTOMATION);}
        public RecordKey revisionSubject(Command c){return missionSubject(c);}
        public HandlerOutcome handle(Command c,Session s,StoreView store){
            Rejection run=OpsRecords.runProblem(c,store);if(run!=null)return run;
            StoredRecord mission=OpsRecords.sameRun(store,"mission",OpsRecords.text(c.payload(),"mission_id"),c.runId());
            if(mission==null)return Rejection.invalid("mission_id must name a mission in this run.");
            if(!"APPROVED".equals(mission.body().path("state").asText())||!"NONE".equals(mission.body().path("pilot").asText())
                    ||store.list("lease",c.runId()).stream().anyMatch(l->mission.id().equals(OpsRecords.text(l.body(),"mission_id"))&&"ACTIVE".equals(l.body().path("state").asText())))return wrongState();
            String supplied=OpsRecords.text(c.payload(),"reason");
            if(!Set.of("NEEDS_NEW_APPROVAL","TIMED_OUT").contains(supplied==null?"":supplied))return Rejection.invalid("Choose NEEDS_NEW_APPROVAL or TIMED_OUT.");
            double now=support.now();String actual=support.staleReason(store,mission,now);
            if(!supplied.equals(actual))return new Rejection("DEPENDENCIES_CURRENT","The supplied expiry reason does not hold for the current mission.");
            return HandlerResult.builder().save(RecordChange.next(mission,OpsRecords.own(mission).put("state","EXPIRED").put("reason",actual)),"MISSION_EXPIRED",now).build();
        }
    }
}
