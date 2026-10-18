package fuelprint.api;

import java.util.List;
import java.util.Optional;

/** Field supplies recorded vehicle inlet positions; science reads them without importing field code. */
public interface InletHistory {

    /**
     * Recorded samples covering the window from {@code startS} to {@code endS}, oldest first, for this run
     * and sensor or vehicle. Include boundary samples needed to follow the inlet throughout the window.
     * An empty Optional means the track is unknown; a present list must be nonempty. Never guess a position.
     */
    Optional<List<InletSample>> track(String runId, String sensorOrVehicleId, double startS, double endS);
}
