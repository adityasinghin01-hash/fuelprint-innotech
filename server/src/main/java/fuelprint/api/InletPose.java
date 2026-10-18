package fuelprint.api;

/**
 * Where one gas inlet is right now, for {@link ObservationProvider#observe}.
 *
 * @param sensorOrVehicleId the sensor or vehicle UUID
 * @param label             display label, like {@code N2} or {@code DRONE-1}
 * @param inlet             inlet position in ENU metres
 */
public record InletPose(String sensorOrVehicleId, String label, Enu inlet) {
}
