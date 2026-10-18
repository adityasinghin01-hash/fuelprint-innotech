package fuelprint.field.command;

import static fuelprint.field.command.FieldRecords.ATTEMPT;
import static fuelprint.field.command.FieldRecords.MISSION;
import static fuelprint.field.command.FieldRecords.SCREENING;
import static fuelprint.field.command.FieldRecords.find;
import static fuelprint.field.command.FieldRecords.missing;
import static fuelprint.field.command.FieldRecords.reject;
import static fuelprint.field.command.FieldRecords.state;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.CaptureInput;
import fuelprint.api.ChannelStatus;
import fuelprint.api.ChannelVerdict;
import fuelprint.api.Stage;
import fuelprint.core.Ids;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Rejection;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.FieldPorts;
import fuelprint.field.profile.CaptureMethod;
import fuelprint.field.world.AttemptSnapshot;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Capture attempts and their QC review (AGREEMENT sections 5, 6 and 8; field plan "The capture rules").
 * Record {@code attempt}: COLLECTING, then COMPLETE, FAILED or INTERRUPTED; a retry is a new attempt.
 * Record {@code screening_record}: one per channel after the human review.
 */
public final class CaptureHandlers {

    private static final Logger log = LoggerFactory.getLogger(CaptureHandlers.class);

    public static final String NAMED_USE = "SYNTHETIC_ONLINE_SCREENING";
    static final String PENDING = "PENDING";

    private CaptureHandlers() {
    }

    /** The attempt of {@code mission} still COLLECTING, or null. */
    static StoredRecord runningAttempt(StoreView store, StoredRecord mission) {
        return store.list(ATTEMPT, mission.runId()).stream()
                .filter(a -> mission.id().equals(a.body().path("mission_id").asText()))
                .filter(a -> "COLLECTING".equals(state(a)))
                .findFirst().orElse(null);
    }

