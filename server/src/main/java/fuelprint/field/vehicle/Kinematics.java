package fuelprint.field.vehicle;

import fuelprint.field.geo.Area;
import fuelprint.field.geo.Obstacles;
import fuelprint.field.geo.Vec3;
import fuelprint.field.profile.WorldProfile;

/**
 * Moves a drone toward the wanted velocity inside the limits of AGREEMENT section 7: speed at most 3 m/s,
 * velocity change at most 1 m/s per second, east and north within ±450 m, height 2 to 80 m, and the 2 m
 * clearance from every box. A step longer than {@value #MAX_SUBSTEP_S} s is split into sub-steps, and every
 * sub-step move is checked as a swept segment, so a fast or long step can never tunnel through a box.
 * When a move would break the clearance the drone slides: each axis that would hit is stopped.
 *
 * <p>The safety stop ({@link Drone#brake()}) is not limited by the acceleration: the contract says the
 * server sets the velocity to zero (dead man, neutral, take over).
 */
public final class Kinematics {

    public static final double MAX_SUBSTEP_S = 0.05;
    static final double YAW_MIN_SPEED_MS = 0.2;

    private final double maxSpeed;
    private final double maxAccel;
    private final Area area;
    private final Obstacles obstacles;
    private final Energy energy;

    public Kinematics(WorldProfile profile, Obstacles obstacles) {
        this(profile.maxSpeedMS(), profile.maxAccelMS2(),
                new Area(profile.xyMin(), profile.xyMax(), profile.zMin(), profile.zMax()), obstacles,
                new Energy(profile));
    }

    public Kinematics(double maxSpeed, double maxAccel, Area area, Obstacles obstacles, Energy energy) {
        this.maxSpeed = maxSpeed;
        this.maxAccel = maxAccel;
        this.area = area;
        this.obstacles = obstacles;
        this.energy = energy;
    }

    public double maxSpeed() {
        return maxSpeed;
    }

    public double maxAccel() {
        return maxAccel;
    }

    public Area area() {
        return area;
    }

    public Obstacles obstacles() {
        return obstacles;
    }

    /**
     * Advances {@code drone} by {@code dt} simulation seconds toward {@code wanted} (m/s, ENU).
     *
     * @param useEnergy false while the drone is parked on the depot pad with its motors off
     */
    public void step(Drone drone, Vec3 wanted, double dt, boolean useEnergy) {
        if (!(dt > 0) || !Double.isFinite(dt)) {
            return;
        }
        Vec3 target = wanted == null || !wanted.isFinite() ? Vec3.ZERO : wanted.limit(maxSpeed);
        int steps = (int) Math.max(1, Math.ceil(dt / MAX_SUBSTEP_S - 1e-9));
        double h = dt / steps;
        for (int i = 0; i < steps; i++) {
            Vec3 change = target.minus(drone.velocity()).limit(maxAccel * h);
            drone.setVelocity(drone.velocity().plus(change).limit(maxSpeed));
            move(drone, h);
            if (drone.velocity().horizontalNorm() > YAW_MIN_SPEED_MS) {
                double yaw = Math.toDegrees(Math.atan2(drone.velocity().e(), drone.velocity().n()));
                drone.setYawDeg(yaw < 0 ? yaw + 360 : yaw);
            }
            if (useEnergy) {
                drone.useEnergy(energy.wattHours(drone.speed(), h));
            }
        }
    }

    private void move(Drone drone, double h) {
        Vec3 p = drone.position();
        Vec3 v = drone.velocity();
        double e = p.e() + v.e() * h;
        double n = p.n() + v.n() * h;
        double u = p.u() + v.u() * h;
        double ve = v.e();
        double vn = v.n();
        double vu = v.u();
        if (e < area.xyMin() || e > area.xyMax()) {
            e = clamp(e, area.xyMin(), area.xyMax());
            ve = 0;
        }
        if (n < area.xyMin() || n > area.xyMax()) {
            n = clamp(n, area.xyMin(), area.xyMax());
            vn = 0;
        }
        if (u < area.zMin() || u > area.zMax()) {
            u = clamp(u, area.zMin(), area.zMax());
            vu = 0;
        }
        Vec3 q = new Vec3(e, n, u);
        if (!obstacles.clear(p, q)) {
            // Slide: try each axis on its own; stop the axes that would break the clearance.
            Vec3 at = p;
            Vec3 tryUp = new Vec3(at.e(), at.n(), q.u());
            if (obstacles.clear(at, tryUp)) {
                at = tryUp;
            } else {
                vu = 0;
            }
            Vec3 tryEast = new Vec3(q.e(), at.n(), at.u());
            if (obstacles.clear(at, tryEast)) {
                at = tryEast;
            } else {
                ve = 0;
            }
            Vec3 tryNorth = new Vec3(at.e(), q.n(), at.u());
            if (obstacles.clear(at, tryNorth)) {
                at = tryNorth;
            } else {
                vn = 0;
            }
            q = at;
        }
        drone.moveTo(q);
        drone.setVelocity(new Vec3(ve, vn, vu));
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
