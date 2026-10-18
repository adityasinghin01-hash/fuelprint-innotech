package fuelprint.api;

/** A point in ENU metres from the map origin (28.87 N, 77.12 E); {@code up} is height above local ground. */
public record Enu(double east, double north, double up) {
}
