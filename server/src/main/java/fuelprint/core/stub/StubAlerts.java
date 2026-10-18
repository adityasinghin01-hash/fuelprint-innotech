package fuelprint.core.stub;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.api.AlertAreas;
import fuelprint.api.Enu;
import fuelprint.api.GatewayMode;
import fuelprint.api.SendResult;
import fuelprint.api.SendStatus;
import fuelprint.api.SmsGateway;
import fuelprint.api.WindNow;
import fuelprint.core.world.WorldProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Stub alerts: {@link AlertAreas} with the real rule of AGREEMENT section 13 (area centre within plus or minus 30
 * degrees of downwind and 2 km of the box, from {@code alert-areas.json}), and an {@link SmsGateway} that is always
 * LOG_ONLY and logs only a masked number.
 */
@Component
@Profile("stub")
public class StubAlerts implements AlertAreas, SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(StubAlerts.class);

    private record Area(String id, double east, double north) {
    }

    private final List<Area> areas = new ArrayList<>();
    private final double halfAngleDeg;
    private final double maxRangeM;
    private final Map<String, Enu> boxes = new java.util.HashMap<>();

    public StubAlerts(WorldProfile world) {
        JsonNode file;
        try (InputStream in = new ClassPathResource("reference/alert-areas.json").getInputStream()) {
            file = Fixtures.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (JsonNode area : file.path("areas")) {
            areas.add(new Area(area.path("id").asText(), area.path("centre").path("east").asDouble(),
                    area.path("centre").path("north").asDouble()));
        }
        halfAngleDeg = file.path("warning_rule").path("downwind_half_angle_deg").asDouble(30);
        maxRangeM = file.path("warning_rule").path("max_range_m").asDouble(2000);
        world.nodes().forEach(n -> boxes.put(n.label(), n.inlet()));
    }

    @Override
    public List<String> downwindOf(String sensorLabel, WindNow wind) {
        Enu box = Optional.ofNullable(boxes.get(sensorLabel)).orElse(null);
        if (box == null || wind == null) {
            return List.of();
        }
        double downwind = (wind.fromDeg() + 180) % 360;
        List<String> hit = new ArrayList<>();
        for (Area area : areas) {
            double de = area.east() - box.east();
            double dn = area.north() - box.north();
            double distance = Math.hypot(de, dn);
            double bearing = (Math.toDegrees(Math.atan2(de, dn)) + 360) % 360;
            double diff = Math.abs(((bearing - downwind) + 540) % 360 - 180);
            if (distance <= maxRangeM && diff <= halfAngleDeg) {
                hit.add(area.id());
            }
        }
        return hit;
    }

    @Override
    public SendResult send(String phoneE164, String text) {
        String digits = phoneE164 == null ? "" : phoneE164.replaceAll("\\D", "");
        String masked = digits.length() >= 12 ? "+91 " + digits.substring(2, 4) + "xxxxxx" + digits.substring(digits.length() - 2)
                : "(masked)";
        log.info("SMS LOG_ONLY (nothing sent) to {}: {}", masked, text);
        return new SendResult(SendStatus.LOGGED_ONLY, GatewayMode.LOG_ONLY, 1, null);
    }
}
