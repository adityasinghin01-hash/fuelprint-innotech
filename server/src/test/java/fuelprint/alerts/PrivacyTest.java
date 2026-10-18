package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.core.command.TestHeaderSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AGREEMENT ground rule 10: a phone number exists only in the contact table. After a full flow no full
 * number is in any record, event, command, notification text, receipt, API response, wording request or
 * log line, and a purge leaves no number in the contact table.
 */
class PrivacyTest extends AlertsSpringTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static final List<String> NUMBERS = List.of(NUMBER_A, NUMBER_B, NUMBER_C, NUMBER_D);

    /** Every way a number could be written. */
    private static List<String> spellings(String e164) {
        String national = e164.substring(3);
        return List.of(e164, e164.substring(1), national, "+91 " + national, "+91 " + national.substring(0, 5) + " " + national.substring(5),
                "+91 " + national.substring(0, 5) + "-" + national.substring(5), national.substring(0, 5) + " " + national.substring(5));
    }

    private JsonNode postCommand(String command, String body, String actor, String roles) throws Exception {
        String reply = mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(TestHeaderSessionService.ACTOR, actor).header(TestHeaderSessionService.ROLES, roles))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNoNumber("API response to " + command, reply);
        return mapper.readTree(reply);
    }

    private static void assertNoNumber(String where, String text) {
        if (text == null) {
            return;
        }
        for (String number : NUMBERS) {
            for (String spelling : spellings(number)) {
                assertThat(text).as(where + " must not contain " + number.substring(0, 5) + "...").doesNotContain(spelling);
            }
        }
    }

    /** Every cell of every table except the contact table. */
    private List<String> everythingSavedOutsideTheContactTable() {
        List<String> cells = new ArrayList<>();
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'alert_contact'",
                String.class);
        assertThat(tables).contains("records", "events", "commands");
        for (String table : tables) {
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM \"" + table + "\"")) {
                row.forEach((column, value) -> cells.add(table + "." + column + " = " + value));
            }
        }
        return cells;
    }

    private List<String> logLines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                lines.add(proxy.getClassName() + ": " + proxy.getMessage());
            }
        }
        return lines;
    }

    private void assertNothingLeaked(String stage) {
        everythingSavedOutsideTheContactTable().forEach(cell -> assertNoNumber(stage + " / " + cell.substring(0, Math.min(60, cell.length())), cell));
        logLines().forEach(line -> assertNoNumber(stage + " / log line", line));
        wordingService.requests.forEach(request -> assertNoNumber(stage + " / wording request", request.publicText()));
        gateway.sent.forEach(sent -> assertNoNumber(stage + " / SMS text", sent.text()));
        notifications().forEach(record -> assertNoNumber(stage + " / notification", record.body().toString()));
        store.list("alert_subscription", null).forEach(record -> assertNoNumber(stage + " / subscription", record.body().toString()));
    }

    @Test
    void aFullFlowLeavesNoNumberAnywhereButTheContactTable() throws Exception {
        // 1. Sign-ups through the real HTTP endpoint and through the bus, plus a refused one.
        String subscribeBody = "{\"schema_version\":\"fp/1\",\"command_id\":\"" + uuid() + "\",\"command_type\":\"subscribe_alerts\","
                + "\"run_id\":null,\"expected_revision\":null,\"payload\":{\"phone_number\":\"" + NUMBER_A
                + "\",\"area_ids\":[\"AREA-E\",\"AREA-N\"],\"consent\":true,\"consent_text_version\":\"FP-CONSENT-1\"}}";
        JsonNode receipt = postCommand("subscribe_alerts", subscribeBody, "guest-21c9", "guest");
        assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");
        assertThat(receipt.path("actor").asText()).isEqualTo("guest-21c9");
        String idA = receipt.path("record_refs").get(0).path("id").asText();

        String refusedBody = subscribeBody.replace(NUMBER_A, NUMBER_C).replace("\"consent\":true", "\"consent\":false")
                .replace(subscribeBody.substring(subscribeBody.indexOf("\"command_id\":\"") + 14, subscribeBody.indexOf("\"command_id\":\"") + 50), uuid());
        JsonNode refused = postCommand("subscribe_alerts", refusedBody, "guest-77aa", "guest");
        assertThat(refused.path("status").asText()).isEqualTo("REJECTED");
        assertThat(refused.path("reason_code").asText()).isEqualTo("CONSENT_REQUIRED");

        String idB = signUp(GUEST_2, NUMBER_B, "AREA-E");
        subscribe(GUEST_1, "+91 70123 45670", true);                    // no area: refused
        subscribe(GUEST_1, "+14155550123", true, "AREA-E");             // not an Indian number: refused

        // 2. The contact table has exactly the two good numbers, and the positive controls hold.
        assertThat(contactRows()).isEqualTo(2);
        assertThat(everythingSavedOutsideTheContactTable()).anyMatch(cell -> cell.contains("+91 98xxxxxx78"));

        // 3. Automatic warning, test message, board update, a removal.
        openInvestigation(List.of("AREA-E", "AREA-N"));
        boardDecision("CANDIDATE_SUPPORTED_SIMULATED", List.of("AREA-E"));
        assertThat(gateway.phones()).contains(NUMBER_A, NUMBER_B);
        assertThat(logLines()).anyMatch(line -> line.contains("+91 98xxxxxx78")).as("the masked number is what gets logged");
        assertThat(unsubscribe(GUEST_2, idB).status().name()).isEqualTo("APPLIED");
        assertNothingLeaked("after the flow");

        // 4. The sign-up page's own read of its record (what an API response shows) is masked.
        assertThat(store.get("alert_subscription", idA).orElseThrow().body().path("phone_masked").asText())
                .isEqualTo("+91 98xxxxxx78");

        // 5. Purge: nothing left in the contact table, still nothing anywhere else.
        assertThat(bus.send(command("purge_subscriptions", object(), null), PRESENTER).status().name()).isEqualTo("APPLIED");
        assertThat(contactRows()).isZero();
        assertThat(jdbc.queryForList("SELECT phone_e164 FROM alert_contact", String.class)).isEmpty();
        assertNothingLeaked("after the purge");
    }

    @Test
    void theCommandsTableKeepsNoNumberEvenForRefusedSignUps() {
        subscribe(GUEST_1, NUMBER_D, false, "AREA-E");     // refused: no consent
        subscribe(GUEST_1, NUMBER_D, true);                // refused: no area
        subscribe(ANALYST, NUMBER_D, true, "AREA-E");      // refused: wrong role

        List<String> payloads = jdbc.queryForList("SELECT payload FROM commands WHERE command_type = 'subscribe_alerts'", String.class);
        assertThat(payloads).isNotEmpty();
        payloads.forEach(payload -> assertNoNumber("stored command payload", payload));
        everythingSavedOutsideTheContactTable().forEach(cell -> assertNoNumber("saved cell", cell));
    }

    @Test
    void theAlertsHandlersNameTheirPrivateField() {
        assertThat(new SubscribeAlertsHandler(null, null).privatePayloadFields()).containsExactly("phone_number");
    }
}
