package fuelprint.field.vehicle;

import fuelprint.field.geo.Vec3;

/**
 * The drone's moving state. Not thread-safe: the field world guards it with its own lock.
 * The gas inlet sits {@value #INLET_FORWARD_M} m ahead of the body along the heading and
 * {@value #INLET_BELOW_M} m below it (the tick example of AGREEMENT section 3).
 */
public final class Drone {

    public static final double INLET_FORWARD_M = 0.4;
    public static final double INLET_BELOW_M = 0.4;

    private final String id;
    private final String label;
    private Vec3 position;
    private Vec3 velocity = Vec3.ZERO;
    private double yawDeg;
    private double energyWh;

    public Drone(String id, String label, Vec3 position, double energyWh) {
        this.id = id;
        this.label = label;
        this.position = position;
        this.energyWh = energyWh;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public Vec3 position() {
        return position;
    }

    public Vec3 velocity() {
        return velocity;
    }

    public double yawDeg() {
        return yawDeg;
    }

    public double energyWh() {
        return energyWh;
    }

    public double speed() {
        return velocity.norm();
    }

    /** Where the gas inlet is now. */
    public Vec3 inlet() {
        double yaw = Math.toRadians(yawDeg);
        return new Vec3(position.e() + INLET_FORWARD_M * Math.sin(yaw),
                position.n() + INLET_FORWARD_M * Math.cos(yaw),
                position.u() - INLET_BELOW_M);
    }

    /** Safety stop: velocity becomes zero at once (dead man, neutral input, take over, stop). */
    public void brake() {
        velocity = Vec3.ZERO;
    }

    void moveTo(Vec3 next) {
        position = next;
    }

    void setVelocity(Vec3 next) {
        velocity = next;
    }

    void setYawDeg(double yaw) {
        yawDeg = yaw;
    }

    void useEnergy(double wh) {
        energyWh = Math.max(0, energyWh - wh);
    }

    /** Test and scenario hook: set the energy left (watt-hours). */
    public void setEnergyWh(double wh) {
        energyWh = wh;
    }

    /** Test hook: place the drone somewhere at rest. */
    public void placeAt(Vec3 where) {
        position = where;
        velocity = Vec3.ZERO;
    }
}
