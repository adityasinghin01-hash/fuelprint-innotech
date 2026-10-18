package fuelprint.alerts;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed places the alerts need: the four fictional residential areas
 * ({@code contracts/reference/alert-areas.json}, FP-AREAS-1) and the four sensor boxes
 * ({@code contracts/reference/world-profile.json}), all in ENU metres from the map origin.
 * A test compares these constants with the two reference files, so they cannot drift.
 */
final class AlertGeography {

    /** One fictional residential area. {@code east} and {@code north} are its centre. */
    record Area(String id, String name, double east, double north) {
    }

    /** A sensor box position in ENU metres. */
    record Site(double east, double north) {
    }

    static final List<Area> AREAS = List.of(
            new Area("AREA-W", "West village", -1000, 0),
            new Area("AREA-N", "North colony", 0, 800),
            new Area("AREA-E", "East colony", 900, 50),
            new Area("AREA-S", "South village", 0, -850));

    static final Map<String, Site> SENSORS = Map.of(
            "N1", new Site(-20, 60),
            "N2", new Site(120, 100),
            "N3", new Site(280, 140),
            "N4", new Site(400, -100));

    private AlertGeography() {
    }

    static Optional<Area> area(String id) {
        return AREAS.stream().filter(area -> area.id().equals(id)).findFirst();
    }

    static boolean isArea(String id) {
        return area(id).isPresent();
    }
}
