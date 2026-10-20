package fuelprint.ops;

import fuelprint.api.MissionAuthority;
import fuelprint.core.command.Rejection;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The same current public authority checked by human approval, read again at initial Field departure. */
@Component
@Profile("!stub")
final class MissionApprovalAuthority implements MissionAuthority {
    private final StoreView store;
    private final MissionHandlers.Support support;

    MissionApprovalAuthority(StoreView store, MissionHandlers.Support support) {
        this.store = store;
        this.support = support;
    }

    @Override
    public Decision checkInitialDeparture(String runId, String missionId) {
        StoredRecord mission = OpsRecords.sameRun(store, "mission", missionId, runId);
        if (mission == null) return Decision.refuse("INVALID", "mission_id must name a mission in this run.");
        if (!"APPROVED".equals(mission.body().path("state").asText())
                || !"NONE".equals(mission.body().path("pilot").asText())) {
            return Decision.refuse("WRONG_STATE", "Initial departure requires an unstarted APPROVED mission.");
        }
        Rejection problem = support.approvalAuthorityProblem(store, mission, support.now());
        return problem == null ? Decision.permit() : Decision.refuse(problem.reasonCode(), problem.reasonText());
    }
}
