package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.Command;
import fuelprint.core.command.Receipt;
import fuelprint.core.store.StoredRecord;
import fuelprint.core.store.RecordChange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** F5 capture stages and F6 QC review (AGREEMENT sections 5, 6, 8; field plan "The capture rules"). */
class CaptureTest extends FieldSpringTest {

    @Autowired AutomationBus automationBus;

    /** Starts the autopilot (automatic capture on) and ticks until the attempt left COLLECTING. Returns the mission id. */
    String flyAndCapture() {
        settings.set(AutomationSettings.AUTO_CAPTURE, true);
        String mission = approvedMission();
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> !records("attempt").isEmpty(), 3000, "capture started");
        tickUntil(() -> !"COLLECTING".equals(onlyAttempt().body().path("state").asText()), 3000, "capture ended");
        return mission;
    }

    JsonNode channel(JsonNode attempt, String name) {
        for (JsonNode c : attempt.path("channels")) {
            if (name.equals(c.path("channel").asText())) {
                return c;
            }
        }
        throw new AssertionError("no channel " + name);
    }

    JsonNode stage(JsonNode attempt, String name) {
        for (JsonNode s : attempt.path("stages")) {
            if (name.equals(s.path("stage").asText())) {
                return s;
            }
        }
        throw new AssertionError("no stage " + name);
    }

    double length(JsonNode attempt, String name) {
        return stage(attempt, name).path("end_s").asDouble() - stage(attempt, name).path("start_s").asDouble();
    }

    ObjectNode review(String attemptId, String so2, String co2) {
        ObjectNode p = payload().put("attempt_id", attemptId);
        ArrayNode list = p.putArray("channel_decisions");
        list.addObject().put("channel", "SO2_ppb").put("decision", so2);
        list.addObject().put("channel", "CO2_ppm").put("decision", co2);
        return p;
    }

    @Test
    void captureStartsByItselfAtTheTargetAndRunsTheStagesAtTimeScale15() {
        String mission = flyAndCapture();
        assertThat(appliedAutomatic("start_capture")).hasSize(1);
        StoredRecord attempt = onlyAttempt();
        JsonNode body = attempt.body();
        assertThat(body.path("method_id").asText()).isEqualTo("TEACH-NODE-ONLINE-2");
        assertThat(body.path("mission_id").asText()).isEqualTo(mission);
        assertThat(body.path("state").asText()).isEqualTo("COMPLETE");
        assertThat(clock.log).contains("request field-capture 15.0");
        assertThat(clock.log.get(clock.log.size() - 1)).isEqualTo("release field-capture");

        // Stage times: setup 30, background 30, purge 30, settle >= 120, collect 30 valid, recover >= 120, review 30.
        assertThat(length(body, "SETUP")).isEqualTo(30.0);
        assertThat(length(body, "BACKGROUND")).isEqualTo(30.0);
        assertThat(length(body, "PURGE")).isEqualTo(30.0);
        assertThat(length(body, "SETTLE")).isEqualTo(120.0);
        assertThat(length(body, "COLLECT")).isCloseTo(30.0, within(1.0));
        assertThat(length(body, "RECOVER")).isGreaterThanOrEqualTo(120.0).isLessThan(122.0);
        assertThat(length(body, "REVIEW")).isEqualTo(30.0);
        List<String> order = new ArrayList<>();
        body.path("stages").forEach(s -> order.add(s.path("stage").asText()));
        assertThat(order).containsExactly("SETUP", "BACKGROUND", "PURGE", "SETTLE", "COLLECT", "RECOVER", "REVIEW");
        for (int i = 1; i < order.size(); i++) {
            assertThat(body.path("stages").get(i).path("start_s").asDouble())
                    .isEqualTo(body.path("stages").get(i - 1).path("end_s").asDouble());
        }

        JsonNode so2 = channel(body, "SO2_ppb");
        JsonNode co2 = channel(body, "CO2_ppm");
        assertThat(so2.path("status").asText()).isEqualTo("VALID");
        assertThat(so2.path("valid_seconds").asDouble()).isEqualTo(30.0);
        assertThat(so2.path("volume_L").asDouble()).isCloseTo(0.25, within(1e-9));
        assertThat(co2.path("status").asText()).isEqualTo("VALID");
        assertThat(co2.path("volume_L").asDouble()).isCloseTo(0.15, within(1e-9));
        assertThat(so2.path("checked_by").asText()).isEqualTo("CAPTURE_RULES");
        // The rules saw the stages and the inlet readings of the channel with its branch flow.
        assertThat(rules.calls).isNotEmpty();
        assertThat(rules.calls.get(0).readings()).anyMatch(r -> r.unit().equals("L_min"));
        assertThat(missionState(mission)).isIn("RETURNING", "COMPLETE");
        assertThat(clock.timeScale()).isEqualTo(1.0);
    }

    @Test
    void theWholeCaptureFitsInAboutSevenMinutesOfSimulatedTimeAndAFewHundredLoopTicks() {
        flyAndCapture();
        JsonNode body = onlyAttempt().body();
        double total = stage(body, "REVIEW").path("end_s").asDouble() - stage(body, "SETUP").path("start_s").asDouble();
        assertThat(total).isBetween(390.0, 394.0);
    }

    @Test
    void noAutomaticCaptureWhenAutoCaptureIsOffAndTheOperatorCanStartIt() {
        String mission = approvedMission();
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> missionState(mission).equals("AT_TARGET"), 3000, "AT_TARGET");
        ticks(40);
        assertThat(automatic("start_capture")).isEmpty();
        assertThat(records("attempt")).isEmpty();

        // The automation actor is refused while the setting is off.
        Receipt refused = automationBus.submit("start_capture", capturePayload(mission), runId, null).join();
        assertThat(refused.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(refused.reasonCode()).isEqualTo("AUTOMATION_OFF");

        Receipt started = send(OPERATOR, "start_capture", capturePayload(mission));
        assertThat(started.applied()).as(started.reasonText()).isTrue();
        assertThat(missionState(mission)).isEqualTo("COLLECTING");
        assertThat(onlyAttempt().body().path("state").asText()).isEqualTo("COLLECTING");
        assertThat(send(OPERATOR, "start_capture", capturePayload(mission)).reasonCode()).isEqualTo("NOT_AT_TARGET");
    }

    ObjectNode capturePayload(String mission) {
        ObjectNode p = payload().put("mission_id", mission).put("method_id", "TEACH-NODE-ONLINE-2");
        p.putArray("channel_ids").add("SO2_ppb").add("CO2_ppm");
        return p;
    }

    @Test
    void startCaptureNeedsTheTargetTheRightMethodAndTheMissionsChannels() {
        String mission = approvedMission();
        assertThat(send(OPERATOR, "start_capture", capturePayload(mission)).reasonCode()).isEqualTo("NOT_AT_TARGET");
        assertThat(send(QC, "start_capture", capturePayload(mission)).reasonCode()).isEqualTo("NOT_ALLOWED");

        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> missionState(mission).equals("AT_TARGET"), 3000, "AT_TARGET");
        ObjectNode wrongMethod = capturePayload(mission).put("method_id", "TEACH-AEROSOL-S-1");
        assertThat(send(OPERATOR, "start_capture", wrongMethod).reasonCode()).isEqualTo("METHOD_MISMATCH");
        ObjectNode wrongChannel = payload().put("mission_id", mission).put("method_id", "TEACH-NODE-ONLINE-2");
        wrongChannel.putArray("channel_ids").add("PM_ugm3");
        assertThat(send(OPERATOR, "start_capture", wrongChannel).reasonCode()).isEqualTo("INVALID");
        ObjectNode none = payload().put("mission_id", mission).put("method_id", "TEACH-NODE-ONLINE-2");
        none.putArray("channel_ids");
        assertThat(send(OPERATOR, "start_capture", none).reasonCode()).isEqualTo("INVALID");
    }

    @Test
    void sc2BlockedInletRejectsSo2With10ValidSecondsAndKeepsCo2Valid() {
        feed = this::blockedSo2AtCollectSecond10;
        flyAndCapture();
        JsonNode body = onlyAttempt().body();
        JsonNode so2 = channel(body, "SO2_ppb");
        JsonNode co2 = channel(body, "CO2_ppm");
        assertThat(so2.path("status").asText()).isEqualTo("REJECTED");
        assertThat(so2.path("valid_seconds").asDouble()).isEqualTo(10.0);
        assertThat(so2.path("volume_L").asDouble()).isCloseTo(0.0833, within(0.0005));
        assertThat(so2.path("reasons").toString()).contains("FLOW_FAULT");
        assertThat(co2.path("status").asText()).isEqualTo("VALID");
        assertThat(co2.path("valid_seconds").asDouble()).isEqualTo(30.0);
        assertThat(co2.path("volume_L").asDouble()).isCloseTo(0.15, within(1e-9));
        assertThat(body.path("state").asText()).as("one usable channel is enough").isEqualTo("COMPLETE");
        assertThat(so2.path("volume_L").isNull()).isFalse();
    }

    @Test
    void withoutCaptureRulesEveryChannelIsRejectedWithTheReasonNotTurnedIntoAPass() {
        rules.enabled = false;
        flyAndCapture();
        JsonNode body = onlyAttempt().body();
        for (String name : List.of("SO2_ppb", "CO2_ppm")) {
            assertThat(channel(body, name).path("status").asText()).isEqualTo("REJECTED");
            assertThat(channel(body, name).path("reasons").toString()).contains("CAPTURE_RULES_UNAVAILABLE");
            assertThat(channel(body, name).path("checked_by").asText()).isEqualTo("FIELD");
        }
        assertThat(body.path("state").asText()).isEqualTo("FAILED");
    }

    /** Isolated Field fixture: real capture/interrupt handling, controlled faulty observation feed and authority double. */
    @Test void flowFaultThenResetInterruptsCaptureWithoutPublishingUsableEvidence() {
        feed=this::blockedSo2AtCollectSecond10;
        settings.set(AutomationSettings.AUTO_CAPTURE,true);
        String mission=approvedMission();
        assertThat(send(OPERATOR,"start_autopilot",missionVehicle(mission)).applied()).isTrue();
        tickUntil(()->world.captureSnapshot().map(s->s.counts().get("SO2_ppb")!=null
                &&s.counts().get("SO2_ppb").reasons().contains("FLOW_FAULT")).orElse(false),6000,"flow fault observed during capture");
        var attempt=onlyAttempt();assertThat(attempt.body().path("state").asText()).isEqualTo("COLLECTING");
        events.publishEvent(new fuelprint.api.RunEnded(runId,fuelprint.api.RunEnded.Reason.RESET));settle();
        assertThat(record("attempt",attempt.id()).body().path("state").asText()).isEqualTo("INTERRUPTED");
        assertThat(missionState(mission)).isEqualTo("INTERRUPTED");
        assertThat(world.captureRunning()).isFalse();assertThat(world.lease()).isEmpty();assertThat(speed()).isZero();
        assertThat(records("screening_record")).isEmpty();assertThat(records("window")).isEmpty();
        var rejected=send(QC,"review_capture",review(attempt.id(),"VALID","VALID"));
        assertThat(rejected.applied()).isFalse();assertThat(records("screening_record")).isEmpty();
        var revisions=store.revisions("attempt",attempt.id());
        events.publishEvent(new fuelprint.api.RunEnded(runId,fuelprint.api.RunEnded.Reason.RESET));settle();
        assertThat(store.revisions("attempt",attempt.id())).isEqualTo(revisions);
    }

    @Test
    void returningHomeMidCaptureInterruptsTheAttemptAndRejectsItsChannels() {
        settings.set(AutomationSettings.AUTO_CAPTURE, true);
        String mission = approvedMission();
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> !records("attempt").isEmpty(), 3000, "capture started");
        ticks(60);
        Receipt home = send(SUPERVISOR, "return_to_depot", payload().put("vehicle_id", world.droneId())
                .put("reason", "OPERATOR"));
        assertThat(home.applied()).as(home.reasonText()).isTrue();
        JsonNode body = onlyAttempt().body();
        assertThat(body.path("state").asText()).isEqualTo("INTERRUPTED");
        assertThat(channel(body, "SO2_ppb").path("status").asText()).isEqualTo("REJECTED");
        assertThat(channel(body, "SO2_ppb").path("reasons").toString()).contains("ATTEMPT_INTERRUPTED");
        assertThat(stage(body, body.path("stages").get(body.path("stages").size() - 1).path("stage").asText())
                .path("end_s").isNull()).isFalse();
        assertThat(missionState(mission)).isEqualTo("RETURNING");
        ticks(5);
        assertThat(world.captureRunning()).isFalse();
        assertThat(clock.timeScale()).isEqualTo(1.0);
        // A retry is a NEW attempt, never a restart of this one.
        tickUntil(() -> missionState(mission).equals("COMPLETE"), 3000, "home");
    }

    // ------------------------------------------------------------ F6 review

    @Test
    void reviewWritesOneScreeningRecordPerChannelWithTheNamedUse() {
        flyAndCapture();
        StoredRecord attempt = onlyAttempt();
        Receipt receipt = send(QC, "review_capture", review(attempt.id(), "VALID", "VALID"));
        assertThat(receipt.applied()).as(receipt.reasonText()).isTrue();
        List<StoredRecord> screening = records("screening_record");
        assertThat(screening).hasSize(2);
        for (StoredRecord record : screening) {
            JsonNode b = record.body();
            assertThat(b.path("attempt_id").asText()).isEqualTo(attempt.id());
            assertThat(b.path("named_use").asText()).isEqualTo("SYNTHETIC_ONLINE_SCREENING");
            assertThat(b.path("decision").asText()).isEqualTo("VALID");
            assertThat(b.path("reasons").isArray()).isTrue();
            assertThat(b.path("summary").path("valid_seconds").asDouble()).isEqualTo(30.0);
            assertThat(b.path("summary").path("reviewed_by").asText()).isEqualTo(QC.actorLabel());
        }
        assertThat(screening.stream().map(r -> r.body().path("channel").asText())).containsExactlyInAnyOrder("SO2_ppb", "CO2_ppm");
        assertThat(screening.get(0).body().path("summary").has("mean_enhancement_ppb")
                || screening.get(0).body().path("summary").has("mean_enhancement_ppm")).isTrue();
        assertThat(send(QC, "review_capture", review(attempt.id(), "VALID", "VALID")).reasonCode())
                .isEqualTo("ALREADY_REVIEWED");
        assertThat(records("screening_record")).hasSize(2);
    }

    @Test
    void reviewCannotPassAChannelThatFailedARule() {
        feed = this::blockedSo2AtCollectSecond10;
        flyAndCapture();
        String attempt = onlyAttempt().id();
        for (String better : List.of("VALID", "LIMITED")) {
            Receipt receipt = send(QC, "review_capture", review(attempt, better, "VALID"));
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo("CHANNEL_FAILED_RULE");
        }
        assertThat(records("screening_record")).isEmpty();

        Receipt ok = send(QC, "review_capture", review(attempt, "REJECTED", "VALID"));
        assertThat(ok.applied()).as(ok.reasonText()).isTrue();
        Map<String, JsonNode> byChannel = new java.util.HashMap<>();
        records("screening_record").forEach(r -> byChannel.put(r.body().path("channel").asText(), r.body()));
        assertThat(byChannel.get("SO2_ppb").path("decision").asText()).isEqualTo("REJECTED");
        assertThat(byChannel.get("SO2_ppb").path("reasons").toString()).contains("FLOW_FAULT");
        assertThat(byChannel.get("SO2_ppb").path("summary").path("valid_seconds").asDouble()).isEqualTo(10.0);
        assertThat(byChannel.get("CO2_ppm").path("decision").asText()).isEqualTo("VALID");
    }

    @Test
    void aReviewerCanDowngradeButNeverUpgrade() {
        flyAndCapture();
        String attempt = onlyAttempt().id();
        Receipt receipt = send(QC, "review_capture", review(attempt, "LIMITED", "REJECTED"));
        assertThat(receipt.applied()).as(receipt.reasonText()).isTrue();
        Map<String, JsonNode> byChannel = new java.util.HashMap<>();
        records("screening_record").forEach(r -> byChannel.put(r.body().path("channel").asText(), r.body()));
        assertThat(byChannel.get("SO2_ppb").path("decision").asText()).isEqualTo("LIMITED");
        assertThat(byChannel.get("SO2_ppb").path("reasons").toString()).contains("REVIEWER_DOWNGRADED");
        assertThat(byChannel.get("CO2_ppm").path("decision").asText()).isEqualTo("REJECTED");
    }

    @Test
    void reviewRefusesTheAutomationActorAndOtherRoles() {
        flyAndCapture();
        String attempt = onlyAttempt().id();
        Receipt automation = automationBus.submit("review_capture", review(attempt, "VALID", "VALID"), runId, null).join();
        assertThat(automation.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(automation.reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(automation.automatic()).isTrue();
        assertThat(send(OPERATOR, "review_capture", review(attempt, "VALID", "VALID")).reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(send(SUPERVISOR, "review_capture", review(attempt, "VALID", "VALID")).reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(send(sessions.createVisitor("visitor-r1"), "review_capture", review(attempt, "VALID", "VALID")).reasonCode())
                .isEqualTo("NOT_ALLOWED");
        assertThat(records("screening_record")).isEmpty();
    }

    @Test
    void reviewNeedsEveryChannelOnceAndAFinishedCapture() {
        settings.set(AutomationSettings.AUTO_CAPTURE, true);
        String mission = approvedMission();
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> !records("attempt").isEmpty(), 3000, "capture started");
        String attempt = onlyAttempt().id();
        assertThat(send(QC, "review_capture", review(attempt, "VALID", "VALID")).reasonCode()).isEqualTo("NOT_READY");
        tickUntil(() -> !"COLLECTING".equals(onlyAttempt().body().path("state").asText()), 3000, "capture ended");

        ObjectNode missing = payload().put("attempt_id", attempt);
        missing.putArray("channel_decisions").addObject().put("channel", "SO2_ppb").put("decision", "VALID");
        assertThat(send(QC, "review_capture", missing).reasonCode()).isEqualTo("INVALID");
        ObjectNode twice = review(attempt, "VALID", "VALID");
        ((ArrayNode) twice.get("channel_decisions")).addObject().put("channel", "SO2_ppb").put("decision", "VALID");
        assertThat(send(QC, "review_capture", twice).reasonCode()).isEqualTo("INVALID");
        ObjectNode strange = review(attempt, "GREAT", "VALID");
        assertThat(send(QC, "review_capture", strange).reasonCode()).isEqualTo("INVALID");
        assertThat(send(QC, "review_capture", payload().put("attempt_id", UUID.randomUUID().toString())).reasonCode())
                .isEqualTo("NOT_FOUND");
        assertThat(records("screening_record")).isEmpty();
    }

    @Test
    void aRepeatedReviewCommandIdAppliesOnce() {
        flyAndCapture();
        String attempt = onlyAttempt().id();
        Command command = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "review_capture", runId,
                null, review(attempt, "VALID", "VALID"));
        Receipt first = bus.send(command, QC);
        Receipt second = bus.send(command, QC);
        settle();
        assertThat(first.applied()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(records("screening_record")).hasSize(2);
        assertThat(records("screening_record")).allSatisfy(r -> assertThat(r.revision()).isEqualTo(1));
    }

    @Test
    void aStaleAttemptRevisionIsRefused() {
        flyAndCapture();
        StoredRecord attempt = onlyAttempt();
        Receipt stale = send(QC, "review_capture", review(attempt.id(), "VALID", "VALID"), attempt.revision() - 1);
        assertThat(stale.reasonCode()).isEqualTo("STALE_REVISION");
        Receipt fresh = send(QC, "review_capture", review(attempt.id(), "VALID", "VALID"), attempt.revision());
        assertThat(fresh.applied()).isTrue();
    }
    /** Public saved reading fixtures: canonical snake_case, never the expired in-memory snapshot. */
    void saveCaptureReadings(StoredRecord attempt) {
        for (String stageName : List.of("BACKGROUND", "COLLECT")) {
            JsonNode interval = stage(attempt.body(), stageName);
            for (double t = interval.path("start_s").asDouble(); t < interval.path("end_s").asDouble() - 1e-6; t++) {
                for (String channel : List.of("SO2_ppb", "CO2_ppm", "SO2_flow_L_min", "CO2_flow_L_min")) {
                    double value = channel.contains("flow") ? (channel.startsWith("SO2") ? .5 : .3)
                            : (stageName.equals("BACKGROUND") ? 10 : 45);
                    ObjectNode fields = payload().put("sensor_or_vehicle_id", world.droneId()).put("channel", channel)
                            .put("value", value).put("raw_value", value).put("unit", channel.contains("flow") ? "L_min" : "ppb")
                            .put("sim_time_s", t).put("origin", "SYNTHETIC_TEACHING");
                    fields.putArray("quality").add("OK");
                    store.save(RecordChange.create("reading", UUID.randomUUID().toString(), runId, fields), "test", null);
                    // An unrelated vehicle's broken record at exactly the same time must not contaminate input.
                    fields.put("sensor_or_vehicle_id", "unrelated-vehicle").putNull("value");
                    store.save(RecordChange.create("reading", UUID.randomUUID().toString(), runId, fields), "test", null);
                }
            }
        }
    }

    @Test
    void humanValidReviewPersistsMeasuredWindowsAndScreeningsInOneReceiptAfterSnapshotIsGone() {
        rules.realWindows = true;
        flyAndCapture();
        StoredRecord attempt = onlyAttempt();
        assertThat(world.captureSnapshot(attempt.id())).isEmpty();
        assertThat(records("window")).isEmpty();
        saveCaptureReadings(attempt);
        assertThat(send(QC, "review_capture", review(attempt.id(), "VALID", "VALID"), attempt.revision() - 1).reasonCode())
                .isEqualTo("STALE_REVISION");
        assertThat(records("window")).isEmpty();
        Command command = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "review_capture", runId,
                attempt.revision(), review(attempt.id(), "VALID", "VALID"));
        Receipt first = bus.send(command, QC);
        Receipt repeated = bus.send(command, QC);
        settle();
        assertThat(first.applied()).as(first.reasonText()).isTrue();
        assertThat(repeated).isEqualTo(first);
        assertThat(first.recordRefs()).hasSize(6);
        assertThat(records("window")).hasSize(4);
        assertThat(records("window").stream().filter(r -> !r.body().path("background_window_id").isNull()))
                .hasSize(2).allSatisfy(r -> {
                    assertThat(r.body().path("mean_enhancement").asDouble()).isEqualTo(35);
                    assertThat(r.body().path("valid_seconds").asDouble()).isEqualTo(30);
                    assertThat(r.body().path("sensor_or_vehicle_id").asText()).isEqualTo(world.droneId());
                    assertThat(record("window", r.body().path("background_window_id").asText()).body().path("mean_enhancement").isNull()).isTrue();
                });
        assertThat(records("screening_record")).hasSize(2).allSatisfy(r ->
                assertThat(r.body().path("summary").path("mean_enhancement_" + (r.body().path("channel").asText().startsWith("SO2") ? "ppb" : "ppm")).asDouble()).isEqualTo(35));
    }

    @Test
    void downgradedAndFailedChannelsNeverProduceUsableWindows() {
        rules.realWindows = true;
        feed = this::blockedSo2AtCollectSecond10;
        flyAndCapture();
        StoredRecord attempt = onlyAttempt();
        saveCaptureReadings(attempt);
        assertThat(send(QC, "review_capture", review(attempt.id(), "VALID", "VALID")).reasonCode()).isEqualTo("CHANNEL_FAILED_RULE");
        assertThat(records("window")).isEmpty();
        assertThat(send(QC, "review_capture", review(attempt.id(), "REJECTED", "LIMITED")).applied()).isTrue();
        assertThat(records("window")).isEmpty();
    }

    @Test
    void interruptedAttemptCannotAdmitPreviouslyValidAutomaticChannels() {
        rules.realWindows = true;
        settings.set(AutomationSettings.AUTO_CAPTURE, true);
        String mission = approvedMission();
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> !records("attempt").isEmpty() && "VALID".equals(channel(onlyAttempt().body(), "SO2_ppb").path("status").asText()),
                3000, "automatic collection verdict");
        assertThat(send(SUPERVISOR, "return_to_depot", payload().put("vehicle_id", world.droneId()).put("reason", "OPERATOR")).applied()).isTrue();
        StoredRecord attempt = onlyAttempt();
        assertThat(attempt.body().path("state").asText()).isEqualTo("INTERRUPTED");
        saveCaptureReadings(attempt);
        assertThat(send(QC, "review_capture", review(attempt.id(), "VALID", "VALID")).applied()).isTrue();
        assertThat(records("window")).isEmpty();
        assertThat(records("screening_record")).allSatisfy(r ->
                assertThat(r.body().path("reasons").toString()).contains("ATTEMPT_INTERRUPTED"));
    }

    @Test
    void emptyDefaultWindowHookReportsUnknownEnhancementAndDiagnostic() {
        flyAndCapture();
        StoredRecord attempt = onlyAttempt();
        saveCaptureReadings(attempt);
        assertThat(send(QC, "review_capture", review(attempt.id(), "VALID", "VALID")).applied()).isTrue();
        assertThat(records("window")).isEmpty();
        assertThat(records("screening_record")).allSatisfy(r -> {
            assertThat(r.body().path("reasons").toString()).contains("CAPTURE_WINDOWS_UNAVAILABLE");
            String unit = r.body().path("channel").asText().startsWith("SO2") ? "ppb" : "ppm";
            assertThat(r.body().path("summary").path("mean_enhancement_" + unit).isNull()).isTrue();
        });
    }

    @Test
    void failedWindowHookReportsFailureWithoutSavingEvidenceOrInventingZero() {
        rules.failWindows = true;
        flyAndCapture();
        StoredRecord attempt = onlyAttempt();
        saveCaptureReadings(attempt);
        assertThat(send(QC, "review_capture", review(attempt.id(), "VALID", "VALID")).applied()).isTrue();
        assertThat(records("window")).isEmpty();
        assertThat(records("screening_record")).allSatisfy(r -> {
            assertThat(r.body().path("reasons").toString()).contains("CAPTURE_WINDOWS_FAILED");
            String unit = r.body().path("channel").asText().startsWith("SO2") ? "ppb" : "ppm";
            assertThat(r.body().path("summary").path("mean_enhancement_" + unit).isNull()).isTrue();
        });
    }

}
