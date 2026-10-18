package fuelprint.field.geo;

/**
 * An axis-aligned box in ENU metres: a factory shed, a stack or a context building.
 *
 * @param id   like {@code S02-SHED}, {@code S02-STACK} or the context building id
 * @param kind SHED, STACK or CONTEXT
 */
public record Box(String id, String kind, double minE, double maxE, double minN, double maxN, double minU,
                  double maxU) {

    public Box {
        if (!(minE <= maxE && minN <= maxN && minU <= maxU)) {
            throw new IllegalArgumentException("Box " + id + " has min above max");
        }
    }

    /** This box grown by {@code m} metres on every side. */
    public Box inflate(double m) {
        return new Box(id, kind, minE - m, maxE + m, minN - m, maxN + m, minU - m, maxU + m);
    }

    /** True if {@code p} is inside or on the surface. */
    public boolean contains(Vec3 p) {
        return p.e() >= minE && p.e() <= maxE && p.n() >= minN && p.n() <= maxN && p.u() >= minU && p.u() <= maxU;
    }

    /**
     * True if the straight segment from {@code a} to {@code b} touches this box anywhere (slab test).
     * Touching the surface counts as a hit, so the answer is conservative.
     */
    public boolean hitBy(Vec3 a, Vec3 b) {
        double[] t = {0, 1};
        return slab(a.e(), b.e() - a.e(), minE, maxE, t)
                && slab(a.n(), b.n() - a.n(), minN, maxN, t)
                && slab(a.u(), b.u() - a.u(), minU, maxU, t);
    }

    /** True if the ground track of the segment from {@code a} to {@code b} crosses this box's footprint. */
    public boolean footprintHitBy(Vec3 a, Vec3 b) {
        double[] t = {0, 1};
        return slab(a.e(), b.e() - a.e(), minE, maxE, t) && slab(a.n(), b.n() - a.n(), minN, maxN, t);
    }

    private static boolean slab(double start, double delta, double min, double max, double[] t) {
        if (Math.abs(delta) < 1e-12) {
            return start >= min && start <= max;
        }
        double tA = (min - start) / delta;
        double tB = (max - start) / delta;
        if (tA > tB) {
            double swap = tA;
            tA = tB;
            tB = swap;
        }
        t[0] = Math.max(t[0], tA);
        t[1] = Math.min(t[1], tB);
        return t[0] <= t[1];
    }
}
