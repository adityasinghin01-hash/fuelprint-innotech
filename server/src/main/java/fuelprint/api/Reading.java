package fuelprint.api;

import java.util.List;

/**
 * One reading (AGREEMENT section 6, {@code reading}). An unknown value is {@code null} with the reason in
 * {@code quality}; it is never turned into zero.
 *
 * @param sensorOrVehicleId the sensor or vehicle UUID
 * @param channel           like {@code SO2_ppb}, {@code CO2_ppm}, {@code PM_ugm3}
 * @param value             corrected value, or null when unknown
 * @param rawValue          value as the instrument reported it, or null
 * @param unit              {@code ppb}, {@code ppm}, {@code ug_m3}, {@code L_min}
 * @param simTimeS          simulation time of the reading
 * @param quality           quality flags, {@code OK} or the reasons it is not
 * @param origin            synthetic or physical
 */
public record Reading(String sensorOrVehicleId, String channel, Double value, Double rawValue, String unit,
                      double simTimeS, List<String> quality, Origin origin) {

    public Reading {
        quality = List.copyOf(quality);
    }
}
