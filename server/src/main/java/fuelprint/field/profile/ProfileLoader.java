package fuelprint.field.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.field.geo.Box;
import fuelprint.field.geo.Vec3;

/**
 * Reads the field numbers from the repository: {@code contracts/reference/world-profile.json} (vehicle,
 * clearance, sources, procedure times) and {@code data/context/derived-scene-crop.json} (context buildings).
 *
 * <p>Allocations made here because the reference files do not give them (each is a constant below):
 * the depot is at (0, 0, 2) (R3 doc 03, "Depot(0,0,2)"); a stack is a {@value #STACK_SIDE_M} m square
 * column at the source centre up to its {@code effective_height_m}; a shed is the source footprint up to
 * {@code roof_m}; a context building is the bounding box of its footprint up to its {@code height_m}
 * (8 m). Context buildings whose box comes within {@value #DEPOT_PAD_HALF_M} m of the depot are left out:
 * the depot pad is cleared ground, otherwise the drone could not take off.
 */
public final class ProfileLoader {

    public static final String WORLD_PROFILE = "contracts/reference/world-profile.json";
    public static final String SCENE_CROP = "data/context/derived-scene-crop.json";

    static final double STACK_SIDE_M = 4.0;
    static final double DEPOT_PAD_HALF_M = 12.0;
    static final Vec3 DEPOT = new Vec3(0, 0, 2);

    private ProfileLoader() {
    }

    /** Loads both files from {@code repoRoot}. */
    public static WorldProfile load(Path repoRoot) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode world = read(mapper, repoRoot.resolve(WORLD_PROFILE));
        JsonNode crop = read(mapper, repoRoot.resolve(SCENE_CROP));
        return fromJson(world, crop);
    }

    static WorldProfile fromJson(JsonNode world, JsonNode crop) {
        JsonNode vehicle = required(world, "vehicle");
        JsonNode clearance = required(world, "clearance");
        JsonNode procedure = required(world, "default_procedure_s");

        List<Box> boxes = new ArrayList<>();
        for (JsonNode source : required(world, "sources")) {
            String id = source.path("id").asText();
            double e = number(source, "east_m");
            double n = number(source, "north_m");
            double halfE = number(source.path("footprint_m"), 0) / 2;
            double halfN = number(source.path("footprint_m"), 1) / 2;
            boxes.add(new Box(id + "-SHED", "SHED", e - halfE, e + halfE, n - halfN, n + halfN, 0,
                    number(source, "roof_m")));
            double s = STACK_SIDE_M / 2;
            boxes.add(new Box(id + "-STACK", "STACK", e - s, e + s, n - s, n + s, 0,
                    number(source, "effective_height_m")));
        }

        int loaded = 0;
        int cleared = 0;
        for (JsonNode building : required(crop, "buildings")) {
            Box box = contextBox(building);
            loaded++;
            if (box.maxE() >= DEPOT.e() - DEPOT_PAD_HALF_M && box.minE() <= DEPOT.e() + DEPOT_PAD_HALF_M
                    && box.maxN() >= DEPOT.n() - DEPOT_PAD_HALF_M && box.minN() <= DEPOT.n() + DEPOT_PAD_HALF_M) {
                cleared++;
                continue;
            }
            boxes.add(box);
        }

        JsonNode xy = required(vehicle, "aerial_xy_m");
        JsonNode z = required(vehicle, "aerial_z_m");
        WorldProfile.Procedure stages = new WorldProfile.Procedure(
                number(procedure, "setup"), number(procedure, "background"), number(procedure, "purge"),
                number(procedure, "minimum_settle"), number(procedure, "online_valid_collection"),
                number(procedure, "minimum_recovery"), number(procedure, "review"));
        return new WorldProfile(number(vehicle, "aerial_m_s"), number(clearance, "acceleration_m_s2"),
                number(xy, 0), number(xy, 1), number(z, 0), number(z, 1),
                number(vehicle, "usable_Wh"), number(vehicle, "reserve_Wh"), number(vehicle, "hover_W"),
                number(vehicle, "instruments_W"), number(clearance, "aerial_obstacle_m"),
                number(clearance, "vehicle_radius_m"), DEPOT, boxes, loaded, cleared, stages);
    }

    private static Box contextBox(JsonNode building) {
        double minE = Double.POSITIVE_INFINITY;
        double maxE = Double.NEGATIVE_INFINITY;
        double minN = Double.POSITIVE_INFINITY;
        double maxN = Double.NEGATIVE_INFINITY;
        for (JsonNode point : required(building, "footprint_enu_m")) {
            minE = Math.min(minE, number(point, 0));
            maxE = Math.max(maxE, number(point, 0));
            minN = Math.min(minN, number(point, 1));
            maxN = Math.max(maxN, number(point, 1));
        }
        return new Box(building.path("id").asText(), "CONTEXT", minE, maxE, minN, maxN, 0,
                number(building, "height_m"));
    }

    /** Walks up from {@code start} to the folder that holds {@code contracts/AGREEMENT.md} and {@code server/}. */
    public static Path findRepoRoot(Path start) {
        for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("contracts").resolve("AGREEMENT.md"))
                    && Files.isDirectory(dir.resolve("server"))) {
                return dir;
            }
        }
        throw new IllegalStateException("Cannot find the repository root (contracts/AGREEMENT.md) above " + start);
    }

    private static JsonNode read(ObjectMapper mapper, Path file) {
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Profile field '" + field + "' is missing");
        }
        return value;
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isNumber()) {
            throw new IllegalStateException("Profile field '" + field + "' is not a number");
        }
        return value.asDouble();
    }

    private static double number(JsonNode array, int index) {
        JsonNode value = array.get(index);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("Profile array item " + index + " is not a number");
        }
        return value.asDouble();
    }
}
