package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.WindNow;
import org.junit.jupiter.api.Test;

/** The downwind rule of AGREEMENT section 13 and {@code alert-areas.json}. */
class DownwindAlertAreasTest {

    private final DownwindAlertAreas areas = new DownwindAlertAreas();

    private static WindNow from(double deg) {
        return new WindNow(deg, 3.0);
    }

    @Test
    void sc1TriggerAtN2WithWindFrom270WarnsExactlyAreaE() {
        assertThat(areas.downwindOf("N2", from(270))).containsExactly("AREA-E");
    }

    /**
     * Wind from 90 blows toward 270 (west). From N2 (120, 100) the West village centre (-1000, 0) lies at
     * bearing 264.9 degrees, 1124 m away: inside plus or minus 30 degrees of 270 and inside 2 km. The other
     * three centres are at 350.3, 187.6 and 93.7 degrees: all more than 30 degrees away. So the rule gives
     * AREA-W only, which is also what the task expected.
     */
    @Test
    void windFrom90WarnsOnlyAreaW() {
        assertThat(areas.downwindOf("N2", from(90))).containsExactly("AREA-W");
    }

    @Test
    void northSouthWinds() {
        // Wind from the south (180) blows north: only the North colony is downwind of N2.
        assertThat(areas.downwindOf("N2", from(180))).containsExactly("AREA-N");
        // Wind from the north (0) blows south: only the South village.
        assertThat(areas.downwindOf("N2", from(0))).containsExactly("AREA-S");
        // 360 is the same direction as 0.
        assertThat(areas.downwindOf("N2", from(360))).containsExactly("AREA-S");
    }

    @Test
    void theAngleLimitIsThirtyDegreesEitherSideOfDownwind() {
        // From N1 (-20, 60) the North colony centre (0, 800) is at bearing 1.55 degrees.
        double bearing = DownwindAlertAreas.bearingDeg(AlertGeography.SENSORS.get("N1"), AlertGeography.area("AREA-N").orElseThrow());
        assertThat(bearing).isBetween(1.4, 1.7);
        // downwind 30 (wind from 210): 28.45 degrees away -> warned
        assertThat(areas.downwindOf("N1", from(210))).contains("AREA-N");
        // downwind 35 (wind from 215): 33.45 degrees away -> not warned
        assertThat(areas.downwindOf("N1", from(215))).doesNotContain("AREA-N");
        // the same on the other side, across north: downwind 335 (wind from 155) is 26.55 away -> warned
        assertThat(areas.downwindOf("N1", from(155))).contains("AREA-N");
        // downwind 328 (wind from 148) is 33.55 away -> not warned
        assertThat(areas.downwindOf("N1", from(148))).doesNotContain("AREA-N");
    }

    @Test
    void windSpeedDoesNotChangeTheAnswer() {
        assertThat(areas.downwindOf("N2", new WindNow(270, 0.2))).containsExactly("AREA-E");
        assertThat(areas.downwindOf("N2", new WindNow(270, 12.0))).containsExactly("AREA-E");
    }

    @Test
    void everyAreaIsWithinTwoKilometresOfEveryBox() {
        // The 2 km limit is part of the rule; with the four fixed areas and boxes it never removes one.
        for (var site : AlertGeography.SENSORS.values()) {
            for (var area : AlertGeography.AREAS) {
                assertThat(Math.hypot(area.east() - site.east(), area.north() - site.north())).isLessThan(2000.0);
            }
        }
    }

    @Test
    void anUnknownBoxOrUnknownWindIsAnErrorNotAnEmptyAnswer() {
        assertThatThrownBy(() -> areas.downwindOf("N9", from(270))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> areas.downwindOf(null, from(270))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> areas.downwindOf("N2", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> areas.downwindOf("N2", new WindNow(Double.NaN, 3))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBuiltInPlacesMatchTheReferenceFiles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("contracts/AGREEMENT.md"))) {
            root = root.getParent();
        }
        JsonNode areaFile = mapper.readTree(root.resolve("contracts/reference/alert-areas.json").toFile());
        assertThat(areaFile.path("version").asText()).isEqualTo("FP-AREAS-1");
        assertThat(areaFile.path("warning_rule").path("downwind_half_angle_deg").asInt())
                .isEqualTo((int) DownwindAlertAreas.HALF_ANGLE_DEG);
        assertThat(areaFile.path("warning_rule").path("max_range_m").asInt()).isEqualTo((int) DownwindAlertAreas.MAX_RANGE_M);
        assertThat(areaFile.path("areas")).hasSize(AlertGeography.AREAS.size());
        for (JsonNode node : areaFile.path("areas")) {
            AlertGeography.Area area = AlertGeography.area(node.path("id").asText()).orElseThrow();
            assertThat(area.name()).isEqualTo(node.path("name").asText());
            assertThat(area.east()).isEqualTo(node.path("centre").path("east").asDouble());
            assertThat(area.north()).isEqualTo(node.path("centre").path("north").asDouble());
        }
        JsonNode world = mapper.readTree(root.resolve("contracts/reference/world-profile.json").toFile());
        assertThat(world.path("nodes")).hasSize(AlertGeography.SENSORS.size());
        for (JsonNode node : world.path("nodes")) {
            AlertGeography.Site site = AlertGeography.SENSORS.get(node.path("id").asText());
            assertThat(site).isNotNull();
            assertThat(site.east()).isEqualTo(node.path("east_m").asDouble());
            assertThat(site.north()).isEqualTo(node.path("north_m").asDouble());
        }
    }
}
