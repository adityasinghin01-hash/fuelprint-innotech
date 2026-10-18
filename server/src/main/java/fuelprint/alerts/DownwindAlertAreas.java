package fuelprint.alerts;

import java.util.ArrayList;
import java.util.List;

import fuelprint.api.AlertAreas;
import fuelprint.api.WindNow;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The downwind rule of AGREEMENT section 13: an area is warned when its centre lies within plus or
 * minus 30 degrees of the downwind direction ({@code wind_from_deg + 180}, clockwise from north), seen
 * from the sensor box that raised the trigger, and no more than 2 km away. Both limits are inclusive.
 * The wind speed is not used.
 */
@Component
@Profile("!stub")
public class DownwindAlertAreas implements AlertAreas {

    static final double HALF_ANGLE_DEG = 30.0;
    static final double MAX_RANGE_M = 2000.0;
    private static final double EPSILON = 1e-9;

    /**
     * @throws IllegalArgumentException if {@code sensorLabel} is not N1 to N4 or {@code wind} is null
     *                                  (a wrong input is a bug, not "no area is downwind")
     */
    @Override
    public List<String> downwindOf(String sensorLabel, WindNow wind) {
        AlertGeography.Site site = sensorLabel == null ? null : AlertGeography.SENSORS.get(sensorLabel);
        if (site == null) {
            throw new IllegalArgumentException("Unknown sensor box: " + sensorLabel);
        }
        if (wind == null || Double.isNaN(wind.fromDeg()) || Double.isInfinite(wind.fromDeg())) {
            throw new IllegalArgumentException("The wind direction is unknown; no area can be chosen");
        }
        double downwind = normalise(wind.fromDeg() + 180.0);
        List<String> warned = new ArrayList<>();
        for (AlertGeography.Area area : AlertGeography.AREAS) {
            double dEast = area.east() - site.east();
            double dNorth = area.north() - site.north();
            double distance = Math.hypot(dEast, dNorth);
            if (distance > MAX_RANGE_M + EPSILON) {
                continue;
            }
            if (distance < EPSILON) {
                warned.add(area.id()); // the area centre is on the box itself: it is downwind of it
                continue;
            }
            double bearing = normalise(Math.toDegrees(Math.atan2(dEast, dNorth)));
            if (angleBetween(bearing, downwind) <= HALF_ANGLE_DEG + EPSILON) {
                warned.add(area.id());
            }
        }
        return List.copyOf(warned);
    }

    /** Bearing of {@code area} seen from {@code sensor}, in degrees clockwise from north (for tests and docs). */
    static double bearingDeg(AlertGeography.Site site, AlertGeography.Area area) {
        return normalise(Math.toDegrees(Math.atan2(area.east() - site.east(), area.north() - site.north())));
    }

    private static double normalise(double degrees) {
        double d = degrees % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    /** Smallest difference between two bearings, 0 to 180. */
    private static double angleBetween(double a, double b) {
        double diff = Math.abs(a - b) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }
}
