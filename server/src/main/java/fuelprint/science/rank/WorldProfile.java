package fuelprint.science.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.Enu;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The public world registry (FP-WORLD-3): source positions and heights, node inlets, weather constants, noise,
 * vehicle, limits and procedure times. A byte copy of {@code contracts/reference/world-profile.json} kept as a
 * classpath resource (a test checks the copy matches the contract). Public data only, never hidden truth.
 */
public final class WorldProfile {

    public static final String RESOURCE = "reference/world-profile.json";

    /** A registered source: position and effective release height, roof and footprint for safety checks. */
    public record Source(String id, double east, double north, double heightM, double roofM,
                         double footprintEastM, double footprintNorthM) {
    }

    private static volatile WorldProfile defaultProfile;

    private final JsonNode root;
    private final List<Source> sources;
    private final Map<String, Enu> nodeInlets;

    private WorldProfile(JsonNode root) {
        this.root = root;
        List<Source> list = new ArrayList<>();
        for (JsonNode s : root.path("sources")) {
            list.add(new Source(s.path("id").asText(), s.path("east_m").asDouble(), s.path("north_m").asDouble(),
                    s.path("effective_height_m").asDouble(), s.path("roof_m").asDouble(),
                    s.path("footprint_m").path(0).asDouble(), s.path("footprint_m").path(1).asDouble()));
        }
        this.sources = Collections.unmodifiableList(list);
        Map<String, Enu> nodes = new LinkedHashMap<>();
        for (JsonNode n : root.path("nodes")) {
            nodes.put(n.path("id").asText(),
                    new Enu(n.path("east_m").asDouble(), n.path("north_m").asDouble(), n.path("inlet_m").asDouble()));
        }
        this.nodeInlets = Collections.unmodifiableMap(nodes);
    }

    /** The profile shipped with the server. */
    public static WorldProfile load() {
        WorldProfile p = defaultProfile;
        if (p == null) {
            try (InputStream in = WorldProfile.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Missing classpath resource " + RESOURCE);
                }
                p = new WorldProfile(new ObjectMapper().readTree(in));
                defaultProfile = p;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return p;
    }

    public String version() {
        return root.path("version").asText();
    }

    public List<Source> sources() {
        return sources;
    }

    public Map<String, Enu> nodeInlets() {
        return nodeInlets;
    }

    public JsonNode raw() {
        return root;
    }

    public double nominal(String key) {
        return required(root.path("nominal").path(key), "nominal." + key);
    }

    public double vehicle(String key) {
        return required(root.path("vehicle").path(key), "vehicle." + key);
    }

    public double noise(String key) {
        return required(root.path("noise").path(key), "noise." + key);
    }

    public double commonBackground(String key) {
        return required(root.path("noise").path("common_background").path(key), "noise.common_background." + key);
    }

    public int limit(String key) {
        return (int) required(root.path("limits").path(key), "limits." + key);
    }

    public double procedureS(String key) {
        return required(root.path("default_procedure_s").path(key), "default_procedure_s." + key);
    }

    public double clearance(String key) {
        return required(root.path("clearance").path(key), "clearance." + key);
    }

    private static double required(JsonNode node, String name) {
        if (!node.isNumber()) {
            throw new IllegalStateException("world-profile.json is missing number " + name);
        }
        return node.asDouble();
    }
}
