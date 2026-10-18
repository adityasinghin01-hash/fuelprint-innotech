package fuelprint.science.rank;

import fuelprint.api.Enu;

import java.util.function.ToDoubleFunction;

/**
 * The line, chamber and sensor response used for both the forward model and the planner's prospective rows:
 * a pure line delay followed by a first-order sensor lag, integrated at the 0.05 s science step. The initial state
 * is the steady response to the first public sample (declared prior steady-state assumption), then propagated
 * through all supplied history. The window value is the mean response over the same 30 s window.
 * <p>
 * Teaching allocation (declared, versioned here): line delay 2.26194671058 s (the ideal delay oracle in the
 * questions trace) and a sensor t90 of 60 s (tau = 60/ln 10). A stationary inlet in steady wind gives exactly the
 * kernel value, because the response has unit gain.
 */
public record ResponseModel(double lineDelayS, double sensorTauS, double dtS, double prehistoryS) {

    public static final ResponseModel TEACHING = new ResponseModel(2.26194671058, 60.0 / Math.log(10), 0.05, 180.0);

    public String label() {
        return "line_delay_s=" + lineDelayS + ",sensor_tau_s=" + sensorTauS + ",dt_s=" + dtS;
    }

    /** Mean response over [startS, endS] to the steady concentration field {@code field} seen along the track. */
    public double windowMean(InletTrack track, double startS, double endS, ToDoubleFunction<Enu> field) {
        if (track.stationary()) {
            return field.applyAsDouble(track.at(startS));
        }
        double t0 = Math.max(startS - prehistoryS, track.samples().get(0).simTimeS());
        t0 = Math.min(t0, startS);
        double state = field.applyAsDouble(track.at(t0 - lineDelayS));
        double alpha = sensorTauS > 0 ? 1 - Math.exp(-dtS / sensorTauS) : 1.0;
        double sum = 0;
        int count = 0;
        int steps = (int) Math.round((endS - t0) / dtS);
        for (int i = 1; i <= steps; i++) {
            double t = t0 + i * dtS;
            double c = field.applyAsDouble(track.at(t - lineDelayS));
            state += alpha * (c - state);
            if (t > startS + 1e-9) {
                sum += state;
                count++;
            }
        }
        return count == 0 ? state : sum / count;
    }
}
