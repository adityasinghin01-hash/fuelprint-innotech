package fuelprint.api;

/** A velocity in ENU metres per second (tick {@code velocity}, pilot {@code velocity_enu}). */
public record Velocity(double east, double north, double up) {
}
