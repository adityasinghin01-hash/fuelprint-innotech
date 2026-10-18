package fuelprint.api;

import java.util.List;
import java.util.Optional;

/** Science: the fixed alarm rule TEACH-DETECT-1. */
public interface Detector {

    /** A trigger if the rule fires now, otherwise empty. */
    Optional<Trigger> evaluate(DetectorInput input);

    /**
     * Windows completed by now (added in P3; default none). The core calls it once per simulated second just
     * before {@link #evaluate} and saves each as a {@code window} record with {@link Window#id()} as its id, in
     * the same transaction as any trigger, so a trigger may cite them.
     */
    default List<Window> windows(DetectorInput input) {
        return List.of();
    }
}
