package fuelprint.api;

/**
 * Where a vehicle is and which way it faces (tick {@code pose}).
 *
 * @param east   ENU metres
 * @param north  ENU metres
 * @param up     height above local ground, metres
 * @param yawDeg heading, degrees clockwise from north
 */
public record VehiclePose(double east, double north, double up, double yawDeg) {
}
