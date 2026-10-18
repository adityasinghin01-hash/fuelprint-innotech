package fuelprint.field.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import fuelprint.field.geo.PathPlanner;
import fuelprint.field.geo.Vec3;

/**
 * The autopilot (AI-7): a server-side pilot that turns a list of safe waypoints into the same kind of
 * velocity input a person sends. It follows the approved route only; it never picks a target.
 *
 * <p>It flies straight at each waypoint and slows down so it can stop on it within the acceleration limit
 * (v = 0.9·√(2·a·d)), so its velocity always points along the planned (clear) leg. Speed, acceleration,
 * area and clearance are then enforced again by {@link fuelprint.field.vehicle.Kinematics}, exactly as
 * for a person.
 */
public final class Autopilot {

    static final double WAYPOINT_REACHED_M = 0.3;
    static final double HOLD_TOLERANCE_M = 0.02;

    private final PathPlanner planner;
    private final double maxSpeed;
    private final double maxAccel;
    private List<Vec3> waypoints = List.of();
    private int next;
    private String planKey;

    public Autopilot(PathPlanner planner, double maxSpeed, double maxAccel) {
        this.planner = planner;
        this.maxSpeed = maxSpeed;
        this.maxAccel = maxAccel;
    }

    /** Forgets the current plan; the next {@link #steer} call plans again. */
    public void reset() {
        waypoints = List.of();
        next = 0;
        planKey = null;
    }

    /** The waypoints still to fly. */
    public List<Vec3> remaining() {
        return next >= waypoints.size() ? List.of() : waypoints.subList(next, waypoints.size());
    }

    /**
     * The points to fly from {@code position} along {@code route} to {@code target}: the rest of the route
     * after the route leg closest to the drone, then the target.
     */
    public static List<Vec3> routePoints(Vec3 position, List<Vec3> route, Vec3 target) {
        List<Vec3> points = new ArrayList<>();
        if (route.size() >= 2) {
            int bestLeg = 0;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i + 1 < route.size(); i++) {
                double d = distanceToSegment(position, route.get(i), route.get(i + 1));
                if (d < best - 1e-9) {
                    best = d;
                    bestLeg = i;
                }
            }
            points.addAll(route.subList(bestLeg + 1, route.size()));
        } else {
            points.addAll(route);
        }
        if (points.isEmpty() || points.get(points.size() - 1).distance(target) > 1e-6) {
            points.add(target);
        }
        Vec3 last = points.get(points.size() - 1);
        List<Vec3> out = new ArrayList<>();
        for (Vec3 p : points) {
            if (p == last || p.distance(position) >= WAYPOINT_REACHED_M) {
                out.add(p);
            }
        }
        return out;
    }

    /** Plans a safe path through {@code points}; empty if there is none. */
    public Optional<List<Vec3>> plan(Vec3 position, List<Vec3> points) {
        return planner.planThrough(position, points);
    }

    /**
     * The velocity to ask for now to fly through {@code points} (planned on the first call for a given
     * {@code key}) and stop exactly on the last one, then hold it.
     *
     * @return the wanted velocity, or empty if no safe path exists (the caller hovers)
     */
    public Optional<Vec3> steer(String key, Vec3 position, Supplier<List<Vec3>> points, double dt) {
        if (!key.equals(planKey)) {
            Optional<List<Vec3>> path = plan(position, points.get());
            if (path.isEmpty()) {
                return Optional.empty();
            }
            waypoints = path.get();
            next = 0;
            planKey = key;
        }
        if (waypoints.isEmpty()) {
            return Optional.of(Vec3.ZERO);
        }
        while (next < waypoints.size() - 1 && waypoints.get(next).distance(position) < WAYPOINT_REACHED_M) {
            next++;
        }
        Vec3 goal = waypoints.get(Math.min(next, waypoints.size() - 1));
        return Optional.of(toward(position, goal, dt));
    }

    /** Velocity toward {@code goal} that can still stop on it within the acceleration limit. */
    Vec3 toward(Vec3 position, Vec3 goal, double dt) {
        Vec3 delta = goal.minus(position);
        double distance = delta.norm();
        if (distance < HOLD_TOLERANCE_M) {
            return Vec3.ZERO;
        }
        double speed = Math.min(maxSpeed, 0.9 * Math.sqrt(2 * maxAccel * distance));
        if (dt > 0) {
            speed = Math.min(speed, distance / (2 * dt));
        }
        return delta.times(speed / distance);
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.minus(a);
        double len2 = ab.e() * ab.e() + ab.n() * ab.n() + ab.u() * ab.u();
        if (len2 < 1e-12) {
            return p.distance(a);
        }
        Vec3 ap = p.minus(a);
        double t = (ap.e() * ab.e() + ap.n() * ab.n() + ap.u() * ab.u()) / len2;
        t = Math.max(0, Math.min(1, t));
        return p.distance(a.plus(ab.times(t)));
    }
}
