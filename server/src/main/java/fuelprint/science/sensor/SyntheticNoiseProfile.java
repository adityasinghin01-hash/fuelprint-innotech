package fuelprint.science.sensor;

import java.util.List;

/** The declared independent instrument AR1 noise, shared by observation and averaging calculations. */
public final class SyntheticNoiseProfile {
    public static final double RHO = 0.8;

    private SyntheticNoiseProfile() { }

    public static double innovationSd(String channel) {
        return switch (channel) {
            case SensorField.SO2 -> 5.0;
            case SensorField.CO2 -> 2.0;
            default -> throw new IllegalArgumentException("Unsupported gas channel: " + channel);
        };
    }

    /** Exact covariance of two actual 1 Hz sample-time means under stationary AR1 noise. */
    public static double meanCovariance(String channel, List<Double> firstTimes, List<Double> secondTimes) {
        if (firstTimes.isEmpty() || secondTimes.isEmpty()) {
            throw new IllegalArgumentException("Sample-time means require nonempty samples");
        }
        double sd = innovationSd(channel);
        double stationaryVariance = sd * sd / (1 - RHO * RHO);
        double sum = 0;
        for (double first : firstTimes) {
            for (double second : secondTimes) {
                sum += Math.pow(RHO, Math.abs(first - second));
            }
        }
        return stationaryVariance * sum / firstTimes.size() / secondTimes.size();
    }
}
