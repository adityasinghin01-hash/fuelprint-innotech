package fuelprint.api;

/**
 * One vehicle as sent in the tick (AGREEMENT section 3). Unknown values are null, never zero.
 *
 * @param vehicleId   the vehicle UUID
 * @param label       like {@code DRONE-1}
 * @param kind        {@code DRONE} or {@code VAN}
 * @param pose        position and heading
 * @param velocity    current velocity
 * @param inlet       where its gas inlet is, ENU metres
 * @param energyWh    energy left in watt-hours, or null if unknown
 * @param stage       mission stage like {@code OUTBOUND}, or null when it has no mission
 * @param leaseHolder actor label of the lease holder ({@code autopilot} for the autopilot), or null
 * @param pilot       who is flying it
 */
public record VehicleState(String vehicleId, String label, String kind, VehiclePose pose, Velocity velocity,
                           Enu inlet, Double energyWh, String stage, String leaseHolder, Pilot pilot) {
}
