package fuelprint.ops;

import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AlertAreas;
import fuelprint.api.ClockControl;
import fuelprint.api.VehicleSource;
import fuelprint.api.WorldClock;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

final class InvestigationHandlers {
    private InvestigationHandlers() {}
    @Component @Profile("!stub")
    static class OpenInvestigation implements CommandHandler {
        private final AlertAreas areas; private final WorldClock world; private final ObjectProvider<ClockControl> clock;
        OpenInvestigation(AlertAreas areas,WorldClock world,ObjectProvider<ClockControl> clock){this.areas=areas;this.world=world;this.clock=clock;}
        public String commandType(){return "open_investigation";}
        public Set<Role> allowedRoles(){return Set.of(Role.AUTOMATION);}
        public RecordKey revisionSubject(Command c){String id=OpsRecords.text(c.payload(),"trigger_id");return Ids.isUuid(id)?new RecordKey("trigger",id):null;}
        public HandlerOutcome handle(Command c,Session session,StoreView store){
            Rejection problem=OpsRecords.runProblem(c,store); if(problem!=null)return problem;
            StoredRecord trigger=OpsRecords.sameRun(store,"trigger",OpsRecords.text(c.payload(),"trigger_id"),c.runId());
            if(trigger==null)return Rejection.invalid("trigger_id must name a trigger in this run.");
            if (OpsRecords.triggerConsumed(store, c.runId(), trigger.id())) return HandlerResult.nothing();
            String sensor=OpsRecords.text(trigger.body(),"sensor");
            if(sensor==null||store.list("sensor",c.runId()).stream().noneMatch(r->sensor.equals(r.id())||sensor.equals(r.body().path("label").asText())))
                return Rejection.invalid("The trigger must name a recorded sensor in this run.");
            StoredRecord active=OpsRecords.active(store,c.runId());
            ObjectNode body=active==null?OpsRecords.freshInvestigation():OpsRecords.own(active);
            if(body.path("trigger_id").isNull())body.put("trigger_id",trigger.id());
            OpsRecords.append(body,"trigger_ids",trigger.id());
            String label=store.list("sensor",c.runId()).stream().filter(r->sensor.equals(r.id())||sensor.equals(r.body().path("label").asText())).findFirst().orElseThrow().body().path("label").asText();
            areas.downwindOf(label,world.wind()).forEach(area->OpsRecords.append(body,"warned_area_ids",area));
            body.put("state","OPEN");
            body.set("intake_cause",OpsRecords.ref(trigger));
            var change=active==null?RecordChange.create("investigation",Ids.newId(),c.runId(),body):RecordChange.next(active,body);
            return HandlerResult.builder().save(change,active==null?"INVESTIGATION_OPENED":"TRIGGER_ATTACHED",clock.getObject().simTimeS()).build();
        }
    }
    @Component @Profile("!stub")
    static class ScheduleSurvey implements CommandHandler {
        private final VehicleSource vehicles; private final ObjectProvider<ClockControl> clock; private final ObjectMapper mapper;
        ScheduleSurvey(VehicleSource vehicles,ObjectProvider<ClockControl> clock,ObjectMapper mapper){this.vehicles=vehicles;this.clock=clock;this.mapper=mapper;}
        public String commandType(){return "schedule_survey";}
        public Set<Role> allowedRoles(){return Set.of(Role.ANALYST);}
        public HandlerOutcome handle(Command c,Session session,StoreView store){
            Rejection problem=OpsRecords.runProblem(c,store); if(problem!=null)return problem;
            String reason=OpsRecords.text(c.payload(),"reason");
            if(reason==null||reason.isBlank())return Rejection.invalid("Give a reason for the survey.");
            if(fuelprint.api.ContactText.contains(c.payload()))return Rejection.invalid("Contact text must not be included in public survey evidence.");
            var action=SurveyCatalog.find(OpsRecords.text(c.payload(),"action_id")).orElse(null);
            if(action==null)return Rejection.invalid("Choose a public survey action_id.");
            String anomalyId=OpsRecords.text(c.payload(),"anomaly_id");
            if(c.payload().hasNonNull("anomaly_id")&&OpsRecords.sameRun(store,"anomaly",anomalyId,c.runId())==null)
                return Rejection.invalid("anomaly_id must name an anomaly in this run.");
            var vehicle=vehicles.vehicles().stream().filter(v->SurveyCatalog.available(v,action)).findFirst().orElse(null);
            if(vehicle==null)return new Rejection("VEHICLE_UNAVAILABLE","No available public vehicle can carry out this survey.");
            StoredRecord active=OpsRecords.active(store,c.runId());
            String investigationId=active==null?Ids.newId():active.id(),surveyId=Ids.newId();
            ObjectNode survey=OpsRecords.object().put("investigation_id",investigationId).put("action_id",action.id())
                    .put("reason",reason).put("anomaly_id",anomalyId).put("vehicle_id",vehicle.vehicleId())
                    .put("method_id",action.methodId()).put("vehicle_kind",action.vehicleKind());
            survey.set("target",mapper.valueToTree(action.target())); survey.set("channel_ids",mapper.valueToTree(action.channelIds()));
            ObjectNode body=active==null?OpsRecords.freshInvestigation():OpsRecords.own(active);
            if(body.path("survey_id").isNull())body.put("survey_id",surveyId);
            OpsRecords.append(body,"survey_ids",surveyId); body.put("state","OPEN");
            body.set("intake_cause",OpsRecords.object().put("type","survey").put("id",surveyId).put("revision",1));
            var change=active==null?RecordChange.create("investigation",investigationId,c.runId(),body):RecordChange.next(active,body);
            return HandlerResult.builder().save(RecordChange.create("survey",surveyId,c.runId(),survey),"SURVEY_SCHEDULED",clock.getObject().simTimeS())
                    .save(change,active==null?"INVESTIGATION_OPENED":"SURVEY_ATTACHED",clock.getObject().simTimeS()).build();
        }
    }
}
