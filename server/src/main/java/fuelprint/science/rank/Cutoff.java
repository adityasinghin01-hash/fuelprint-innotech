package fuelprint.science.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The FP-COMPAT-3 survival cutoff on normalised SSE, computed by {@link CalibrationRunner} from separate
 * calibration episodes and stored with how it was made in {@code science/rank/cutoff.json}.
 */
public record Cutoff(double value, int episodes, int rank, String method, String seeds, String sha256OfStatistics) {

    public static final String RESOURCE = "science/rank/cutoff.json";
    /** The historical value; never allowed as a new cutoff (R3 doc 03). */
    public static final double FORBIDDEN_HISTORICAL = 132.253985;

    public Cutoff {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Cutoff must be a positive finite number");
        }
        if (Math.abs(value - FORBIDDEN_HISTORICAL) < 1e-6) {
            throw new IllegalArgumentException("The historical cutoff 132.253985 is forbidden");
        }
    }

    /** Rank of the cutoff among m sorted statistics: min(m, ceil(0.95 (m + 1))). */
    public static int rankFor(int m) {
        return Math.min(m, (int) Math.ceil(0.95 * (m + 1)));
    }

    public String label() {
        return "cutoff=" + value + " (m=" + episodes + ", rank=" + rank + ")";
    }

    public static Cutoff load() {
        try (InputStream in = Cutoff.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE + "; run CalibrationRunner");
            }
            JsonNode n = new ObjectMapper().readTree(in);
            return fromJson(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Validate the offline artifact before its statistic can govern runtime decisions. */
    static Cutoff fromJson(JsonNode n) {
        if (!CompatRanker.ALGORITHM.equals(n.path("algorithm").asText())
                || !WorldProfile.load().version().equals(n.path("world").asText())
                || !ResponseModel.TEACHING.label().equals(n.path("response").asText()))
            throw new IllegalStateException("Cutoff provenance mismatch: recalibrate " + CompatRanker.ALGORITHM);
        int episodes = n.path("episodes").asInt(), rank = n.path("rank").asInt();
        JsonNode manifest = n.path("episode_statistics");
        if (episodes < 40 || rank != rankFor(episodes) || !manifest.isArray() || manifest.size() != episodes)
            throw new IllegalStateException("Invalid cutoff episode count or order-statistic rank");
        double[] statistics = new double[episodes];
        for (int i = 0; i < episodes; i++) {
            JsonNode value = manifest.get(i).path("statistic");
            statistics[i] = value.asDouble(Double.NaN);
            if (Double.isNaN(statistics[i]) || statistics[i] < 0)
                throw new IllegalStateException("Invalid cutoff statistic manifest");
        }
        if (!CalibrationRunner.statisticsHash(statistics).equals(n.path("statistics_sha256").asText()))
            throw new IllegalStateException("Cutoff statistic hash mismatch");
        java.util.Arrays.sort(statistics);
        if (Double.compare(statistics[rank - 1], n.path("cutoff").asDouble(Double.NaN)) != 0)
            throw new IllegalStateException("Cutoff is not its declared order statistic");
        return new Cutoff(n.path("cutoff").asDouble(), episodes, rank,
                n.path("method").asText(), n.path("seeds").asText(), n.path("statistics_sha256").asText());
    }
}
