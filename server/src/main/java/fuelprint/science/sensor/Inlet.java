package fuelprint.science.sensor;

import java.util.SplittableRandom;

/** The instrument behind one inlet: SO2 and CO2 gas branches and an optical particle estimate. */
final class Inlet {

    final String id;
    final String label;
    final boolean fixed;
    final GasChannel so2;
    final GasChannel co2;
    final Ar1 pmNoise;
    final SplittableRandom rng;

    double lastTimeS;
    long lastSecond;
    double ambientSo2;
    double ambientCo2;
    double pmRaw;
    java.util.List<fuelprint.api.Reading> cached = java.util.List.of();

    Inlet(String id, String label, long seed, double so2Ambient, double co2Ambient, double timeS,
          NoiseSettings n) {
        this.id = id;
        this.label = label;
        this.fixed = label != null && label.matches("N[0-9]+");
        this.rng = new SplittableRandom(seed);
        this.so2 = GasSettings.SO2.channel(n.so2Sd(), n.rho(), so2Ambient, rng);
        this.co2 = GasSettings.CO2.channel(n.co2Sd(), n.rho(), co2Ambient, rng);
        this.pmNoise = new Ar1(0.8, 3.0, rng);
        this.lastTimeS = timeS;
        this.lastSecond = (long) Math.floor(timeS + 1e-9) - 1;
        this.ambientSo2 = so2Ambient;
        this.ambientCo2 = co2Ambient;
    }

    /** Noise numbers of the world profile. */
    record NoiseSettings(double rho, double so2Sd, double co2Sd) {
    }
}
