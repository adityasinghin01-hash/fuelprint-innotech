package fuelprint.field.pilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.core.Ids;
import fuelprint.field.geo.Vec3;
import fuelprint.field.world.FieldWorld;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Reads one pilot input message of AGREEMENT section 7 and hands it to the world:
 * {@code {"lease_id": "uuid", "sequence": 1532, "neutral": false, "velocity_enu": {"east": 1.5, "north": -0.4,
 * "up": 0.0}, "sent_utc": "..."}}. The sender is the socket's session, never a field in the message.
 */
@Component
@Profile("!stub")
public class PilotInputService {

    static final int MAX_MESSAGE_CHARS = 1024;

    private final FieldWorld world;
    private final ObjectMapper mapper = new ObjectMapper();

    public PilotInputService(FieldWorld world) {
        this.world = world;
    }

    /** Handles one text message from {@code actor}'s socket. */
    public FieldWorld.InputResult accept(String actor, String text) {
        if (text == null || text.length() > MAX_MESSAGE_CHARS) {
            return FieldWorld.InputResult.INVALID;
        }
        JsonNode message;
        try {
            message = mapper.readTree(text);
        } catch (Exception e) {
            return FieldWorld.InputResult.INVALID;
        }
        if (message == null || !message.isObject()) {
            return FieldWorld.InputResult.INVALID;
        }
        String leaseId = message.path("lease_id").asText(null);
        JsonNode sequence = message.get("sequence");
        if (!Ids.isUuid(leaseId) || sequence == null || !sequence.canConvertToLong() || !sequence.isIntegralNumber()) {
            return FieldWorld.InputResult.INVALID;
        }
        boolean neutral = message.path("neutral").asBoolean(false);
        Vec3 velocity = null;
        if (!neutral) {
            JsonNode v = message.path("velocity_enu");
            if (!v.path("east").isNumber() || !v.path("north").isNumber() || !v.path("up").isNumber()) {
                return FieldWorld.InputResult.INVALID;
            }
            velocity = new Vec3(v.get("east").asDouble(), v.get("north").asDouble(), v.get("up").asDouble());
        }
        return world.acceptInput(actor, leaseId, sequence.asLong(), neutral, velocity);
    }

    public void connected(String actor) {
        world.pilotConnected(actor);
    }

    public void disconnected(String actor) {
        world.pilotDisconnected(actor);
    }
}
