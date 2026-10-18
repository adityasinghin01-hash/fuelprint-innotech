package fuelprint.api;

import java.util.List;

/**
 * What the alarm rule sees at one moment, for {@link Detector#evaluate}.
 *
 * @param runId    the run
 * @param simTimeS the current simulation time
 * @param readings recent fixed-box readings, oldest first
 * @param windows  recent saved windows of the fixed boxes, so a trigger can cite its background window
 * @param wind     the wind now
 */
public record DetectorInput(String runId, double simTimeS, List<Reading> readings, List<Window> windows,
                            WindNow wind) {

    public DetectorInput {
        readings = List.copyOf(readings);
        windows = List.copyOf(windows);
    }
}
