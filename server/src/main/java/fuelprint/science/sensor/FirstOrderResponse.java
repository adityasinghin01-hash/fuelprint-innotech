package fuelprint.science.sensor;

/**
 * First-order sensor response: {@code new = old + (target - old) * (1 - exp(-dt/tau))}, tau = T90 / ln 10.
 * After T90 seconds of a step the output has covered 90 % of it.
 */
public final class FirstOrderResponse {

    private final double tau;
    private double state;

    public FirstOrderResponse(double t90S, double initial) {
        if (t90S <= 0) {
            throw new IllegalArgumentException("T90 must be positive");
        }
        this.tau = t90S / Math.log(10);
        this.state = initial;
    }

    public double update(double target, double dtS) {
        state = state + (target - state) * (1 - Math.exp(-dtS / tau));
        return state;
    }

    public double value() {
        return state;
    }

    public void reset(double value) {
        state = value;
    }
}
