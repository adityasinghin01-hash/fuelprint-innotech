package fuelprint.field.geo;

/** The allowed flying area: east and north within [xyMin, xyMax], height within [zMin, zMax]. */
public record Area(double xyMin, double xyMax, double zMin, double zMax) {

    public boolean contains(Vec3 p) {
        return p.e() >= xyMin && p.e() <= xyMax && p.n() >= xyMin && p.n() <= xyMax
                && p.u() >= zMin && p.u() <= zMax;
    }
}
