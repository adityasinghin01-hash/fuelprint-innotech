package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.WordingPurpose;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.command.ReasonCode;
import fuelprint.core.command.Receipt;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Smoke warnings and board updates sent by the automation actor, with their limits. */
class AlertAutomationTest extends AlertsSpringTest {

    @Autowired AutomationBus automation;

    private JsonNode last(int before) {
        List<StoredRecord> added = notificationsAfter(before);
        assertThat(added).as("new notifications").isNotEmpty();
        return added.get(added.size() - 1).body();
    }

    private int automaticSendCommands() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commands WHERE command_type = 'send_alert' AND automatic = 1",
                Integer.class);
    }

    // ---- smoke warning ----

    @Test
    void anOpenInvestigationWithWarnedAreasSendsOneSmokeWarningToOnlyThoseAreas() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E", "AREA-N");
        signUp(VISITOR_1, NUMBER_C, "AREA-W");
        signUp(PRESENTER_GUEST, NUMBER_D, "AREA-S");
        int before = notifications().size();
        int commandsBefore = automaticSendCommands();

        String investigationId = openInvestigation(List.of("AREA-E"));

        assertThat(gateway.phones()).containsExactlyInAnyOrder(NUMBER_A, NUMBER_B);
        assertThat(gateway.sent).allSatisfy(sent -> {
            assertThat(sent.text()).startsWith("FuelPrint DEMO (simulated):");
            assertThat(sent.text().length()).isLessThanOrEqualTo(160);
        });
        assertThat(notificationsAfter(before)).hasSize(1);
        StoredRecord record = notificationsAfter(before).get(0);
        JsonNode body = record.body();
        assertThat(body.path("kind").asText()).isEqualTo("SMOKE_WARNING");
        assertThat(texts(body.path("area_ids"))).containsExactly("AREA-E");
        assertThat(body.path("recipients").asInt()).isEqualTo(2);
        assertThat(body.path("sent").asInt()).isEqualTo(2);
        assertThat(body.path("failed").asInt()).isZero();
        assertThat(body.path("state").asText()).isEqualTo("SENT");
        assertThat(body.path("gateway_mode").asText()).isEqualTo("LOG_ONLY");
        assertThat(body.path("suppressed_reason").isNull()).isTrue();
        assertThat(body.path("cause").path("type").asText()).isEqualTo("investigation");
        assertThat(body.path("cause").path("id").asText()).isEqualTo(investigationId);
        assertThat(body.path("cause").path("revision").asLong()).isEqualTo(1);
        assertThat(body.path("text").asText()).isEqualTo(gateway.sent.get(0).text());
        assertThat(record.createdBy()).isEqualTo("automation");
        assertThat(record.runId()).as("notification is not tied to a run").isNull();
        assertThat(automaticSendCommands()).isEqualTo(commandsBefore + 1);
    }

    @Test
    void aSecondWarningForTheSameAreaWithinTenMinutesIsSuppressedByRateLimit() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        openInvestigation(List.of("AREA-E"));
        assertThat(gateway.sent).hasSize(1);
        int before = notifications().size();

        clock.advance(Duration.ofMinutes(9));
        String second = openInvestigation(List.of("AREA-E"));

        assertThat(gateway.sent).as("nothing more was sent").hasSize(1);
        assertThat(notificationsAfter(before)).hasSize(1);
        JsonNode body = last(before);
        assertThat(body.path("state").asText()).isEqualTo("SUPPRESSED");
        assertThat(body.path("suppressed_reason").asText()).isEqualTo("RATE_LIMIT");
        assertThat(body.path("sent").asInt()).isZero();
        assertThat(body.path("kind").asText()).isEqualTo("SMOKE_WARNING");
        assertThat(texts(body.path("area_ids"))).containsExactly("AREA-E");
        assertThat(body.path("cause").path("id").asText()).isEqualTo(second);
    }

    @Test
    void afterTenMinutesTheAreaMayBeWarnedAgain() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        openInvestigation(List.of("AREA-E"));
        int before = notifications().size();

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        openInvestigation(List.of("AREA-E"));

        assertThat(gateway.sent).hasSize(2);
        assertThat(last(before).path("state").asText()).isEqualTo("SENT");
    }

    @Test
    void theRateLimitIsPerAreaSoAnotherAreaIsStillWarned() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-E", "AREA-N");
        signUp(VISITOR_1, NUMBER_C, "AREA-N");
        openInvestigation(List.of("AREA-E"));
        gateway.sent.clear();
        int before = notifications().size();

        openInvestigation(List.of("AREA-E", "AREA-N"));

        List<StoredRecord> added = notificationsAfter(before);
        assertThat(added).hasSize(2);
        JsonNode suppressed = added.stream().map(StoredRecord::body).filter(b -> "SUPPRESSED".equals(b.path("state").asText())).findFirst().orElseThrow();
        JsonNode sent = added.stream().map(StoredRecord::body).filter(b -> "SENT".equals(b.path("state").asText())).findFirst().orElseThrow();
        assertThat(suppressed.path("suppressed_reason").asText()).isEqualTo("RATE_LIMIT");
        assertThat(texts(suppressed.path("area_ids"))).containsExactly("AREA-E");
        assertThat(texts(sent.path("area_ids"))).containsExactly("AREA-N");
        assertThat(gateway.phones()).containsExactlyInAnyOrder(NUMBER_B, NUMBER_C);
    }

    @Test
    void aPersonInTwoWarnedAreasGetsOneMessage() {
        signUp(GUEST_1, NUMBER_A, "AREA-E", "AREA-N");

        openInvestigation(List.of("AREA-E", "AREA-N"));

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).text()).contains("East colony").contains("North colony");
    }

    @Test
    void withAutoAlertsOffNothingIsSentAndNothingIsSaved() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        automationSettings.set(AutomationSettings.AUTO_ALERTS, false);
        int before = notifications().size();
        int commandsBefore = automaticSendCommands();

        openInvestigation(List.of("AREA-E"));
        boardDecision("CANDIDATE_SUPPORTED_SIMULATED", List.of("AREA-E"));

        assertThat(gateway.sent).isEmpty();
        assertThat(notifications()).hasSize(before);
        assertThat(automaticSendCommands()).isEqualTo(commandsBefore);
    }

    @Test
    void automationIsRefusedByTheHandlerWhenAutoAlertsIsOff() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        automationSettings.set(AutomationSettings.AUTO_ALERTS, false);
        ObjectNode payload = object().put("kind", "TEST");
        payload.set("area_ids", array("AREA-E"));

        Receipt refused = automation.submit("send_alert", payload).join();

        assertThat(refused.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(refused.reasonCode()).isEqualTo("AUTO_ALERTS_OFF");
        assertThat(refused.actor()).isEqualTo("automation");
        assertThat(refused.automatic()).isTrue();
        assertThat(gateway.sent).isEmpty();

        // a presenter can still send a test message by hand
        Receipt byHand = bus.send(command("send_alert", payload.deepCopy(), null), PRESENTER);
        assertThat(byHand.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(gateway.sent).hasSize(1);
    }

    @Test
    void investigationsThatShouldNotWarnSendNothing() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        int before = notifications().size();

        openInvestigation(List.of());                                                 // a survey: no trigger, no area
        String id = uuid();
        saveInvestigation(id, "ANALYSED", List.of("AREA-E"), 1);                      // not OPEN
        saveInvestigation(uuid(), "OPEN", List.of("AREA-X"), 1);                      // not an alert area

        assertThat(gateway.sent).isEmpty();
        assertThat(notifications()).hasSize(before);
    }

    @Test
    void aLaterRevisionOfTheSameInvestigationDoesNotWarnAgain() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        String id = openInvestigation(List.of("AREA-E"));
        int before = notifications().size();

        saveInvestigation(id, "OPEN", List.of("AREA-E"), 2);

        assertThat(gateway.sent).hasSize(1);
        assertThat(notifications()).hasSize(before);
    }

    @Test
    void aLaterRevisionThatAddsAnAreaWarnsOnlyThatArea() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-N");
        String id = openInvestigation(List.of("AREA-E"));
        gateway.sent.clear();

        saveInvestigation(id, "OPEN", List.of("AREA-E", "AREA-N"), 2);

        assertThat(gateway.phones()).containsExactly(NUMBER_B);
    }

    @Test
    void noSignUpsMeansASuppressedNotificationNeverADroppedOne() {
        int before = notifications().size();

        openInvestigation(List.of("AREA-E"));

        assertThat(gateway.sent).isEmpty();
        JsonNode body = last(before);
        assertThat(body.path("state").asText()).isEqualTo("SUPPRESSED");
        assertThat(body.path("suppressed_reason").asText()).isEqualTo("NO_SUBSCRIBERS");
        assertThat(body.path("recipients").asInt()).isZero();
        assertThat(body.path("sent").asInt()).isZero();
    }

    @Test
    void signUpsForOtherAreasOrThatEndedDoNotCount() {
        signUp(GUEST_1, NUMBER_A, "AREA-W");
        String removed = signUp(GUEST_2, NUMBER_B, "AREA-E");
        unsubscribe(GUEST_2, removed);
        String expired = signUp(VISITOR_1, NUMBER_C, "AREA-E");
        int before = notifications().size();
        clock.advance(Duration.ofHours(15));   // the local day has ended; nobody purged yet

        openInvestigation(List.of("AREA-E"));

        assertThat(expired).isNotBlank();
        assertThat(gateway.sent).isEmpty();
        assertThat(last(before).path("suppressed_reason").asText()).isEqualTo("NO_SUBSCRIBERS");
    }

    // ---- board update ----

    @Test
    void eachOfTheFourBoardDecisionsSendsItsOwnText() {
        String[][] cases = {
                {"CANDIDATE_SUPPORTED_SIMULATED", "acted on"},
                {"MORE_INVESTIGATION", "still going"},
                {"NO_SUPPORTED_SOURCE", "Monitoring continues"},
                {"SAMPLE_INVALID", "could not be used"}};
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-W");
        List<String> seen = new java.util.ArrayList<>();

        for (String[] item : cases) {
            gateway.sent.clear();
            int before = notifications().size();

            String decisionId = boardDecision(item[0], List.of("AREA-E"));

            assertThat(gateway.sent).as(item[0]).hasSize(1);
            assertThat(gateway.phones()).containsExactly(NUMBER_A);
            String text = gateway.sent.get(0).text();
            assertThat(text).as(item[0]).startsWith("FuelPrint DEMO (simulated):").contains(item[1]).contains("East colony");
            assertThat(text.length()).isLessThanOrEqualTo(160);
            seen.add(text);
            JsonNode body = last(before);
            assertThat(body.path("kind").asText()).isEqualTo("BOARD_UPDATE");
            assertThat(body.path("state").asText()).isEqualTo("SENT");
            assertThat(body.path("cause").path("type").asText()).isEqualTo("board_decision");
            assertThat(body.path("cause").path("id").asText()).isEqualTo(decisionId);
            assertThat(texts(body.path("area_ids"))).containsExactly("AREA-E");
        }
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    void boardUpdatesAreNotRateLimitedLikeSmokeWarnings() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");

        boardDecision("MORE_INVESTIGATION", List.of("AREA-E"));
        boardDecision("CANDIDATE_SUPPORTED_SIMULATED", List.of("AREA-E"));

        assertThat(gateway.sent).hasSize(2);
    }

    @Test
    void aBoardDecisionWithNoAreasOrAnUnknownDecisionSendsNothing() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        int before = notifications().size();

        boardDecision("MORE_INVESTIGATION", List.of());
        boardDecision("MORE_INVESTIGATION", List.of("AREA-X"));
        boardDecision("SOMETHING_NEW", List.of("AREA-E"));

        assertThat(gateway.sent).isEmpty();
        assertThat(notifications()).hasSize(before);
    }

    @Test
    void aBoardDecisionWithNoSignUpsIsSuppressed() {
        int before = notifications().size();

        boardDecision("NO_SUPPORTED_SOURCE", List.of("AREA-S"));

        assertThat(last(before).path("suppressed_reason").asText()).isEqualTo("NO_SUBSCRIBERS");
    }

    // ---- who may send ----

    @Test
    void onlyPresenterAndAutomationMaySendAlerts() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        ObjectNode payload = object().put("kind", "TEST");
        payload.set("area_ids", array("AREA-E"));

        for (var session : new fuelprint.core.command.Session[] {GUEST_1, VISITOR_1, ANALYST, BOARD}) {
            Receipt receipt = bus.send(command("send_alert", payload.deepCopy(), null), session);
            assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
            assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.NOT_ALLOWED);
        }
        assertThat(gateway.sent).isEmpty();
    }

    @Test
    void sendAlertChecksItsInput() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        ObjectNode good = object().put("kind", "TEST");
        good.set("area_ids", array("AREA-E"));

        ObjectNode badKind = good.deepCopy().put("kind", "SHOUT");
        assertThat(bus.send(command("send_alert", badKind, null), PRESENTER).reasonCode()).isEqualTo(ReasonCode.INVALID);

        ObjectNode noAreas = good.deepCopy();
        noAreas.set("area_ids", array());
        assertThat(bus.send(command("send_alert", noAreas, null), PRESENTER).reasonCode()).isEqualTo("INVALID_AREA");

        ObjectNode badArea = good.deepCopy();
        badArea.set("area_ids", array("AREA-Q"));
        assertThat(bus.send(command("send_alert", badArea, null), PRESENTER).reasonCode()).isEqualTo("INVALID_AREA");

        ObjectNode smokeNoCause = good.deepCopy().put("kind", "SMOKE_WARNING");
        assertThat(bus.send(command("send_alert", smokeNoCause, null), PRESENTER).reasonCode()).isEqualTo("INVALID_CAUSE");

        ObjectNode smokeUnknownCause = smokeNoCause.deepCopy();
        smokeUnknownCause.putObject("cause").put("type", "investigation").put("id", uuid()).put("revision", 1);
        assertThat(bus.send(command("send_alert", smokeUnknownCause, null), PRESENTER).reasonCode())
                .isEqualTo("CAUSE_NOT_FOUND");

        assertThat(gateway.sent).isEmpty();
    }

    @Test
    void anAreaThatTheCauseDoesNotNameIsRefused() {
        signUp(GUEST_1, NUMBER_A, "AREA-W");
        String id = openInvestigation(List.of("AREA-E"));
        ObjectNode payload = object().put("kind", "SMOKE_WARNING");
        payload.set("area_ids", array("AREA-W"));
        payload.putObject("cause").put("type", "investigation").put("id", id).put("revision", 1);

        Receipt receipt = bus.send(command("send_alert", payload, null), PRESENTER);

        assertThat(receipt.reasonCode()).isEqualTo("AREA_NOT_IN_CAUSE");
        assertThat(gateway.phones()).doesNotContain(NUMBER_A);
    }

    @Test
    void aPresenterTestMessageGoesToTheChosenAreas() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        signUp(GUEST_2, NUMBER_B, "AREA-S");
        int before = notifications().size();
        ObjectNode payload = object().put("kind", "TEST");
        payload.set("area_ids", array("AREA-E"));

        Receipt receipt = bus.send(command("send_alert", payload, null), PRESENTER);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(receipt.actor()).isEqualTo("presenter@laptop-3");
        assertThat(receipt.automatic()).isFalse();
        assertThat(gateway.phones()).containsExactly(NUMBER_A);
        JsonNode body = last(before);
        assertThat(body.path("kind").asText()).isEqualTo("TEST");
        assertThat(body.path("cause").isNull()).isTrue();
        assertThat(body.path("text").asText()).isEqualTo(AlertTexts.test());
        assertThat(wordingService.requests).as("a test message uses no wording").isEmpty();
    }

    @Test
    void sendingTheSameAlertCommandTwiceSendsOnce() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        ObjectNode payload = object().put("kind", "TEST");
        payload.set("area_ids", array("AREA-E"));
        fuelprint.core.command.Command command = command("send_alert", payload, null);

        Receipt first = bus.send(command, PRESENTER);
        Receipt second = bus.send(command, PRESENTER);

        assertThat(second).isEqualTo(first);
        assertThat(gateway.sent).hasSize(1);
    }

    // ---- wording ----

    @Test
    void theWordingServiceGetsOnlyPublicTemplateTextAndTheRightPurpose() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");

        String investigationId = openInvestigation(List.of("AREA-E"));
        boardDecision("MORE_INVESTIGATION", List.of("AREA-E"));

        assertThat(wordingService.requests).hasSize(2);
        var smoke = wordingService.requests.get(0);
        assertThat(smoke.purpose()).isEqualTo(WordingPurpose.SMS_SMOKE_WARNING);
        assertThat(smoke.target().type()).isEqualTo("investigation");
        assertThat(smoke.target().id()).isEqualTo(investigationId);
        assertThat(smoke.target().revision()).isEqualTo(1);
        assertThat(wordingService.requests.get(1).purpose()).isEqualTo(WordingPurpose.SMS_BOARD_UPDATE);
        for (var request : wordingService.requests) {
            assertThat(request.publicText()).startsWith("FuelPrint DEMO (simulated):")
                    .doesNotContain("+91").doesNotContain(NUMBER_A).doesNotContainPattern("S0[1-6]");
        }
    }

    @Test
    void goodWordingIsUsedAndItsRecordIdIsKept() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        String text = "FuelPrint DEMO (simulated): smoke may drift toward East colony. Demo only, nothing is wrong.";
        wordingService.reply(text);
        String investigationId = uuid();
        String wordingId = uuid();
        saveWording(wordingId, "investigation", investigationId, 1, "SMS_SMOKE_WARNING", text);
        int before = notifications().size();

        saveInvestigation(investigationId, "OPEN", List.of("AREA-E"), 1);

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).text()).isEqualTo(text);
        JsonNode body = last(before);
        assertThat(body.path("text").asText()).isEqualTo(text);
        assertThat(body.path("wording_id").asText()).isEqualTo(wordingId);
    }

    @Test
    void goodWordingWithoutASavedWordingRecordLeavesTheIdNull() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        wordingService.reply("FuelPrint DEMO (simulated): smoke may drift toward East colony. Demo only.");
        int before = notifications().size();

        openInvestigation(List.of("AREA-E"));

        assertThat(last(before).path("wording_id").isNull()).isTrue();
        assertThat(gateway.sent.get(0).text()).contains("may drift");
    }

    @Test
    void badWordingFallsBackToTheTemplate() {
        String template = AlertTexts.smokeWarning(List.of("AREA-E"));
        String[] bad = {
                "FuelPrint DEMO (simulated): smoke from S02 may reach East colony. Demo only.",       // a factory label
                "FuelPrint DEMO (simulated): s06 is the likely source. Demo only.",                   // a factory label
                "FuelPrint DEMO (simulated): an illegal release may reach East colony.",             // banned word
                "FuelPrint DEMO (simulated): the source is guilty of smoke.",                         // banned word
                "FuelPrint DEMO (simulated): there is a 5% chance of smoke.",                        // banned word + percent
                "FuelPrint DEMO (simulated): coal smoke may reach East colony.",                      // banned word
                "FuelPrint DEMO (simulated): the probability of smoke is high.",                      // banned word
                "FuelPrint DEMO (simulated): " + "x".repeat(200 - 28),                                // 200 characters
                "Smoke may reach East colony. Demo only.",                                            // missing prefix
                "FuelPrint DEMO (simulated): smoke is 3 km away from you.",                           // a number not in the input
                ""};                                                                                  // nothing
        for (String text : bad) {
            signUp(GUEST_1, NUMBER_A, "AREA-E");
            wordingService.reply(text);
            gateway.sent.clear();
            int before = notifications().size();
            clock.advance(Duration.ofMinutes(11));

            openInvestigation(List.of("AREA-E"));

            assertThat(gateway.sent).as(text).hasSize(1);
            assertThat(gateway.sent.get(0).text()).as(text).isEqualTo(template);
            assertThat(last(before).path("text").asText()).as(text).isEqualTo(template);
            assertThat(last(before).path("wording_id").isNull()).isTrue();
            assertThat(last(before).path("state").asText()).isEqualTo("SENT");
            unsubscribeAll();
        }
    }

    @Test
    void aBadBoardWordingFallsBackToTheMatchingTemplate() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        wordingService.reply("FuelPrint DEMO (simulated): S03 was proved guilty.");

        boardDecision("NO_SUPPORTED_SOURCE", List.of("AREA-E"));

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.get(0).text()).isEqualTo(AlertTexts.boardUpdate("NO_SUPPORTED_SOURCE", List.of("AREA-E")));
    }

    @Test
    void aWordingServiceThatThrowsOrIsMissingFallsBackToo() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        var absent = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        AlertWording wording = new AlertWording(absent.getBeanProvider(fuelprint.api.WordingService.class),
                absent.getBeanProvider(fuelprint.core.command.HandlerRegistry.class));
        var chosen = wording.choose(new fuelprint.api.Ref("investigation", uuid(), 1), WordingPurpose.SMS_SMOKE_WARNING,
                "FuelPrint DEMO (simulated): x", store, RUN_ID);
        assertThat(chosen.text()).isEqualTo("FuelPrint DEMO (simulated): x");
        assertThat(chosen.fallbackReason()).isEqualTo("NO_WORDING_SERVICE");
    }

    // ---- helpers ----

    static final fuelprint.core.command.Session PRESENTER_GUEST = fuelprint.core.command.Session.human(
            "guest-session-4", "guest-4444", java.util.Set.of(fuelprint.core.command.Role.GUEST));

    private void unsubscribeAll() {
        for (StoredRecord record : activeSubscriptions()) {
            unsubscribe(PRESENTER, record.id());
        }
    }

    private void saveWording(String id, String targetType, String targetId, long targetRevision, String purpose,
                             String text) {
        ObjectNode fields = object();
        fields.putObject("target").put("type", targetType).put("id", targetId).put("revision", targetRevision);
        fields.put("purpose", purpose).put("text", text).put("source", "LLM");
        ObjectNode payload = object().put("id", id).put("run_id", RUN_ID);
        payload.set("fields", fields);
        Receipt receipt = bus.send(command("test_save_wording", payload, null), ANALYST);
        assertThat(receipt.status()).as(receipt.reasonText()).isEqualTo(Receipt.Status.APPLIED);
    }
}
