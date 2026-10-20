package fuelprint.ops;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.WindNow;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;

/** Versioned public dependencies. Object keys sort lexically; numbers use plain minimal decimals. */
final class MissionDependencies {
    private MissionDependencies() {}

    static long directionBucket(double degrees) {
        if (!Double.isFinite(degrees)) throw new IllegalArgumentException("Wind direction is unknown.");
        double normalized = ((degrees % 360) + 360) % 360;
        return (long) Math.floor((normalized + 5) / 10) % 36;
    }

    static long speedBucket(double speed) {
        if (!Double.isFinite(speed) || speed < 0 || speed > Long.MAX_VALUE / 2.0)
            throw new IllegalArgumentException("Wind speed is unknown or invalid.");
        return (long) Math.floor(speed * 2 + .5);
    }

    static String hash(JsonNode dependencies) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(dependencies).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String canonical(JsonNode node) {
        if (node == null || node.isMissingNode()) throw new IllegalArgumentException("A dependency is missing.");
        if (node.isObject()) {
            List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo);
            List<String> values = new ArrayList<>();
            for (String key : keys) values.add(OpsRecords.object().textNode(key).toString() + ":" + canonical(node.get(key)));
            return "{" + String.join(",", values) + "}";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>(); node.forEach(n -> values.add(canonical(n)));
            return "[" + String.join(",", values) + "]";
        }
        if (node.isNumber()) {
            if (!Double.isFinite(node.doubleValue())) throw new IllegalArgumentException("A numeric dependency is unknown.");
            return new BigDecimal(node.asText()).stripTrailingZeros().toPlainString();
        }
        return node.toString();
    }

    static void point(JsonNode node) {
        for (String key : List.of("east", "north", "up")) {
            if (node == null || !node.path(key).isNumber() || !Double.isFinite(node.path(key).doubleValue()))
                throw new IllegalArgumentException("A route/target coordinate is unknown.");
        }
    }

    static StoredRecord investigation(StoreView store, ObjectNode mission, String run) {
        boolean survey = mission.hasNonNull("survey_id"), proposal = mission.hasNonNull("proposal_id");
        if (survey == proposal) throw new IllegalArgumentException("Choose exactly one same-run proposal or survey.");
        StoredRecord basis = OpsRecords.sameRun(store, survey ? "survey" : "proposal",
                OpsRecords.text(mission, survey ? "survey_id" : "proposal_id"), run);
        if (basis == null) throw new IllegalArgumentException("The mission basis is missing or belongs to another run.");
        String investigationId = OpsRecords.text(basis.body(), "investigation_id");
        if (proposal) {
            StoredRecord analysis = OpsRecords.sameRun(store, "analysis", OpsRecords.text(basis.body(), "analysis_id"), run);
            if (analysis == null || !java.util.Objects.equals(investigationId, OpsRecords.text(analysis.body(), "investigation_id")))
                throw new IllegalArgumentException("The proposal analysis lineage is invalid.");
        }
        StoredRecord investigation = OpsRecords.sameRun(store, "investigation", investigationId, run);
        if (investigation == null || "CLOSED".equals(investigation.body().path("state").asText()))
            throw new IllegalArgumentException("The mission investigation is unavailable or closed.");
        return investigation;
    }

    static ObjectNode current(StoreView store, ObjectNode mission, String run, WindNow wind) {
        if (wind == null) throw new IllegalArgumentException("Current wind is unknown.");
        StoredRecord investigation = investigation(store, mission, run);
        boolean survey = mission.hasNonNull("survey_id");
        StoredRecord basis = OpsRecords.sameRun(store, survey ? "survey" : "proposal",
                OpsRecords.text(mission, survey ? "survey_id" : "proposal_id"), run);
        List<String> history = OpsRecords.strings(investigation.body().path("analysis_ids"));
        StoredRecord analysis = history.isEmpty() ? null : OpsRecords.sameRun(store, "analysis", history.get(history.size()-1), run);
        if (!history.isEmpty() && (analysis == null || !investigation.id().equals(OpsRecords.text(analysis.body(), "investigation_id"))))
            throw new IllegalArgumentException("The latest analysis lineage is invalid.");
        StoredRecord proposal = analysis == null ? null : store.list("proposal", run).stream()
                .filter(p -> analysis.id().equals(OpsRecords.text(p.body(), "analysis_id")))
                .reduce((first, last) -> last).orElse(null);
        if (analysis != null && proposal == null) throw new IllegalArgumentException("The latest analysis has no proposal.");
        if (!survey && (proposal == null || !basis.id().equals(proposal.id())))
            throw new IllegalArgumentException("The proposal is no longer the latest analysis proposal.");
        if (!survey) {
            JsonNode recordedWind = analysis.body().path("wind");
            if (!recordedWind.path("from_deg").isNumber() || !recordedWind.path("speed_m_s").isNumber()
                    || directionBucket(recordedWind.path("from_deg").doubleValue()) != directionBucket(wind.fromDeg())
                    || speedBucket(recordedWind.path("speed_m_s").doubleValue()) != speedBucket(wind.speedMS()))
                throw new IllegalArgumentException("The planner wind basis is stale or unknown; run analysis again.");
        }
        point(mission.get("target"));
        JsonNode route = mission.path("route");
        if (!route.isArray() || route.isEmpty()) throw new IllegalArgumentException("The full declared route is required.");
        route.forEach(MissionDependencies::point);
        for (String key : Set.of("action_id", "vehicle_id", "method_id"))
            if (OpsRecords.text(mission, key) == null || mission.path(key).asText().isBlank())
                throw new IllegalArgumentException("The mission has an unknown " + key + ".");
        if (!mission.path("channel_ids").isArray() || mission.path("channel_ids").isEmpty()
                || OpsRecords.strings(mission.path("channel_ids")).size() != mission.path("channel_ids").size())
            throw new IllegalArgumentException("The mission channels are unknown.");
        ObjectNode result = OpsRecords.object().put("version", "FP-MISSION-DEPS-1").put("run_id", run)
                .put("investigation_id", investigation.id());
        result.set("basis_ref", OpsRecords.ref(basis));
        result.set("analysis_ref", analysis == null ? result.nullNode() : OpsRecords.ref(analysis));
        result.set("proposal_ref", proposal == null ? result.nullNode() : OpsRecords.ref(proposal));
        if (analysis != null) {
            result.set("input_window_refs", analysis.body().path("input_window_refs"));
            result.set("screening_record_refs", analysis.body().path("screening_record_refs"));
        }
        for (String key : List.of("action_id", "vehicle_id", "target", "route", "method_id", "channel_ids")) result.set(key, mission.get(key));
        return result.put("wind_direction_bucket", directionBucket(wind.fromDeg())).put("wind_speed_bucket", speedBucket(wind.speedMS()));
    }
}
