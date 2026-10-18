package fuelprint.api;

/**
 * A vehicle the planner may use.
 *
 * @param vehicleId the vehicle UUID
 * @param label     like {@code DRONE-1}
 * @param kind      {@code DRONE} or {@code VAN}
 * @param pose      where it is now, ENU metres
 * @param energyWh  energy left in watt-hours, or null if unknown
 */
public record VehicleState(String vehicleId, String label, String kind, Enu pose, Double energyWh) {
}
