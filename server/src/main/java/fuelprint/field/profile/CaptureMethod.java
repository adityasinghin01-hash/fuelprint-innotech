package fuelprint.field.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The online gas capture method {@value #ID} from {@code contracts/reference/method-profiles.json}.
 * Its branch flows (SO2 0.5 L/min, CO2 0.3 L/min) are only written in {@code TEACH-M1-ONLINE-1} of that
 * file, so they are read from there (the field plan gives the same numbers for TEACH-NODE-ONLINE-2).
 *
 * @param purgeS              purge stage
 * @param minimumSettleS      settle stage, at least
 * @param collectionValidS    consecutive valid seconds needed
 * @param flowTolerancePercent allowed flow error
 * @param flowLMinByGas       nominal branch flow by gas prefix ({@code SO2}, {@code CO2})
 */
public record CaptureMethod(double purgeS, double minimumSettleS, double collectionValidS,
                            double flowTolerancePercent, Map<String, Double> flowLMinByGas) {

    public static final String ID = "TEACH-NODE-ONLINE-2";
    public static final String METHOD_FILE = "contracts/reference/method-profiles.json";
    static final String FLOW_SOURCE_ID = "TEACH-M1-ONLINE-1";

    public CaptureMethod {
        flowLMinByGas = Map.copyOf(flowLMinByGas);
    }

    /** The gas part of a channel name: {@code SO2} for {@code SO2_ppb}. */
    public static String gasOf(String channel) {
        int cut = channel.indexOf('_');
        return cut < 0 ? channel : channel.substring(0, cut);
    }

    /** The nominal branch flow for {@code channel} in L/min, or empty for a channel with no known branch. */
    public Optional<Double> flowFor(String channel) {
        return Optional.ofNullable(flowLMinByGas.get(gasOf(channel)));
    }

    public static CaptureMethod load(Path repoRoot) {
        JsonNode all;
        try {
            all = new ObjectMapper().readTree(repoRoot.resolve(METHOD_FILE).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + METHOD_FILE, e);
        }
        JsonNode method = find(all, ID);
        JsonNode flows = find(all, FLOW_SOURCE_ID);
        return new CaptureMethod(method.path("purge_s").asDouble(), method.path("minimum_settle_s").asDouble(),
                method.path("collection_valid_s").asDouble(), method.path("flow_tolerance_percent").asDouble(),
                Map.of("SO2", flows.path("SO2_flow_L_min").asDouble(), "CO2", flows.path("CO2_flow_L_min").asDouble()));
    }

    private static JsonNode find(JsonNode all, String id) {
        for (JsonNode method : all) {
            if (id.equals(method.path("id").asText())) {
                return method;
            }
        }
        throw new IllegalStateException("Method " + id + " is missing from " + METHOD_FILE);
    }
}
