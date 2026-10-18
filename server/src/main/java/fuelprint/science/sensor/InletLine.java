package fuelprint.science.sensor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The inlet line (1.5 m x 4 mm ID = 18.85 mL). Air moves through it by the volume actually sampled, not by a
 * fixed delay: when the flow changes or stops, the transport follows. A parcel enters at the inlet and leaves
 * after 18.85 mL more air has been drawn behind it.
 */
public final class InletLine {

    public static final double VOLUME_L = Math.PI * Math.pow(0.002, 2) * 1.5 * 1000.0; // 0.0188496 L

    private record Parcel(double concentration, double volumeL) {
    }

    private final Deque<Parcel> parcels = new ArrayDeque<>();
    private double heldL;

    /** Starts full of air at {@code concentration}. */
    public InletLine(double concentration) {
        parcels.addLast(new Parcel(concentration, VOLUME_L));
        heldL = VOLUME_L;
    }

    /**
     * Draws {@code volumeL} of air at {@code inletConcentration} in at the inlet and returns the volume-weighted
     * concentration that left the line at the instrument end. With no volume drawn nothing leaves and the result
     * is NaN (the instrument keeps its last state).
     */
    public double push(double inletConcentration, double volumeL) {
        if (volumeL <= 0) {
            return Double.NaN;
        }
        parcels.addLast(new Parcel(inletConcentration, volumeL));
        heldL += volumeL;
        double out = 0;
        double outL = 0;
        while (heldL - VOLUME_L > 1e-15) {
            Parcel head = parcels.peekFirst();
            double excess = heldL - VOLUME_L;
            if (head.volumeL() <= excess + 1e-15) {
                parcels.pollFirst();
                out += head.concentration() * head.volumeL();
                outL += head.volumeL();
                heldL -= head.volumeL();
            } else {
                parcels.pollFirst();
                parcels.addFirst(new Parcel(head.concentration(), head.volumeL() - excess));
                out += head.concentration() * excess;
                outL += excess;
                heldL -= excess;
            }
        }
        return outL > 0 ? out / outL : Double.NaN;
    }

    public double heldL() {
        return heldL;
    }
}