    static ArrayNode stagesJson(List<Stage> stages) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (Stage stage : stages) {
            ObjectNode node = out.addObject();
            node.put("stage", stage.stage());
            node.put("start_s", stage.startS());
            if (stage.endS() == null) {
                node.putNull("end_s");
            } else {
                node.put("end_s", stage.endS());
            }
        }
        return out;
    }

    static ObjectNode channelEntry(String channel, Double validSeconds, Double volumeL, ChannelStatus status,
                                   List<String> reasons, Double meanEnhancement, String enhancementNote,
                                   String checkedBy) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("channel", channel);
        putNumber(node, "valid_seconds", validSeconds);
        putNumber(node, "volume_L", volumeL);
        if (status == null) {
            node.putNull("status");
        } else {
            node.put("status", status.name());
        }
        ArrayNode list = node.putArray("reasons");
        reasons.forEach(list::add);
        putNumber(node, "mean_enhancement", meanEnhancement);
        if (enhancementNote == null) {
            node.putNull("mean_enhancement_note");
        } else {
            node.put("mean_enhancement_note", enhancementNote);
        }
        if (checkedBy == null) {
            node.putNull("checked_by");
        } else {
            node.put("checked_by", checkedBy);
        }
        return node;
    }

    private static void putNumber(ObjectNode node, String field, Double value) {
        if (value == null || !Double.isFinite(value)) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /** The INTERRUPTED revision of a running attempt: open stage closed now, unjudged channels REJECTED. */
    static RecordChange interrupted(StoredRecord attempt, FieldWorld world, String reason) {
        ObjectNode fields = FieldRecords.own(attempt);
        Optional<AttemptSnapshot> snapshot = world.captureSnapshot(attempt.id());
        double now = world.simTimeS();
        ArrayNode stages = snapshot.map(s -> stagesJson(s.stages())).orElse((ArrayNode) fields.path("stages").deepCopy());
        for (JsonNode stage : stages) {
            if (stage.path("end_s").isNull()) {
                ((ObjectNode) stage).put("end_s", Math.max(now, stage.path("start_s").asDouble()));
            }
        }
        ArrayNode channels = JsonNodeFactory.instance.arrayNode();
        for (JsonNode channel : fields.path("channels")) {
            if (!channel.path("status").isNull()) {
                channels.add(channel);
                continue;
            }
            String name = channel.path("channel").asText();
            AttemptSnapshot.ChannelCount count = snapshot.map(s -> s.counts().get(name)).orElse(null);
            boolean counted = snapshot.map(AttemptSnapshot::collectEnded).orElse(false) && count != null;
            channels.add(channelEntry(name, counted ? (double) count.validSeconds() : null,
                    counted ? count.volumeL() : null, ChannelStatus.REJECTED,
                    List.of("ATTEMPT_INTERRUPTED", reason), null, "ATTEMPT_INTERRUPTED", "FIELD"));
        }
        fields.set("stages", stages);
        fields.set("channels", channels);
        fields.put("state", "INTERRUPTED");
        fields.putArray("reasons").add(reason);
        return RecordChange.next(attempt, fields);
    }

    /**
     * {@code start_capture} (field_operator, or automation if {@code auto_capture} is on): mission_id, method_id,
     * channel_ids. Subject: the mission. The mission is AT_TARGET (so its approval is current), the drone is in
     * position, the method is TEACH-NODE-ONLINE-2 and the channels are the mission's. Stages start at the next
     * whole simulated second.
     */
    @Component
    @Profile("!stub")
    public static class StartCapture implements CommandHandler {
        private final FieldWorld world;
        private final FieldPorts ports;

        public StartCapture(FieldWorld world, FieldPorts ports) {
            this.world = world;
            this.ports = ports;
        }

        @Override
        public String commandType() {
            return "start_capture";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.FIELD_OPERATOR, Role.AUTOMATION);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "mission_id");
            return id == null ? null : new RecordKey(MISSION, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            if (session.isAutomation() && !ports.isOn(AutomationSettings.AUTO_CAPTURE)) {
                return reject("AUTOMATION_OFF", "Automatic capture is switched off.");
            }
            String missionId = FieldRecords.id(command, "mission_id");
            if (missionId == null) {
                return missing("mission_id");
            }
            StoredRecord mission = find(store, MISSION, missionId);
            MissionView view = mission == null ? null : MissionView.of(mission).orElse(null);
            if (view == null) {
                return reject("NOT_FOUND", "There is no mission " + missionId + ".");
            }
            if (!MissionView.AT_TARGET.equals(view.state())) {
                return reject("NOT_AT_TARGET", "A capture starts only when the mission is AT_TARGET; it is "
                        + view.state() + ".");
            }
            if (!view.vehicleId().equals(world.droneId()) || !world.inPosition(view)) {
                return reject("NOT_IN_POSITION", "The drone is not within 5 m of the target and inside the height band.");
            }
            String methodId = FieldRecords.text(command, "method_id");
            if (!CaptureMethod.ID.equals(methodId) || !methodId.equals(view.methodId())) {
                return reject("METHOD_MISMATCH", "The method must be the mission's method " + CaptureMethod.ID + ".");
            }
            List<String> channels = new ArrayList<>();
            command.payload().path("channel_ids").forEach(c -> channels.add(c.asText()));
            if (channels.isEmpty() || new HashSet<>(channels).size() != channels.size()
                    || !view.channelIds().containsAll(channels)) {
                return Rejection.invalid("channel_ids must be one or more of the mission's channels, each once.");
            }
            if (world.captureRunning() || runningAttempt(store, mission) != null) {
                return reject("CAPTURE_RUNNING", "A capture is already running.");
            }
            double start = Math.ceil(world.simTimeS() - 1e-6);
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("mission_id", missionId);
            fields.put("method_id", methodId);
            fields.set("stages", stagesJson(List.of(new Stage("SETUP", start, null))));
            ArrayNode list = fields.putArray("channels");
            for (String channel : channels) {
                list.add(channelEntry(channel, null, null, null, List.of(PENDING), null, PENDING, null));
            }
            fields.put("state", "COLLECTING");
            fields.putArray("reasons");
            return new LeaseMoves(world, store, mission)
                    .also(RecordChange.create(ATTEMPT, Ids.newId(), mission.runId(), fields), "ATTEMPT_STARTED")
                    .missionState(MissionView.COLLECTING)
                    .build();
        }
    }

    /**
     * {@code update_attempt} (automation only; internal): attempt_id. Saves the stages so far; when COLLECT
     * has ended it runs the automatic pre-check of every channel with {@code CaptureRules} on the readings at the
     * drone's inlet; when REVIEW has ended the attempt is COMPLETE (a channel is usable) or FAILED (none is).
     * Without capture rules a channel is REJECTED with CAPTURE_RULES_UNAVAILABLE and the field's own count.
     */
    @Component
    @Profile("!stub")
    public static class UpdateAttempt implements CommandHandler {
        private final FieldWorld world;
        private final FieldPorts ports;

        public UpdateAttempt(FieldWorld world, FieldPorts ports) {
            this.world = world;
            this.ports = ports;
        }

        @Override
        public String commandType() {
            return "update_attempt";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.AUTOMATION);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "attempt_id");
            return id == null ? null : new RecordKey(ATTEMPT, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            StoredRecord attempt = find(store, ATTEMPT, FieldRecords.id(command, "attempt_id"));
            if (attempt == null) {
                return missing("attempt_id");
            }
            if (!"COLLECTING".equals(state(attempt))) {
                return reject("ALREADY_FINAL", "This attempt already ended (" + state(attempt) + ").");
            }
            Optional<AttemptSnapshot> found = world.captureSnapshot(attempt.id());
            if (found.isEmpty()) {
                return reject("NO_CAPTURE", "The capture of this attempt is not running.");
            }
            AttemptSnapshot snapshot = found.get();
            ObjectNode fields = FieldRecords.own(attempt);
            ArrayNode channels = (ArrayNode) fields.path("channels");
            boolean pending = false;
            for (JsonNode channel : channels) {
                pending |= channel.path("status").isNull();
            }
            if (snapshot.collectEnded() && pending) {
                channels = judge(snapshot);
            }
            String state = "COLLECTING";
            if (snapshot.done()) {
                boolean usable = false;
                for (JsonNode channel : channels) {
                    String status = channel.path("status").asText();
                    usable |= "VALID".equals(status) || "LIMITED".equals(status);
                }
                state = usable ? "COMPLETE" : "FAILED";
            }
            ObjectNode next = fields.deepCopy();
            next.set("stages", stagesJson(snapshot.stages()));
            next.set("channels", channels);
            next.put("state", state);
            if (next.equals(fields)) {
                return HandlerResult.nothing();
            }
            String event = "COLLECTING".equals(state) ? "ATTEMPT_UPDATED" : "ATTEMPT_" + state;
            return HandlerResult.builder().save(RecordChange.next(attempt, next), event, world.simTimeS()).build();
        }

        private ArrayNode judge(AttemptSnapshot snapshot) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (String channel : snapshot.channels()) {
                AttemptSnapshot.ChannelCount count = snapshot.counts().get(channel);
                CaptureInput input = new CaptureInput(snapshot.attemptId(), snapshot.methodId(), channel,
                        snapshot.stages(), snapshot.readings().getOrDefault(channel, List.of()));
                ChannelVerdict verdict = null;
                String problem = null;
                try {
                    verdict = ports.judge(input).orElse(null);
                    if (verdict == null) {
                        problem = "CAPTURE_RULES_UNAVAILABLE";
                    }
                } catch (RuntimeException e) {
                    log.warn("CaptureRules failed for {} of attempt {}", channel, snapshot.attemptId(), e);
                    problem = "CAPTURE_RULES_FAILED";
                }
                if (verdict != null) {
                    ChannelStatus status = verdict.status() == null ? ChannelStatus.REJECTED : verdict.status();
                    List<String> reasons = new ArrayList<>(verdict.reasons());
                    if (verdict.status() == null) {
                        reasons.add("STATUS_MISSING");
                    }
                    out.add(channelEntry(channel, verdict.validSeconds(), verdict.volumeL(), status, reasons,
                            count.meanEnhancement(), count.enhancementNote(), "CAPTURE_RULES"));
                } else {
                    List<String> reasons = new ArrayList<>();
                    reasons.add(problem);
                    reasons.addAll(count.reasons());
                    out.add(channelEntry(channel, (double) count.validSeconds(), count.volumeL(),
                            ChannelStatus.REJECTED, reasons, count.meanEnhancement(), count.enhancementNote(), "FIELD"));
                }
            }
            return out;
        }
    }

    /**
     * {@code review_capture} (qc_reviewer only; human only, the automation actor is refused by the core):
     * attempt_id, channel_decisions[] of {channel, decision VALID|LIMITED|REJECTED, reasons[]}. Subject: the
     * attempt. Every channel gets exactly one decision; a decision can never be better than the automatic
     * pre-check (a channel that failed a rule cannot be passed). Saves one {@code screening_record} per channel.
     */
    @Component
    @Profile("!stub")
    public static class ReviewCapture implements CommandHandler {
        static final List<String> ORDER = List.of("VALID", "LIMITED", "REJECTED");

        private final FieldWorld world;

        public ReviewCapture(FieldWorld world) {
            this.world = world;
        }

        @Override
        public String commandType() {
            return "review_capture";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.QC_REVIEWER);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "attempt_id");
            return id == null ? null : new RecordKey(ATTEMPT, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String attemptId = FieldRecords.id(command, "attempt_id");
            if (attemptId == null) {
                return missing("attempt_id");
            }
            StoredRecord attempt = find(store, ATTEMPT, attemptId);
            if (attempt == null) {
                return reject("NOT_FOUND", "There is no attempt " + attemptId + ".");
            }
            if ("COLLECTING".equals(state(attempt))) {
                return reject("NOT_READY", "The capture is still running; review it when it has ended.");
            }
            boolean reviewed = store.list(SCREENING, attempt.runId()).stream()
                    .anyMatch(s -> attemptId.equals(s.body().path("attempt_id").asText()));
            if (reviewed) {
                return reject("ALREADY_REVIEWED", "This attempt was already reviewed.");
            }
            Map<String, JsonNode> automatic = new LinkedHashMap<>();
            attempt.body().path("channels").forEach(c -> automatic.put(c.path("channel").asText(), c));
            JsonNode decisions = command.payload().path("channel_decisions");
            if (!decisions.isArray()) {
                return Rejection.invalid("channel_decisions must be a list.");
            }
            Map<String, JsonNode> byChannel = new LinkedHashMap<>();
            for (JsonNode decision : decisions) {
                String channel = decision.path("channel").asText(null);
                if (channel == null || !automatic.containsKey(channel) || byChannel.put(channel, decision) != null) {
                    return Rejection.invalid("Each channel of the attempt needs exactly one decision; '" + channel
                            + "' is unknown or repeated.");
                }
                if (!ORDER.contains(decision.path("decision").asText())) {
                    return Rejection.invalid("decision must be VALID, LIMITED or REJECTED.");
                }
            }
            if (!byChannel.keySet().equals(automatic.keySet())) {
                return Rejection.invalid("Every channel needs a decision: " + automatic.keySet() + ".");
            }
            HandlerResult.Builder result = HandlerResult.builder();
            for (Map.Entry<String, JsonNode> entry : byChannel.entrySet()) {
                String channel = entry.getKey();
                JsonNode pre = automatic.get(channel);
                String preStatus = pre.path("status").isNull() ? "REJECTED" : pre.path("status").asText();
                String decision = entry.getValue().path("decision").asText();
                if (ORDER.indexOf(decision) < ORDER.indexOf(preStatus)) {
                    return reject("CHANNEL_FAILED_RULE", channel + " was " + preStatus + " in the automatic check; "
                            + "it cannot be marked " + decision + ".");
                }
                Set<String> reasons = new LinkedHashSet<>();
                pre.path("reasons").forEach(r -> reasons.add(r.asText()));
                entry.getValue().path("reasons").forEach(r -> reasons.add(r.asText()));
                if (!decision.equals(preStatus)) {
                    reasons.add("REVIEWER_DOWNGRADED");
                }
                result.save(RecordChange.create(SCREENING, Ids.newId(), attempt.runId(),
                        screening(attempt, channel, decision, reasons, pre, preStatus, session)),
                        "SCREENING_RECORDED", world.simTimeS());
            }
            return result.build();
        }

        private static ObjectNode screening(StoredRecord attempt, String channel, String decision, Set<String> reasons,
                                            JsonNode pre, String preStatus, Session session) {
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("attempt_id", attempt.id());
            fields.put("mission_id", attempt.body().path("mission_id").asText());
            fields.put("channel", channel);
            fields.put("named_use", NAMED_USE);
            fields.put("decision", decision);
            ArrayNode list = fields.putArray("reasons");
            reasons.forEach(list::add);
            ObjectNode summary = fields.putObject("summary");
            summary.set("valid_seconds", pre.path("valid_seconds").deepCopy());
            summary.set("volume_L", pre.path("volume_L").deepCopy());
            int cut = channel.indexOf('_');
            String unit = cut < 0 ? "value" : channel.substring(cut + 1);
            summary.set("mean_enhancement_" + unit, pre.path("mean_enhancement").deepCopy());
            summary.put("automatic_status", preStatus);
            summary.put("reviewed_by", session.actorLabel());
            return fields;
        }
    }
}
