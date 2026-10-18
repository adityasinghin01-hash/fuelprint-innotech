package fuelprint.api;

import java.util.List;

/** Alerts: which fictional residential areas are downwind (AGREEMENT section 13). */
public interface AlertAreas {

    /** Area ids (like {@code AREA-E}) within plus or minus 30 degrees of downwind and 2 km of the box. */
    List<String> downwindOf(String sensorLabel, WindNow wind);
}
