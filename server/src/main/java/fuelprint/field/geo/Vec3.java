package fuelprint.field.geo;

import fuelprint.api.Enu;
import fuelprint.api.Velocity;

/** A small immutable 3D vector in ENU metres (or metres per second): east, north, up. */
public record Vec3(double e, double n, double u) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public static Vec3 of(Enu enu) {
        return new Vec3(enu.east(), enu.north(), enu.up());
    }

    public Vec3 plus(Vec3 o) {
        return new Vec3(e + o.e, n + o.n, u + o.u);
    }

    public Vec3 minus(Vec3 o) {
        return new Vec3(e - o.e, n - o.n, u - o.u);
    }

    public Vec3 times(double k) {
        return new Vec3(e * k, n * k, u * k);
    }

    public double norm() {
        return Math.sqrt(e * e + n * n + u * u);
    }

    public double horizontalNorm() {
        return Math.hypot(e, n);
    }

    public double distance(Vec3 o) {
        return minus(o).norm();
    }

    public double horizontalDistance(Vec3 o) {
        return Math.hypot(e - o.e, n - o.n);
    }

    /** This vector shortened to at most {@code max} length (same direction). */
    public Vec3 limit(double max) {
        double length = norm();
        return length <= max || length == 0 ? this : times(max / length);
    }

    public boolean isFinite() {
        return Double.isFinite(e) && Double.isFinite(n) && Double.isFinite(u);
    }

    public Enu toEnu() {
        return new Enu(e, n, u);
    }

    public Velocity toVelocity() {
        return new Velocity(e, n, u);
    }
}
