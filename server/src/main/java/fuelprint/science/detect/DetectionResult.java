package fuelprint.science.detect;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import fuelprint.api.Trigger;
import fuelprint.api.Window;

/**
 * What one call of the alarm rule found: the overall status, the status per box, any trigger, and the windows
 * (background windows that were just accepted, and the enhancement window of a trigger) for the core to save.
 */
public record DetectionResult(DetectionStatus status, Map<String, DetectionStatus> bySensor, List<Trigger> triggers,
                              List<Window> windows) {

    public DetectionResult {
        bySensor = Map.copyOf(bySensor);
        triggers = List.copyOf(triggers);
        windows = List.copyOf(windows);
    }

    /** The first trigger of this call, if any. */
    public Optional<Trigger> firstTrigger() {
        return triggers.stream().findFirst();
    }
}
