package fuelprint.field.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.geo.Vec3;

/**
 * Field's read of one {@code mission} record revision (AGREEMENT section 6; ops creates it, field moves it).
 *
 * @param route the approved route points, may be empty
 */
public record MissionView(String id, String runId, long revision, String state, String vehicleId, Vec3 target,
                          List<Vec3> route, String methodId, List<String> channelIds, String pilot) {

    public static final String APPROVED = "APPROVED";
    public static final String LEASED = "LEASED";
    public static final String OUTBOUND = "OUTBOUND";
    public static final String AT_TARGET = "AT_TARGET";
    public static final String COLLECTING = "COLLECTING";
    public static final String RETURNING = "RETURNING";
    public static final String COMPLETE = "COMPLETE";
    public static final String INTERRUPTED = "INTERRUPTED";

    /** States in which field flies the mission (APPROVED to RETURNING). */
    public static final Set<String> ACTIVE = Set.of(APPROVED, LEASED, OUTBOUND, AT_TARGET, COLLECTING, RETURNING);
    /** States after which nothing changes any more. */
    public static final Set<String> TERMINAL = Set.of(COMPLETE, INTERRUPTED, "EXPIRED", "REJECTED");

    public MissionView {
        route = List.copyOf(route);
        channelIds = List.copyOf(channelIds);
    }

    public boolean active() {
        return ACTIVE.contains(state);
    }

    /** The mission in {@code record}, or empty if it has no usable target or vehicle. */
    public static Optional<MissionView> of(StoredRecord record) {
        ObjectNode body = record.body();
        Optional<Vec3> target = point(body.get("target"));
        String vehicle = body.path("vehicle_id").asText(null);
        if (target.isEmpty() || vehicle == null) {
            return Optional.empty();
        }
        List<Vec3> route = new ArrayList<>();
        for (JsonNode p : body.path("route")) {
            point(p).ifPresent(route::add);
        }
        List<String> channels = new ArrayList<>();
        body.path("channel_ids").forEach(c -> channels.add(c.asText()));
        return Optional.of(new MissionView(record.id(), record.runId(), record.revision(),
                body.path("state").asText(""), vehicle, target.get(), route, body.path("method_id").asText(null),
                channels, body.path("pilot").asText("NONE")));
    }

    static Optional<Vec3> point(JsonNode node) {
        if (node == null || !node.path("east").isNumber() || !node.path("north").isNumber()
                || !node.path("up").isNumber()) {
            return Optional.empty();
        }
        return Optional.of(new Vec3(node.get("east").asDouble(), node.get("north").asDouble(), node.get("up").asDouble()));
    }
}
