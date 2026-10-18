package fuelprint.science.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The numbers of {@code world-profile.json} (FP-WORLD-3) that the science engine uses. */
public final class WorldProfile {

    /** A fictional factory (S01 to S06). */
    public record Source(String id, double east, double north, double effectiveHeight) {
    }

    /** A fixed sensor box (N1 to N4). */
    public record Node(String id, double east, double north, double inletHeight) {
    }

    public static final String RESOURCE = "/reference/world-profile.json";

    private final List<Source> sources;
    private final List<Node> nodes;
    private final double sigma0Sq;
    private final double kx;
    private final double ky;
    private final double kz;
    private final double windFromDeg;
    private final double windSpeed;
    private final double so2GramsPerSecond;
    private final double co2GramsPerSecond;
    private final double liveSeconds;
    private final int livePuffCap;

    private WorldProfile(JsonNode root) {
        List<Source> s = new ArrayList<>();
        for (JsonNode n : root.get("sources")) {
            s.add(new Source(n.get("id").asText(), n.get("east_m").asDouble(), n.get("north_m").asDouble(),
                    n.get("effective_height_m").asDouble()));
        }
        List<Node> nd = new ArrayList<>();
        for (JsonNode n : root.get("nodes")) {
            nd.add(new Node(n.get("id").asText(), n.get("east_m").asDouble(), n.get("north_m").asDouble(),
                    n.get("inlet_m").asDouble()));
        }
        this.sources = List.copyOf(s);
        this.nodes = List.copyOf(nd);
        JsonNode nom = root.get("nominal");
        this.sigma0Sq = nom.get("sigma0_sq_m2").asDouble();
        this.kx = nom.get("Kx_m2_s").asDouble();
        this.ky = nom.get("Ky_m2_s").asDouble();
        this.kz = nom.get("Kz_m2_s").asDouble();
        this.windFromDeg = nom.get("wind_from_deg").asDouble();
        this.windSpeed = nom.get("wind_m_s").asDouble();
        this.so2GramsPerSecond = nom.get("SO2_g_s").asDouble();
        this.co2GramsPerSecond = nom.get("CO2_g_s").asDouble();
        this.liveSeconds = root.get("clock").get("live_s").asDouble();
        this.livePuffCap = root.get("clock").get("live_puff_cap").asInt();
    }

    /** Loads the shared classpath copy of the contract reference profile. */
    public static WorldProfile load() {
        try (InputStream in = WorldProfile.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return new WorldProfile(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Source> sources() {
        return sources;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public Optional<Source> source(String id) {
        return sources.stream().filter(x -> x.id().equals(id)).findFirst();
    }

    public Optional<Node> node(String id) {
        return nodes.stream().filter(x -> x.id().equals(id)).findFirst();
    }

    public double sigma0Sq() {
        return sigma0Sq;
    }

    public double kx() {
        return kx;
    }

    public double ky() {
        return ky;
    }

    public double kz() {
        return kz;
    }

    public double windFromDeg() {
        return windFromDeg;
    }

    public double windSpeed() {
        return windSpeed;
    }

    public double nominalSo2GramsPerSecond() {
        return so2GramsPerSecond;
    }

    public double nominalCo2GramsPerSecond() {
        return co2GramsPerSecond;
    }

    public double liveSeconds() {
        return liveSeconds;
    }

    public int livePuffCap() {
        return livePuffCap;
    }
}
