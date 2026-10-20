package fuelprint.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.Ids;
import fuelprint.core.command.Command;
import fuelprint.core.command.Rejection;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;

/** Public-record joins shared by the Operations command handlers. */
final class OpsRecords {
    private OpsRecords() {}
    static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    static String text(JsonNode n, String field) { return n.path(field).isTextual() ? n.path(field).asText() : null; }
    static ObjectNode own(StoredRecord r) {
        ObjectNode n=r.body(); RecordChange.COMMON_FIELDS.forEach(n::remove); return n;
    }
    static StoredRecord sameRun(StoreView store,String type,String id,String run) {
        if(!Ids.isUuid(id)||run==null)return null;
        return store.get(type,id).filter(r->run.equals(r.runId())).orElse(null);
    }
    static Rejection runProblem(Command c,StoreView store) {
        StoredRecord run=sameRun(store,"run",c.runId(),c.runId());
        if(run==null)return Rejection.invalid("A recorded run_id is required.");
        if(!Set.of("RUNNING","PAUSED").contains(run.body().path("state").asText()))
            return new Rejection("WRONG_STATE","This run has ended; start or select the current run.");
        return null;
    }
    static StoredRecord active(StoreView store,String run) {
        return store.list("investigation",run).stream().filter(r->!"CLOSED".equals(r.body().path("state").asText())).findFirst().orElse(null);
    }
    /** Durable intake membership survives closure and includes the primary ID on older records. */
    static boolean triggerConsumed(StoreView store, String run, String triggerId) {
        if (run == null || !Ids.isUuid(triggerId)) return false;
        return store.list("investigation", run).stream().anyMatch(investigation -> {
            JsonNode body = investigation.body();
            return triggerId.equals(text(body, "trigger_id"))
                    || strings(body.path("trigger_ids")).contains(triggerId);
        });
    }
    static ObjectNode freshInvestigation() {
        ObjectNode n=object().put("state","OPEN"); n.putNull("trigger_id"); n.putNull("survey_id");
        for(String key:List.of("trigger_ids","survey_ids","analysis_ids","warned_area_ids","screening_record_ids"))n.putArray(key);
        return n;
    }
    static List<String> strings(JsonNode n) {
        List<String> ids=new ArrayList<>(); if(n.isArray())n.forEach(x->{if(x.isTextual())ids.add(x.asText());}); return ids;
    }
    static void append(ObjectNode n,String field,String id) {
        var values=strings(n.path(field)); if(!values.contains(id)){ values.add(id); var array=n.putArray(field);values.forEach(array::add); }
    }
    static ObjectNode ref(StoredRecord r) { return object().put("type",r.type()).put("id",r.id()).put("revision",r.revision()); }

    /** Resolve existing mission -> proposal -> analysis, or mission -> survey. Never cross a run. */
    static StoredRecord investigationForMission(StoreView store,StoredRecord mission) {
        String run=mission.runId();
        StoredRecord proposal=sameRun(store,"proposal",text(mission.body(),"proposal_id"),run);
        if(proposal!=null){
            StoredRecord analysis=sameRun(store,"analysis",text(proposal.body(),"analysis_id"),run);
            if(analysis!=null)return sameRun(store,"investigation",text(analysis.body(),"investigation_id"),run);
        }
        StoredRecord survey=sameRun(store,"survey",text(mission.body(),"survey_id"),run);
        return survey==null?null:sameRun(store,"investigation",text(survey.body(),"investigation_id"),run);
    }
    static StoredRecord investigationForEvidence(StoreView store,StoredRecord screening) {
        String run=screening.runId(); JsonNode b=screening.body();
        if(!Set.of("VALID","LIMITED","REJECTED").contains(b.path("decision").asText()))return null;
        StoredRecord mission=sameRun(store,"mission",text(b,"mission_id"),run);
        StoredRecord attempt=sameRun(store,"attempt",text(b,"attempt_id"),run);
        if(mission==null||attempt==null||!mission.id().equals(text(attempt.body(),"mission_id")))return null;
        return investigationForMission(store,mission);
    }
    static List<StoredRecord> evidence(StoreView store,StoredRecord investigation) {
        return store.list("screening_record",investigation.runId()).stream().filter(r->{
            StoredRecord linked=investigationForEvidence(store,r); return linked!=null&&linked.id().equals(investigation.id());
        }).toList();
    }
    /** A public-data basis shared by queued channel events and persisted automatic deduplication. */
    static ObjectNode basis(StoreView store,StoredRecord investigation,JsonNode wind) {
        ObjectNode basis=object(); basis.set("wind",wind); basis.set("survey_ids",investigation.body().path("survey_ids"));
        var windows=basis.putArray("windows"); store.list("window",investigation.runId()).forEach(r->windows.add(ref(r)));
        var evidence=basis.putArray("screening_records"); evidence(store,investigation).forEach(r->evidence.add(ref(r)));
        return basis;
    }
    static boolean sameBasis(JsonNode a, JsonNode b) {
        // Persistence may read revision 1 as IntNode although the public revision was a LongNode.
        // This fixed-order basis has identical JSON for the same exact references in either form.
        return a.toString().equals(b.toString());
    }
    static boolean validCause(Command c,StoreView store,StoredRecord investigation) {
        JsonNode cause=c.payload().get("cause");
        if(cause==null)return true;
        if(!cause.isObject()||!cause.path("revision").isIntegralNumber()||cause.path("revision").asLong()<1)return false;
        StoredRecord r=store.get(cause.path("type").asText(),cause.path("id").asText(),cause.path("revision").asLong()).orElse(null);
        if(r==null||!Objects.equals(c.runId(),r.runId()))return false;
        if(r.type().equals("investigation"))return r.id().equals(investigation.id());
        if(r.type().equals("screening_record")){
            StoredRecord linked=investigationForEvidence(store,r); return linked!=null&&linked.id().equals(investigation.id());
        }
        return false;
    }
}
