package fuelprint.core.stub;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;

/** Reads the example records bundled as {@code classpath:fixtures/<name>.json}. */
final class Fixtures {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Fixtures() {
    }

    static JsonNode read(String name) {
        try (InputStream in = new ClassPathResource("fixtures/" + name + ".json").getInputStream()) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing fixture " + name, e);
        }
    }

    /** The fixture without the common record fields, read as {@code type}. */
    static <T> T as(String name, Class<T> type) {
        ObjectNode node = (ObjectNode) read(name).deepCopy();
        for (String common : new String[] {"id", "run_id", "revision", "created_utc", "created_by"}) {
            node.remove(common);
        }
        try {
            return MAPPER.treeToValue(node, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Fixture " + name + " does not fit " + type.getSimpleName(), e);
        }
    }
}
