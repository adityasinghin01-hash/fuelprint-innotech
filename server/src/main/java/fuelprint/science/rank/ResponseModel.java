package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.science.sensor.GasChannel;
import fuelprint.science.sensor.GasSettings;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

/**
 * The world's volume-transport line, mixing chamber and per-gas sensor, without electrical noise.
 * Before recorded history the first public inlet is assumed to have reached steady state.
 */
public record ResponseModel(double dtS, double prehistoryS) {
    public static final ResponseModel TEACHING = new ResponseModel(0.05, 180.0);

    public String label() {
        StringBuilder label = new StringBuilder("GasChannel;dt_s=" + dtS);
        for (GasSettings gas : GasSettings.values()) {
            label.append(';').append(gas).append("_line_delay_s=").append(gas.lineDelayS())
                    .append(",chamber_t90_s=").append(gas.chamberT90S())
                    .append(",sensor_t90_s=").append(gas.sensorT90S());
        }
        return label.toString();
    }

    @FunctionalInterface
    public interface Ambient { double at(double time, Enu inlet); }

    /** Mean of the same discrete 1 Hz samples used by the observation windows, over (startS,endS]. */
    public double windowMean(Gas gas, InletTrack track, double startS, double endS,
                             ToDoubleFunction<Enu> field) {
        if (track.stationary()) return field.applyAsDouble(track.at(startS));
        return means(gas, track, startS, endS, startS, endS, (t,p) -> field.applyAsDouble(p), false, () -> false)[1];
    }

    /** One continuous instrument state, with the measured earlier background subtracted from collection. */
    public double windowContrast(Gas gas, InletTrack track, double backgroundStart, double backgroundEnd,
                                 double startS, double endS, ToDoubleFunction<Enu> field) {
        if (track.stationary()) return 0;
        double[] m = means(gas, track, backgroundStart, backgroundEnd, startS, endS, (t,p) -> field.applyAsDouble(p), false, () -> false);
        return m[1] - m[0];
    }

    /** Finite-age plume hypothesis from time zero, including the same measured background operator. */
    public double puffContrast(Gas gas, InletTrack track, double bgStart, double bgEnd,
                               double start, double end, Ambient field) {
        return puffContrast(gas,track,bgStart,bgEnd,start,end,field,() -> false);
    }

    public double puffContrast(Gas gas, InletTrack track, double bgStart, double bgEnd,
                               double start, double end, Ambient field, java.util.function.BooleanSupplier expired) {
        double[] m = means(gas, track, bgStart, bgEnd, start, end, field, true, expired);
        return m[1]-m[0];
    }

    private double[] means(Gas gas, InletTrack track, double bgStart, double bgEnd,
                           double start, double end, Ambient field, boolean fromZero,
                           java.util.function.BooleanSupplier expired) {
        double first = Math.min(bgStart, start);
        double t0 = fromZero ? 0 : Math.max(0, Math.floor(Math.max(first - prehistoryS, track.samples().get(0).simTimeS())));
        var rng = new SplittableRandom(0);
        double held = field.at(t0, track.at(t0));
        GasChannel channel = GasSettings.valueOf(gas.name()).channel(0, 0, held, rng);
        double[] sum = new double[2];
        int[] count = new int[2];
        sample(t0, channel.responseState(), bgStart, bgEnd, start, end, sum, count);
        long second = (long) Math.floor(t0 + 1e-9);
        int steps = (int) Math.round((end - t0) / dtS);
        for (int i = 1; i <= steps; i++) {
            double t = t0 + i * dtS;
            long now = (long) Math.floor(t + 1e-9);
            if (now > second) {
                if (expired.getAsBoolean()) throw new FitEngine.BudgetExceeded();
                held = field.at(t, track.at(t));
                second = now;
            }
            double state = channel.advance(held, 1, dtS, false, rng).value();
            if (Math.abs(t - Math.rint(t)) < 1e-7)
                sample(t, state, bgStart, bgEnd, start, end, sum, count);
        }
        if (count[0] == 0 || count[1] == 0) throw new IllegalArgumentException("Window has no 1 Hz samples");
        return new double[]{sum[0] / count[0], sum[1] / count[1]};
    }

    private static void sample(double t, double value, double bgStart, double bgEnd, double start, double end,
                               double[] sum, int[] count) {
        if (t > bgStart + 1e-7 && t <= bgEnd + 1e-7) { sum[0] += value; count[0]++; }
        if (t > start + 1e-7 && t <= end + 1e-7) { sum[1] += value; count[1]++; }
    }
}
