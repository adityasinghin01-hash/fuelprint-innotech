package fuelprint.api;

import java.util.List;

/** All readings of one tick, from {@link ObservationProvider#observe}. */
public record ReadingBatch(double simTimeS, List<Reading> readings) {

    public ReadingBatch {
        readings = List.copyOf(readings);
    }
}
