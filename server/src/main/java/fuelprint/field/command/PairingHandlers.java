package fuelprint.field.command;

import static fuelprint.field.command.FieldRecords.PAIRING;
import static fuelprint.field.command.FieldRecords.find;
import static fuelprint.field.command.FieldRecords.missing;
import static fuelprint.field.command.FieldRecords.reject;
import static fuelprint.field.command.FieldRecords.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.Ids;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.FieldTime;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Pairing a visitor phone (AGREEMENT section 5 {@code create_pairing}, {@code confirm_pairing}; R3 doc 03
 * "Phones"): the field operator makes a 6-character code and a two-word phrase that live 120 s; the phone
 * redeems the code ({@code POST /api/session/join}, command {@code redeem_pairing}) and shows the phrase;
 * the operator confirms the phrase matches and the visitor joins the queue.
 * Record {@code pairing}: CREATED, then REDEEMED, then CONFIRMED; or EXPIRED.
 */
public final class PairingHandlers {

    public static final long PAIRING_S = 120;
    /** No 0/O, 1/I/L: easy to read aloud and type. */
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;
    static final List<String> FIRST = List.of("green", "blue", "quiet", "bright", "silver", "golden", "calm",
            "swift", "gentle", "sunny", "amber", "misty", "brave", "happy", "little", "tall");
    static final List<String> SECOND = List.of("river", "mango", "kite", "lotus", "tiger", "peacock", "garden",
            "lantern", "comet", "harbour", "meadow", "pebble", "banyan", "sparrow", "valley", "rocket");

    private static final SecureRandom RANDOM = new SecureRandom();

    private PairingHandlers() {
    }

    /** The code with spaces and dashes removed, upper case. */
    public static String normaliseCode(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    static boolean expired(StoredRecord pairing, Instant now) {
        try {
            return !now.isBefore(Instant.parse(pairing.body().path("expires_utc").asText()));
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    static RecordChange next(StoredRecord pairing, String state, String byField, String by) {
        ObjectNode fields = FieldRecords.own(pairing);
        fields.put("state", state);
        if (byField != null) {
            fields.put(byField, by);
        }
        return RecordChange.next(pairing, fields);
    }

    /** {@code create_pairing} (field_operator): mission_id, vehicle_id. Subject: the mission. */
    @Component
    @Profile("!stub")
    public static class CreatePairing implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;

        public CreatePairing(FieldWorld world, FieldTime time) {
            this.world = world;
            this.time = time;
        }

        @Override
        public String commandType() {
            return "create_pairing";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.FIELD_OPERATOR);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "mission_id");
            return id == null ? null : new RecordKey(FieldRecords.MISSION, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String missionId = FieldRecords.id(command, "mission_id");
            String vehicleId = FieldRecords.id(command, "vehicle_id");
            if (missionId == null || vehicleId == null) {
                return missing(missionId == null ? "mission_id" : "vehicle_id");
            }
            StoredRecord mission = find(store, FieldRecords.MISSION, missionId);
            if (mission == null) {
                return reject("NOT_FOUND", "There is no mission " + missionId + ".");
            }
            if (!vehicleId.equals(world.droneId()) || !vehicleId.equals(mission.body().path("vehicle_id").asText())) {
                return reject("WRONG_VEHICLE", "That vehicle does not fly this mission.");
            }
            if (!MissionView.ACTIVE.contains(state(mission))) {
                return reject("WRONG_STATE", "A visitor can pair only for an approved mission; this one is "
                        + state(mission) + ".");
            }
            List<String> live = store.list(PAIRING, mission.runId()).stream()
                    .filter(p -> "CREATED".equals(state(p)) || "REDEEMED".equals(state(p)))
                    .map(p -> p.body().path("code").asText()).toList();
            String code;
            do {
                code = randomCode();
            } while (live.contains(code));
            Instant now = time.now();
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("mission_id", missionId);
            fields.put("vehicle_id", vehicleId);
            fields.put("code", code);
            fields.put("phrase", FIRST.get(RANDOM.nextInt(FIRST.size())) + " " + SECOND.get(RANDOM.nextInt(SECOND.size())));
            fields.put("expires_utc", FieldTime.format(now.plusSeconds(PAIRING_S)));
            fields.put("state", "CREATED");
            fields.putNull("redeemed_by");
            fields.putNull("confirmed_by");
            return HandlerResult.builder()
                    .save(RecordChange.create(PAIRING, Ids.newId(), mission.runId(), fields), "PAIRING_CREATED",
                            world.simTimeS())
                    .build();
        }

        static String randomCode() {
            StringBuilder out = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                out.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            return out.toString();
        }
    }

    /**
     * {@code redeem_pairing} (visitor; not in AGREEMENT section 5, sent by {@code POST /api/session/join} with the
     * new visitor session): pairing_id, code. Subject: the pairing. The code is kept out of the commands table.
     */
    @Component
    @Profile("!stub")
    public static class RedeemPairing implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;

        public RedeemPairing(FieldWorld world, FieldTime time) {
            this.world = world;
            this.time = time;
        }

        @Override
        public String commandType() {
            return "redeem_pairing";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.VISITOR);
        }

