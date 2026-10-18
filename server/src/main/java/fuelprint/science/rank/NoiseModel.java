package fuelprint.science.rank;

/**
 * The declared synthetic noise allocation from world-profile.json (SYNTHETIC_ALLOCATION_NOT_HARDWARE_NOISE):
 * 1 Hz AR(1) instrument noise per channel. The standard uncertainty of a 30 s enhancement is that of the 30-sample
 * signal-window mean minus an independent 30-sample background-window mean.
 */
public final class NoiseModel {

    private NoiseModel() {
    }

    /** Exact variance of the mean of n consecutive samples of a stationary AR(1) with innovation sd. */
    public static double ar1MeanVariance(double rho, double innovationSd, int n) {
        double marginal = innovationSd * innovationSd / (1 - rho * rho);
        double s = n;
        double p = 1;
        for (int k = 1; k < n; k++) {
            p *= rho;
            s += 2.0 * (n - k) * p;
        }
        return marginal * s / ((double) n * n);
    }

    /** Standard uncertainty of one 30 s enhancement window (signal mean minus background mean). */
    public static double enhancementStd(WorldProfile world, Gas gas) {
        int n = world.limit("window_s");
        double v = ar1MeanVariance(world.noise("AR1_rho"), world.noise(gas.innovationKey()), n);
        return Math.sqrt(2 * v);
    }
}
