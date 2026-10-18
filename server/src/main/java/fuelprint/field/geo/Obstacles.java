package fuelprint.field.geo;

import java.util.List;

/**
 * Every solid volume the drone must keep clear of, already grown by the clearance (2 m) plus the vehicle
 * radius (0.5 m), so the drone's centre point only has to stay outside the grown boxes.
 */
public final class Obstacles {

    private final List<Box> raw;
    private final List<Box> grown;
    private final double margin;

    /**
     * @param raw    the solid boxes
     * @param margin clearance plus vehicle radius, metres
     */
    public Obstacles(List<Box> raw, double margin) {
        this.raw = List.copyOf(raw);
        this.margin = margin;
        this.grown = this.raw.stream().map(b -> b.inflate(margin)).toList();
    }

    public List<Box> raw() {
        return raw;
    }

    public double margin() {
        return margin;
    }

    /** True if a drone centred at {@code p} would be closer than the clearance to any box. */
    public boolean blocked(Vec3 p) {
        for (Box box : grown) {
            if (box.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** True if the straight move from {@code a} to {@code b} keeps the clearance the whole way. */
    public boolean clear(Vec3 a, Vec3 b) {
        for (Box box : grown) {
            if (box.hitBy(a, b)) {
                return false;
            }
        }
        return true;
    }

    /** The highest grown top of any box whose grown footprint the ground track from {@code a} to {@code b} crosses, or 0. */
    public double highestTopUnder(Vec3 a, Vec3 b) {
        double top = 0;
        for (Box box : grown) {
            if (box.maxU() > top && box.footprintHitBy(a, b)) {
                top = box.maxU();
            }
        }
        return top;
    }
}
