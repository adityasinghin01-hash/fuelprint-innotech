package fuelprint.api;

import java.util.Optional;

/** Science: the fixed alarm rule TEACH-DETECT-1. */
public interface Detector {

    /** A trigger if the rule fires now, otherwise empty. */
    Optional<Trigger> evaluate(DetectorInput input);
}
