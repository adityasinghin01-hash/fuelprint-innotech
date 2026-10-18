package fuelprint.api;

import java.util.List;

/** Science: the readings of one tick. */
public interface ObservationProvider {

    /** Readings at each fixed sensor and each vehicle inlet for this tick. */
    ReadingBatch observe(double simTimeS, List<InletPose> inlets);
}
