package fuelprint.api;

/** One recorded public inlet position, in ENU metres, at a simulation time in seconds. */
public record InletSample(double simTimeS, Enu inlet) {
}
