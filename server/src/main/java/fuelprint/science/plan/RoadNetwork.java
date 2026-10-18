package fuelprint.science.plan;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.science.rank.WorldProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * The estate road graph from world-profile.json: the outer rectangle, the central vertical and horizontal roads and
 * the spur. Ground vehicles move only on it; a target is reachable only if it lies on a road (there is no default
 * connector to points such as (280, 140)). Shortest allowed path by Dijkstra.
 */
public final class RoadNetwork {

    /** How far a ground target may be from a road centre line and still count as on the road. */
    public static final double ON_ROAD_M = 4.0;
    /** How far the vehicle's current position may be from the road (it drives straight onto it). */
    public static final double START_SNAP_M = 25.0;

    /** An axis-aligned road centre line from (x1, y1) to (x2, y2). */
    record Segment(double x1, double y1, double x2, double y2) {
        boolean horizontal() {
            return y1 == y2;
        }

        double[] nearest(double x, double y) {
            double nx = Math.max(Math.min(x1, x2), Math.min(Math.max(x1, x2), x));
            double ny = Math.max(Math.min(y1, y2), Math.min(Math.max(y1, y2), y));
            return new double[]{nx, ny};
        }

        boolean contains(double x, double y) {
            double[] n = nearest(x, y);
            return Math.abs(n[0] - x) < 1e-9 && Math.abs(n[1] - y) < 1e-9;
        }
    }

    private final List<Segment> segments;

    public RoadNetwork(List<Segment> segments) {
        this.segments = List.copyOf(segments);
    }

    public static RoadNetwork of(WorldProfile world) {
        JsonNode r = world.raw().path("roads");
        double x0 = r.path("rectangle_x_m").path(0).asDouble();
        double x1 = r.path("rectangle_x_m").path(1).asDouble();
        double y0 = r.path("rectangle_y_m").path(0).asDouble();
        double y1 = r.path("rectangle_y_m").path(1).asDouble();
        double cx = r.path("central_vertical_x_m").asDouble();
        double cy = r.path("central_horizontal_y_m").asDouble();
        JsonNode spur = r.path("spur");
        List<Segment> s = new ArrayList<>();
        s.add(new Segment(x0, y0, x1, y0));
        s.add(new Segment(x0, y1, x1, y1));
        s.add(new Segment(x0, y0, x0, y1));
        s.add(new Segment(x1, y0, x1, y1));
        s.add(new Segment(cx, y0, cx, y1));
        s.add(new Segment(x0, cy, x1, cy));
        s.add(new Segment(spur.path("x_m").path(0).asDouble(), spur.path("y_m").asDouble(),
                spur.path("x_m").path(1).asDouble(), spur.path("y_m").asDouble()));
        return new RoadNetwork(s);
    }

    /** Nearest road point within {@code maxM}, if any. */
    public Optional<double[]> snap(double x, double y, double maxM) {
        double[] best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Segment s : segments) {
            double[] n = s.nearest(x, y);
            double d = Math.hypot(n[0] - x, n[1] - y);
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return bestD <= maxM ? Optional.of(best) : Optional.empty();
    }

    /**
     * Shortest driving distance from the vehicle position to a ground target, including the straight drive onto the
     * road; empty when the target is not on a road or the vehicle is too far from one.
     */
    public Optional<Double> distance(double fromX, double fromY, double toX, double toY) {
        Optional<double[]> start = snap(fromX, fromY, START_SNAP_M);
        Optional<double[]> end = snap(toX, toY, ON_ROAD_M);
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        double lead = Math.hypot(start.get()[0] - fromX, start.get()[1] - fromY);
        double tail = Math.hypot(end.get()[0] - toX, end.get()[1] - toY);
        List<double[]> nodes = new ArrayList<>();
        nodes.add(start.get());
        nodes.add(end.get());
        for (Segment s : segments) {
            nodes.add(new double[]{s.x1(), s.y1()});
            nodes.add(new double[]{s.x2(), s.y2()});
            for (Segment t : segments) {
                if (s.horizontal() && !t.horizontal()) {
                    double x = t.x1();
                    double y = s.y1();
                    if (s.contains(x, y) && t.contains(x, y)) {
                        nodes.add(new double[]{x, y});
                    }
                }
            }
        }
        int n = nodes.size();
        List<List<double[]>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (Segment s : segments) {
            List<Integer> on = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (s.contains(nodes.get(i)[0], nodes.get(i)[1])) {
                    on.add(i);
                }
            }
            on.sort((a, b) -> s.horizontal()
                    ? Double.compare(nodes.get(a)[0], nodes.get(b)[0])
                    : Double.compare(nodes.get(a)[1], nodes.get(b)[1]));
            for (int k = 1; k < on.size(); k++) {
                int a = on.get(k - 1);
                int b = on.get(k);
                double d = Math.hypot(nodes.get(a)[0] - nodes.get(b)[0], nodes.get(a)[1] - nodes.get(b)[1]);
                adj.get(a).add(new double[]{b, d});
                adj.get(b).add(new double[]{a, d});
            }
        }
        double[] dist = new double[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[0] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        q.add(new double[]{0, 0});
        while (!q.isEmpty()) {
            double[] cur = q.poll();
            int u = (int) cur[0];
            if (cur[1] > dist[u]) {
                continue;
            }
            for (double[] e : adj.get(u)) {
                int v = (int) e[0];
                double nd = dist[u] + e[1];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    q.add(new double[]{v, nd});
                }
            }
        }
        return Double.isFinite(dist[1]) ? Optional.of(lead + dist[1] + tail) : Optional.empty();
    }
}
