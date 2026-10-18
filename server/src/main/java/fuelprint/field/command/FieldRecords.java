package fuelprint.field.command;

import java.time.Instant;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.Pilot;
import fuelprint.core.Ids;
import fuelprint.core.command.Command;
import fuelprint.core.command.Rejection;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.FieldTime;
import fuelprint.field.world.FieldWorld;

/** Record shapes and small helpers shared by the field command handlers. */
public final class FieldRecords {

    public static final String MISSION = "mission";
    public static final String LEASE = "lease";
    public static final String PAIRING = "pairing";
    public static final String ATTEMPT = "attempt";
    public static final String SCREENING = "screening_record";

    public static final String LEASE_ACTIVE = "ACTIVE";
    public static final String LEASE_RELEASED = "RELEASED";
    public static final String LEASE_REVOKED = "REVOKED";
    public static final String LEASE_EXPIRED = "EXPIRED";

    public static final Set<String> RETURN_REASONS = Set.of("BATTERY_RESERVE", "MISSION_DONE", "OPERATOR");

    private FieldRecords() {
    }

    /** The record's own fields (the common fields removed), ready for {@link RecordChange#next}. */
    public static ObjectNode own(StoredRecord record) {
        ObjectNode body = record.body();
        RecordChange.COMMON_FIELDS.forEach(body::remove);
        return body;
    }

    /** The next mission revision with {@code state} and {@code pilot}. */
    public static RecordChange mission(StoredRecord current, String state, Pilot pilot) {
        ObjectNode fields = own(current);
        fields.put("state", state);
        fields.put("pilot", pilot.name());
        return RecordChange.next(current, fields);
    }

    /** True if the mission record would change with this state and pilot. */
    public static boolean missionChanges(StoredRecord current, String state, Pilot pilot) {
        ObjectNode body = current.body();
        return !state.equals(body.path("state").asText()) || !pilot.name().equals(body.path("pilot").asText());
    }

    /** A brand-new ACTIVE lease. Person leases expire after 2 s without fresh input; the autopilot's has no expiry (null). */
    public static RecordChange newLease(String runId, String missionId, String vehicleId, String holder, Pilot kind,
                                        String purpose, Instant now) {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("mission_id", missionId);
        fields.put("vehicle_id", vehicleId);
        fields.put("holder_session", holder);
        fields.put("holder_kind", kind.name());
        fields.put("purpose", purpose);
        fields.put("state", LEASE_ACTIVE);
        if (kind == Pilot.AUTOPILOT) {
            fields.putNull("expires_utc");
        } else {
            fields.put("expires_utc", FieldTime.format(now.plusMillis(FieldWorld.LEASE_MS)));
        }
        fields.putNull("last_sequence");
        fields.putNull("ended_reason");
        return RecordChange.create(LEASE, Ids.newId(), runId, fields);
    }

    /** The final revision of a lease: RELEASED, REVOKED or EXPIRED, with why and the last input sequence. */
    public static RecordChange endLease(StoredRecord lease, String state, String reason, Long lastSequence) {
        ObjectNode fields = own(lease);
        fields.put("state", state);
        fields.put("ended_reason", reason);
        if (lastSequence == null) {
            fields.putNull("last_sequence");
        } else {
            fields.put("last_sequence", lastSequence);
        }
        return RecordChange.next(lease, fields);
    }

    /** A text field of the payload, or null if missing or not text. */
    public static String text(Command command, String field) {
        JsonNode value = command.payload().get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** A UUID field of the payload, or null if missing or not a UUID. */
    public static String id(Command command, String field) {
        String value = text(command, field);
        return Ids.isUuid(value) ? value : null;
    }

    public static Rejection reject(String code, String text) {
        return new Rejection(code, text);
    }

    public static Rejection missing(String field) {
        return Rejection.invalid(field + " is required and must be a UUID.");
    }

    /** The latest revision of {@code type}/{@code id}, if any. */
    public static StoredRecord find(StoreView store, String type, String id) {
        return id == null ? null : store.get(type, id).orElse(null);
    }

    public static String state(StoredRecord record) {
        return record.body().path("state").asText("");
    }
}
