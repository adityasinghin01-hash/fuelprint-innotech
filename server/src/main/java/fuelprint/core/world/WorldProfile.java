package fuelprint.core.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.Enu;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The parts of {@code contracts/reference/world-profile.json} the core needs (bundled as
 * {@code classpath:reference/world-profile.json} by the pom): the four fixed sensor boxes and the clock step.
 */
@Component
public class WorldProfile {

    /** The channels every fixed box measures (AGREEMENT section 6 {@code sensor.channels}). */
    public static final List<String> SENSOR_CHANNELS = List.of("SO2_ppb", "CO2_ppm", "PM_ugm3");

    /** One fixed sensor box: label N1 to N4 and its inlet (up = inlet height above ground). */
    public record Node(String label, Enu inlet) {
    }

    private final List<Node> nodes;
    private final double dtS;

    public WorldProfile() {
        this(load("reference/world-profile.json"));
    }

    WorldProfile(JsonNode profile) {
        List<Node> read = new ArrayList<>();
        for (JsonNode node : profile.path("nodes")) {
            read.add(new Node(node.path("id").asText(), new Enu(node.path("east_m").asDouble(),
                    node.path("north_m").asDouble(), node.path("inlet_m").asDouble())));
        }
        if (read.isEmpty()) {
            throw new IllegalStateException("world-profile.json lists no sensor nodes");
        }
        this.nodes = List.copyOf(read);
        this.dtS = profile.path("clock").path("dt_s").asDouble(0.05);
    }

    static JsonNode load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath:" + path, e);
        }
    }

    /** The fixed sensor boxes N1 to N4, in file order. */
    public List<Node> nodes() {
        return nodes;
    }

    /** Simulation seconds per world step at time scale 1 (0.05). */
    public double dtS() {
        return dtS;
    }
}