        @Override
        public Set<String> privatePayloadFields() {
            return Set.of("code");
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "pairing_id");
            return id == null ? null : new RecordKey(PAIRING, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            StoredRecord pairing = find(store, PAIRING, FieldRecords.id(command, "pairing_id"));
            String code = normaliseCode(FieldRecords.text(command, "code"));
            if (pairing == null || !sameCode(pairing.body().path("code").asText(), code)) {
                return reject("CODE_NOT_VALID", "That code is not valid. Check it on the field screen.");
            }
            if (!"CREATED".equals(state(pairing))) {
                return reject("ALREADY_USED", "That code was already used.");
            }
            if (expired(pairing, time.now())) {
                return reject("EXPIRED", "That code has expired. Ask for a new one.");
            }
            return HandlerResult.builder()
                    .save(next(pairing, "REDEEMED", "redeemed_by", session.actorLabel()), "PAIRING_REDEEMED",
                            world.simTimeS())
                    .build();
        }

        private static boolean sameCode(String stored, String given) {
            return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** {@code confirm_pairing} (field_operator): pairing_id. The phone has redeemed the code; the visitor joins the queue. */
    @Component
    @Profile("!stub")
    public static class ConfirmPairing implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;

        public ConfirmPairing(FieldWorld world, FieldTime time) {
            this.world = world;
            this.time = time;
        }

        @Override
        public String commandType() {
            return "confirm_pairing";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.FIELD_OPERATOR);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "pairing_id");
            return id == null ? null : new RecordKey(PAIRING, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String id = FieldRecords.id(command, "pairing_id");
            if (id == null) {
                return missing("pairing_id");
            }
            StoredRecord pairing = find(store, PAIRING, id);
            if (pairing == null) {
                return reject("NOT_FOUND", "There is no pairing " + id + ".");
            }
            if (!"REDEEMED".equals(state(pairing))) {
                return reject("NOT_REDEEMED", "The phone has not entered this code yet (pairing is "
                        + state(pairing) + ").");
            }
            if (expired(pairing, time.now())) {
                return reject("EXPIRED", "This pairing code has expired. Make a new one.");
            }
            return HandlerResult.builder()
                    .save(next(pairing, "CONFIRMED", "confirmed_by", session.actorLabel()), "PAIRING_CONFIRMED",
                            world.simTimeS())
                    .build();
        }
    }

    /** {@code expire_pairing} (automation only; internal): pairing_id. A code not used within 120 s expires. */
    @Component
    @Profile("!stub")
    public static class ExpirePairing implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;

        public ExpirePairing(FieldWorld world, FieldTime time) {
            this.world = world;
            this.time = time;
        }

        @Override
        public String commandType() {
            return "expire_pairing";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.AUTOMATION);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            StoredRecord pairing = find(store, PAIRING, FieldRecords.id(command, "pairing_id"));
            if (pairing == null) {
                return missing("pairing_id");
            }
            String state = state(pairing);
            if (!"CREATED".equals(state) && !"REDEEMED".equals(state)) {
                return reject("WRONG_STATE", "Only an unconfirmed pairing can expire; this one is " + state + ".");
            }
            if (!expired(pairing, time.now())) {
                return reject("NOT_EXPIRED", "This pairing code is still valid.");
            }
            return HandlerResult.builder()
                    .save(next(pairing, "EXPIRED", null, null), "PAIRING_EXPIRED", world.simTimeS())
                    .build();
        }
    }
}
