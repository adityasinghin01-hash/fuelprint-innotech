package fuelprint.ops;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ClockControl;
import fuelprint.core.Ids;
import fuelprint.core.command.*;
import fuelprint.core.run.Runs;
import fuelprint.core.store.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Human board decision pinned to the exact case/finding; Alerts reacts only after commit. */
@Component @Profile("!stub")
final class BoardHandler implements CommandHandler {
    private static final Set<String> DECISIONS=Set.of("MORE_INVESTIGATION","NO_SUPPORTED_SOURCE","CANDIDATE_SUPPORTED_SIMULATED","SAMPLE_INVALID");
    private static final Pattern SOURCE=Pattern.compile("(?i)S0[1-6]");
    private final Set<String> areas;
    private final ObjectProvider<ClockControl> clock;

    BoardHandler(ObjectMapper mapper,ObjectProvider<ClockControl> clock)throws IOException {
        this.clock=clock;
        Set<String> known=new HashSet<>();
        try(var input=new ClassPathResource("reference/alert-areas.json").getInputStream()) {
            for(JsonNode area:mapper.readTree(input).path("areas")) {
                if(!area.path("id").isTextual() || area.path("id").asText().isBlank())throw new IllegalStateException("Invalid packaged alert area.");
                known.add(area.path("id").textValue());
            }
        }
        if(known.isEmpty())throw new IllegalStateException("The public alert-area catalogue is unavailable.");
        areas=Set.copyOf(known);
    }
    public String commandType(){return "record_board_decision";}
    public Set<Role> allowedRoles(){return Set.of(Role.BOARD);}
    // Rejected free text can contain private data too; keep it out of the durable command payload.
    public Set<String> privatePayloadFields(){return Set.of("rationale","notice");}
    public RecordKey revisionSubject(Command c){
        String id=c.payload()==null?null:OpsRecords.text(c.payload(),"case_id");
        return Ids.isUuid(id)?new RecordKey("case",id):null;
    }
    public HandlerOutcome handle(Command command,Session session,StoreView store){
        JsonNode p=command.payload();
        if(p==null)return Rejection.invalid("A case, decision, rationale and notice are required.");
        StoredRecord c=OpsRecords.sameRun(store,"case",OpsRecords.text(p,"case_id"),command.runId());
        if(c==null || OpsRecords.sameRun(store,"run",command.runId(),command.runId())==null)
            return Rejection.invalid("The case and run must exist together.");
        if(!Set.of("FINDING","SEALED").contains(c.body().path("state").asText()))
            return new Rejection("WRONG_STATE","The case needs a human finding before a board decision.");
        JsonNode pin=c.body().path("finding_ref");
        if(!pin.isObject() || pin.size()!=3 || !"finding".equals(pin.path("type").asText())
                || !Ids.isUuid(OpsRecords.text(pin,"id")) || !pin.path("revision").isIntegralNumber()
                || !pin.path("revision").canConvertToLong() || pin.path("revision").longValue()<1)
            return Rejection.invalid("The case needs an exact saved finding reference.");
        StoredRecord finding=store.get("finding",pin.path("id").textValue(),pin.path("revision").longValue())
                .filter(r->Objects.equals(command.runId(),r.runId()) && c.id().equals(r.body().path("case_id").asText())).orElse(null);
        if(finding==null)return Rejection.invalid("The exact finding must belong to this case and run.");
        String decision=OpsRecords.text(p,"decision"),rationale=OpsRecords.text(p,"rationale");
        if(decision==null || !DECISIONS.contains(decision))return Rejection.invalid("Choose one of the four declared board decisions.");
        if(!safeText(rationale,4096,false))return Rejection.invalid("A nonempty public rationale without private phone data is required.");
        JsonNode input=p.path("notice");
        if(!input.isObject())return Rejection.invalid("The notice needs area text, interval, status text and public area ids.");
        Set<String> allowed=Set.of("area","area_text","interval","status_text","area_ids");
        var keys=input.fieldNames();while(keys.hasNext())if(!allowed.contains(keys.next()))return Rejection.invalid("The notice contains an unsupported field.");
        String area=OpsRecords.text(input,"area"),alias=OpsRecords.text(input,"area_text");
        if(area==null)area=alias;
        if(area!=null && alias!=null && !area.equals(alias))return Rejection.invalid("Area text aliases must agree.");
        String interval=OpsRecords.text(input,"interval"),status=OpsRecords.text(input,"status_text");
        if(!safeText(area,512,true) || !safeText(interval,512,true) || !safeText(status,1024,true))
            return Rejection.invalid("Notice text is required and must not name a source or contain a phone number.");
        JsonNode ids=input.get("area_ids");
        if(ids==null){
            StoredRecord investigation=OpsRecords.sameRun(store,"investigation",OpsRecords.text(c.body(),"investigation_id"),command.runId());
            if(investigation==null)return Rejection.invalid("Provide explicit public area ids when no investigation warning areas are recorded.");
            ids=investigation.body().path("warned_area_ids");
        }
        if(!ids.isArray())return Rejection.invalid("area_ids must be a list of known public alert areas.");
        Set<String> selected=new LinkedHashSet<>();
        for(JsonNode id:ids){
            if(!id.isTextual() || !areas.contains(id.textValue()))return Rejection.invalid("Every notice area must exist in the public catalogue.");
            selected.add(id.textValue());
        }
        ObjectNode notice=OpsRecords.object().put("area",area).put("interval",interval).put("status_text",status);
        var array=notice.putArray("area_ids");selected.forEach(array::add);
        ObjectNode body=OpsRecords.object().put("case_id",c.id()).put("decision",decision).put("rationale",rationale);
        body.set("case_ref",OpsRecords.ref(c));body.set("finding_ref",OpsRecords.ref(finding));body.set("notice",notice);
        body.put("decided_by",session.actorFor(allowedRoles()));
        Double time=null;
        var current=Runs.current(store.listAll("run")).orElse(null);
        if(current!=null && command.runId().equals(current.id()) && Set.of("RUNNING","PAUSED").contains(current.body().path("state").asText())){
            double value=clock.getObject().simTimeS();if(Double.isFinite(value)&&value>=0)time=value;
        }
        body.put("decision_sim_time_s",time).put("decision_sim_time_reason",time==null?"CURRENT_RUN_CLOCK_UNAVAILABLE":null);
        return HandlerResult.builder().save(RecordChange.create("board_decision",Ids.newId(),command.runId(),body),"BOARD_DECISION_SAVED",time).build();
    }
    private static boolean safeText(String text,int maximum,boolean noSource){
        return text!=null && !text.isBlank() && text.length()<=maximum && !fuelprint.api.ContactText.contains(text)
                && text.chars().noneMatch(c->c<0x20 || c==0x7f) && (!noSource || !SOURCE.matcher(text).find());
    }
}
