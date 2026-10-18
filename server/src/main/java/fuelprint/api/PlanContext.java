package fuelprint.api;

import java.util.List;

/**
 * What the planner needs besides the analysis, for {@link Planner#plan}.
 *
 * @param runId      the run
 * @param analysisId the saved analysis record UUID
 * @param simTimeS   simulation time now
 * @param wind       the wind now
 * @param vehicles   vehicles available
 */
public record PlanContext(String runId, String analysisId, double simTimeS, WindNow wind,
                          List<VehicleState> vehicles) {

    public PlanContext {
        vehicles = List.copyOf(vehicles);
    }
}
