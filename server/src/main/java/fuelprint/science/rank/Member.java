package fuelprint.science.rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One of the 27 nuisance members: wind direction shift (-10/0/+10 degrees), speed factor (0.8/1/1.2) and source
 * height shift (-10/0/+10 m), applied together to every source of an explanation.
 */
public record Member(double directionShiftDeg, double speedFactor, double heightShiftM) {

    private static final List<Member> ALL;

    static {
        List<Member> list = new ArrayList<>();
        for (double d : new double[]{-10, 0, 10}) {
            for (double s : new double[]{0.8, 1.0, 1.2}) {
                for (double h : new double[]{-10, 0, 10}) {
                    list.add(new Member(d, s, h));
                }
            }
        }
        ALL = Collections.unmodifiableList(list);
    }

    /** The 27 members in a fixed order. */
    public static List<Member> all() {
        return ALL;
    }

    /** A physically impossible member (non-positive speed or release height) is rejected, never clipped. */
    public boolean possibleFor(double windSpeedMS, double sourceHeightM) {
        return windSpeedMS * speedFactor > 0 && sourceHeightM + heightShiftM > 0;
    }

    public String label() {
        return String.format(Locale.ROOT, "dir%+.0f/speedx%.1f/h%+.0f", directionShiftDeg, speedFactor, heightShiftM);
    }
}
