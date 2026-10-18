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

    /** Mean response over (startS, endS] to the field seen along the recorded inlet track. */
    public double windowMean(Gas gas, InletTrack track, double startS, double endS,
                             ToDoubleFunction<Enu> field) {
        if (track.stationary()) return field.applyAsDouble(track.at(startS));
        double t0 = Math.min(startS, Math.max(startS - prehistoryS, track.samples().get(0).simTimeS()));
        var rng = new SplittableRandom(0);
        GasChannel channel = GasSettings.valueOf(gas.name()).channel(0, 0, field.applyAsDouble(track.at(t0)), rng);
        double state = channel.responseState();
        double sum = 0;
        int count = 0;
        int steps = (int) Math.round((endS - t0) / dtS);
        for (int i = 1; i <= steps; i++) {
            double t = t0 + i * dtS;
            state = channel.advance(field.applyAsDouble(track.at(t)), 1, dtS, false, rng).value();
            if (t > startS + 1e-9) {
                sum += state;
                count++;
            }
        }
        return count == 0 ? state : sum / count;
    }
}
