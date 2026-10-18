package fuelprint.field.vehicle;

import fuelprint.field.profile.WorldProfile;

/**
 * Drone power use (world-profile {@code vehicle}): hover 160 W + instruments 10 W + speed² W, where speed
 * is in m/s. Synthetic numbers, not a measured airframe.
 */
public final class Energy {

    private final double hoverW;
    private final double instrumentsW;

    public Energy(WorldProfile profile) {
        this(profile.hoverW(), profile.instrumentsW());
    }

    public Energy(double hoverW, double instrumentsW) {
        this.hoverW = hoverW;
        this.instrumentsW = instrumentsW;
    }

    /** Power in watts while flying at {@code speedMS}. */
    public double powerW(double speedMS) {
        return hoverW + instrumentsW + speedMS * speedMS;
    }

    /** Watt-hours used flying {@code seconds} at {@code speedMS}. */
    public double wattHours(double speedMS, double seconds) {
        return powerW(speedMS) * seconds / 3600.0;
    }
}
