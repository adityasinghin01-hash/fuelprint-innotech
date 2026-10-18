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
            return new Cutoff(n.path("cutoff").asDouble(), n.path("episodes").asInt(), n.path("rank").asInt(),
                    n.path("method").asText(), n.path("seeds").asText(), n.path("statistics_sha256").asText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
