package fuelprint.science.sensor;

import java.util.SplittableRandom;

/** A stationary first-order autoregressive process stepped once per second. */
final class Ar1 {

    private final double rho;
    private final double innovationSd;
    private double value;

    Ar1(double rho, double innovationSd, SplittableRandom rng) {
        this.rho = rho;
        this.innovationSd = innovationSd;
        // Stationary start: variance = sd^2 / (1 - rho^2)
        this.value = gaussian(rng) * innovationSd / Math.sqrt(1 - rho * rho);
    }

    double value() {
        return value;
    }

    double next(SplittableRandom rng) {
        value = rho * value + innovationSd * gaussian(rng);
        return value;
    }

    static double gaussian(SplittableRandom rng) {
        double u1 = 1.0 - rng.nextDouble();
        double u2 = rng.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}
