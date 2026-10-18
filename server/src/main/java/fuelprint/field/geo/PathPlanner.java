package fuelprint.field.geo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plans a safe straight-line path between two points for the autopilot. If the direct line is blocked it
 * climbs straight up to a cruise height above everything under the ground track (plus a 1 m margin),
 * crosses at that height and then goes straight down or up to the end point. A path that still is not
 * clear, or an end point inside the clearance of a box or outside the area, is refused.
 */
public final class PathPlanner {

    static final double CRUISE_MARGIN_M = 1.0;

    private final Obstacles obstacles;
    private final Area area;

    public PathPlanner(Obstacles obstacles, Area area) {
        this.obstacles = obstacles;
        this.area = area;
    }

    /** The waypoints after {@code from} that end at {@code to}, or empty if no safe path exists. */
    public Optional<List<Vec3>> plan(Vec3 from, Vec3 to) {
        if (!area.contains(to) || obstacles.blocked(to)) {
            return Optional.empty();
        }
        if (obstacles.clear(from, to)) {
            return Optional.of(List.of(to));
        }
        double cruise = Math.max(Math.max(from.u(), to.u()), obstacles.highestTopUnder(from, to) + CRUISE_MARGIN_M);
        if (cruise > area.zMax()) {
            return Optional.empty();
        }
        List<Vec3> path = new ArrayList<>();
        Vec3 up = new Vec3(from.e(), from.n(), cruise);
        Vec3 across = new Vec3(to.e(), to.n(), cruise);
        Vec3 last = from;
        for (Vec3 point : List.of(up, across, to)) {
            if (point.distance(last) < 1e-6) {
                continue;
            }
            if (!obstacles.clear(last, point)) {
                return Optional.empty();
            }
            path.add(point);
            last = point;
        }
        return Optional.of(List.copyOf(path));
    }

    /** {@link #plan} through every point of {@code points} in order, or empty if any leg has no safe path. */
    public Optional<List<Vec3>> planThrough(Vec3 from, List<Vec3> points) {
        List<Vec3> all = new ArrayList<>();
        Vec3 last = from;
        for (Vec3 point : points) {
            Optional<List<Vec3>> leg = plan(last, point);
            if (leg.isEmpty()) {
                return Optional.empty();
            }
            all.addAll(leg.get());
            last = point;
        }
        return Optional.of(List.copyOf(all));
    }

    /** Length in metres of the path from {@code from} through {@code waypoints}. */
    public static double length(Vec3 from, List<Vec3> waypoints) {
        double total = 0;
        Vec3 last = from;
        for (Vec3 point : waypoints) {
            total += last.distance(point);
            last = point;
        }
        return total;
    }
}
