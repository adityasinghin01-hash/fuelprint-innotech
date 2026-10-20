package fuelprint.ops;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** After-commit mission proposals and preflight expiry; never writes or waits on commands. */
@Component @Profile("!stub")
final class MissionAutomation implements WorldStepListener {
    private static final Logger log=LoggerFactory.getLogger(MissionAutomation.class);
    private final AutomationBus automation;
    private final StoreView store;
    private final ServerMode mode;
    private final MissionHandlers.Support support;
    private final Set<String> queued=ConcurrentHashMap.newKeySet();
    MissionAutomation(AutomationBus automation,StoreView store,ServerMode mode,MissionHandlers.Support support){
        this.automation=automation;this.store=store;this.mode=mode;this.support=support;
    }
    @EventListener void saved(RecordSaved event){
        if(!mode.automationActive()||event.runId()==null)return;
        if(!Set.of("analysis","proposal").contains(event.recordType()))return;
        StoredRecord exact=store.get(event.recordType(),event.recordId(),event.revision()).orElse(null);
        if(exact==null||!event.runId().equals(exact.runId()))return;
        if(event.recordType().equals("proposal")&&support.settings.getObject().isOn(AutomationSettings.AUTO_PROPOSE)
                &&"BEST_ACTION".equals(exact.body().path("result").asText())&&OpsRecords.text(exact.body(),"best_action_id")!=null){
            boolean pending=store.list("mission",event.runId()).stream().anyMatch(m->{
                var investigation=OpsRecords.investigationForMission(store,m);
                return investigation!=null&&investigation.id().equals(OpsRecords.text(exact.body(),"investigation_id"))
                        &&Set.of("PROPOSED","APPROVED").contains(m.body().path("state").asText());
            });
            if(!pending && !MissionHandlers.proposalConsumed(store,event.runId(),exact.id()))submit("propose_mission",exact.id()+":"+exact.revision(),event.runId(),
                    OpsRecords.object().put("proposal_id",exact.id()).put("action_id",exact.body().path("best_action_id").asText()),exact.revision());
        }
        expire(event.runId(),support.now());
    }
    @Override public void onStep(double now,double dt){
        if(!mode.automationActive()||!Double.isFinite(now))return;
        for(StoredRecord run:store.listAll("run"))if(Set.of("RUNNING","PAUSED").contains(run.body().path("state").asText()))expire(run.id(),now);
    }
    private void expire(String run,double now){
        for(StoredRecord mission:store.list("mission",run))if("APPROVED".equals(mission.body().path("state").asText())){
            String reason=support.staleReason(store,mission,now);
            if(reason!=null)submit("expire_mission",mission.id()+":"+mission.revision(),run,
                    OpsRecords.object().put("mission_id",mission.id()).put("reason",reason),mission.revision());
        }
    }
    private void submit(String command,String key,String run,ObjectNode payload,Long revision){
        String pending=command+":"+key;if(!queued.add(pending))return;
        automation.submit(command,payload,run,revision).whenComplete((receipt,failure)->{
            queued.remove(pending);
            if(failure!=null)log.warn("Mission automatic step {} failed.",command);
            else if(!receipt.applied())log.info("Mission automatic step {} refused: {}",command,receipt.reasonCode());
        });
    }
}
