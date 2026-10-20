package fuelprint.ops;

import java.util.List;
import java.util.Optional;

import fuelprint.api.Enu;
import fuelprint.api.Pilot;
import fuelprint.api.VehicleState;
import fuelprint.core.Ids;

/** Analyst-directed teaching actions, uniform across presets; no planner benefit is claimed. */
final class SurveyCatalog {

    record Action(String id, Enu target, String methodId, List<String> channelIds, String vehicleKind) {}

    private static final List<Action> ACTIONS = List.of(
            action("SURVEY-DRONE-0-100-35", new Enu(0, 100, 35)),
            action("SURVEY-DRONE-280-140-45", new Enu(280, 140, 45)));

    private SurveyCatalog() {}

    private static Action action(String id, Enu target) {
        return new Action(id, target, "TEACH-NODE-ONLINE-2", List.of("SO2_ppb", "CO2_ppm"), "DRONE");
    }

    static Optional<Action> find(String id) {
        return ACTIONS.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    static boolean available(VehicleState vehicle, Action action) {
        return vehicle != null && Ids.isUuid(vehicle.vehicleId()) && action.vehicleKind().equals(vehicle.kind())
                && vehicle.energyWh() != null && Double.isFinite(vehicle.energyWh()) && vehicle.energyWh() > 0
                && vehicle.pose() != null && Double.isFinite(vehicle.pose().east())
                && Double.isFinite(vehicle.pose().north()) && Double.isFinite(vehicle.pose().up())
                && vehicle.velocity() != null && Double.isFinite(vehicle.velocity().east())
                && Double.isFinite(vehicle.velocity().north()) && Double.isFinite(vehicle.velocity().up())
                && Math.abs(vehicle.velocity().east()) < 1e-9 && Math.abs(vehicle.velocity().north()) < 1e-9
                && Math.abs(vehicle.velocity().up()) < 1e-9
                && vehicle.leaseHolder() == null && vehicle.pilot() == Pilot.NONE
                && (vehicle.stage() == null || List.of("COMPLETE", "IDLE").contains(vehicle.stage()));
    }
}
