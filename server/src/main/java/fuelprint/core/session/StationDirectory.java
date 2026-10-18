package fuelprint.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.core.command.Role;
import fuelprint.core.db.DataDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The laptop stations and their PINs from {@code server/config/stations.json} (never committed). If that file is
 * missing and {@code fp.stations.allow-example} is true (the default, for teammates' laptops), the committed
 * {@code server/config/stations.example.json} is used and a warning is logged. {@code tools/stall.sh} sets it false.
 */
@Component
public class StationDirectory {

    /** One station: id like {@code laptop-1}, a display name, its PIN and its roles. */
    public record Station(String stationId, String name, String pin, Set<Role> roles) {
        @Override
        public String toString() {
            return "Station[" + stationId + " " + roles + "]"; // never the PIN
        }
    }

    private static final Logger log = LoggerFactory.getLogger(StationDirectory.class);

    private final List<Station> stations;
    private final boolean example;

    public StationDirectory(@Value("${fp.stations-file:server/config/stations.json}") String file,
                            @Value("${fp.stations.allow-example:true}") boolean allowExample) {
        Path root = DataDirectory.findRepoRoot(Path.of("").toAbsolutePath());
        Path configured = Path.of(file).isAbsolute() ? Path.of(file) : root.resolve(file);
        Path exampleFile = configured.resolveSibling("stations.example.json");
        if (Files.isRegularFile(configured)) {
            stations = read(configured);
            example = false;
        } else if (allowExample && Files.isRegularFile(exampleFile)) {
            log.warn("No {}; using the EXAMPLE stations and PINs from {}. Never do this at the stall.",
                    configured, exampleFile);
            stations = read(exampleFile);
            example = true;
        } else {
            log.warn("No station file at {}: no laptop can log in", configured);
            stations = List.of();
            example = false;
        }
    }

    static List<Station> read(Path file) {
        try {
            JsonNode root = new ObjectMapper().readTree(file.toFile());
            List<Station> list = new ArrayList<>();
            for (JsonNode node : root.path("stations")) {
                Set<Role> roles = new LinkedHashSet<>();
                for (JsonNode role : node.path("roles")) {
                    Role parsed = Role.fromWire(role.asText())
                            .orElseThrow(() -> new IllegalStateException("Unknown role " + role.asText() + " in " + file));
                    if (parsed == Role.AUTOMATION) {
                        throw new IllegalStateException("A station can never hold the automation role (" + file + ")");
                    }
                    roles.add(parsed);
                }
                String pin = node.path("pin").asText("");
                if (pin.length() < 4) {
                    throw new IllegalStateException("Station " + node.path("station_id").asText() + " needs a PIN of 4+ characters");
                }
                list.add(new Station(node.path("station_id").asText(), node.path("name").asText(), pin, Set.copyOf(roles)));
            }
            return List.copyOf(list);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read station file " + file, e);
        }
    }

    public List<Station> stations() {
        return stations;
    }

    /** True if the example PINs are in use. */
    public boolean usingExample() {
        return example;
    }

    /** The station if {@code pin} is its PIN (constant-time compare). */
    public Optional<Station> check(String stationId, String pin) {
        if (stationId == null || pin == null) {
            return Optional.empty();
        }
        for (Station station : stations) {
            if (station.stationId().equals(stationId)
                    && MessageDigest.isEqual(station.pin().getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8))) {
                return Optional.of(station);
            }
        }
        return Optional.empty();
    }
}
