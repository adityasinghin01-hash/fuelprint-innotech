package fuelprint.api;

import java.util.Optional;

/** AI (AI-6): the normal SO2 band learned from real CPCB data. It never fires the alarm. */
public interface NormalAirModel {

    /** The expected band for this box, channel and moment, or empty if the model has none. */
    Optional<ExpectedBand> expected(String sensor, String channel, double simTimeS, WindNow wind);
}
