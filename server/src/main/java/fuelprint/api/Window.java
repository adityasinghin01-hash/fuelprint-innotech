package fuelprint.api;

/**
 * A saved averaging window of one channel (AGREEMENT section 6, {@code window}). Unknown numbers are null.
 *
 * @param id                 the window record UUID
 * @param sensorOrVehicleId  the sensor or vehicle the readings came from (see the contract note in the P1 report)
 * @param channel            like {@code SO2_ppb}
 * @param startS             window start, simulation seconds
 * @param endS               window end, simulation seconds
 * @param validSeconds       seconds of usable data inside the window
 * @param meanEnhancement    mean rise above background, or null
 * @param stdUncertainty     standard uncertainty of that mean, or null
 * @param backgroundWindowId the window used as background, or null
 */
public record Window(String id, String sensorOrVehicleId, String channel, double startS, double endS,
                     double validSeconds, Double meanEnhancement, Double stdUncertainty, String backgroundWindowId) {
}
