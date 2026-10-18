package fuelprint.science.sensor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.SplittableRandom;

/** Shared gas branch settings for the world and the inverse model. Flows come from the online method. */
public enum GasSettings {
    SO2(60.0), CO2(30.0);

    private final double sensorT90S;

    GasSettings(double sensorT90S) {
        this.sensorT90S = sensorT90S;
    }

    public double flowLMin() {
        return Method.FLOWS.get(name() + "_flow_L_min").asDouble();
    }

    public double sensorT90S() { return sensorT90S; }
    public double chamberT90S() { return GasChannel.CHAMBER_T90_S; }
    public double lineDelayS() { return 60 * InletLine.VOLUME_L / flowLMin(); }

    public GasChannel channel(double noiseSd, double rho, double initial, SplittableRandom rng) {
        return new GasChannel(flowLMin(), sensorT90S(), chamberT90S(), noiseSd, rho, initial, rng);
    }

    private static final class Method {
        static final JsonNode FLOWS = load();

        private static JsonNode load() {
            try (InputStream in = GasSettings.class.getResourceAsStream("/reference/method-profiles.json")) {
                if (in == null) throw new IllegalStateException("Missing method-profiles.json");
                for (JsonNode method : new ObjectMapper().readTree(in)) {
                    if ("TEACH-M1-ONLINE-1".equals(method.path("id").asText())) {
                        for (String gas : new String[]{"SO2", "CO2"}) {
                            JsonNode flow = method.path(gas + "_flow_L_min");
                            if (!flow.isNumber() || !(flow.asDouble() > 0))
                                throw new IllegalStateException("Missing positive flow for " + gas);
                        }
                        return method;
                    }
                }
                throw new IllegalStateException("Missing online gas method");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
