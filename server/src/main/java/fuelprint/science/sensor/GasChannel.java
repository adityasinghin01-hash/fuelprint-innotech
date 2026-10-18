package fuelprint.science.sensor;

import java.util.List;
import java.util.SplittableRandom;

/**
 * One gas branch of an instrument: inlet line (transport by sampled volume), chamber mixing, first-order
 * sensor response, then independent electrical noise (AR(1), held for one second).
 *
 * <p>A failed flow keeps the stale response state, the stale raw number and the stale value, adds no volume and
 * flags FLOW_FAULT. It never turns into zero.
 */
public final class GasChannel {

    /** What one tick of a branch produced. */
    public record Output(double value, double raw, double flowLMin, double volumeL, List<String> quality) {
    }

    public static final double FLOW_TOLERANCE = 0.05;
    public static final double CHAMBER_T90_S = 8.0;

    private final double targetFlowLMin;
    private final InletLine line;
    private final FirstOrderResponse chamber;
    private final FirstOrderResponse response;
    private final Ar1 noise;
    private double noiseNow;
    private double lastRaw;
    private double lastValue;

    /**
     * @param t90S           sensor response T90 (60 s SO2, 30 s CO2)
     * @param chamberT90S    chamber mixing T90 (8 s); 0 or less leaves the chamber out
     * @param noiseSd        innovation SD of the electrical noise (5 ppb, 2 ppm)
     * @param initialAmbient concentration the line, chamber and sensor start at
     */
    public GasChannel(double targetFlowLMin, double t90S, double chamberT90S, double noiseSd, double rho,
                      double initialAmbient, SplittableRandom rng) {
        this.targetFlowLMin = targetFlowLMin;
        this.line = new InletLine(initialAmbient);
        this.chamber = chamberT90S > 0 ? new FirstOrderResponse(chamberT90S, initialAmbient) : null;
        this.response = new FirstOrderResponse(t90S, initialAmbient);
        this.noise = new Ar1(rho, noiseSd, rng);
        this.noiseNow = noise.value();
        this.lastRaw = initialAmbient + noiseNow;
        this.lastValue = lastRaw;
    }

    public double targetFlowLMin() {
        return targetFlowLMin;
    }

    /** The state of the sensor itself before noise (the response). */
    public double responseState() {
        return response.value();
    }

    /**
     * Advances one step.
     *
     * @param ambient        concentration at the inlet now
     * @param flowFactor     actual flow divided by target flow (1 normal, 0 failed)
     * @param dtS            step length
     * @param secondBoundary true when a new whole second starts (the noise takes one step)
     */
    public Output advance(double ambient, double flowFactor, double dtS, boolean secondBoundary,
                          SplittableRandom rng) {
        double flow = targetFlowLMin * flowFactor;
        if (flowFactor <= 0) {
            return new Output(lastValue, lastRaw, 0.0, 0.0, List.of("FLOW_FAULT"));
        }
        double volume = flow * dtS / 60.0;
        double delivered = line.push(ambient, volume);
        if (!Double.isNaN(delivered)) {
            double into = chamber == null ? delivered : chamber.update(delivered, dtS);
            response.update(into, dtS);
        }
        if (secondBoundary) {
            noiseNow = noise.next(rng);
        }
        lastRaw = response.value() + noiseNow;
        lastValue = lastRaw;
        List<String> quality = Math.abs(flowFactor - 1.0) > FLOW_TOLERANCE
                ? List.of("FLOW_OUT_OF_RANGE") : List.of("OK");
        return new Output(lastValue, lastRaw, flow, volume, quality);
    }
}
