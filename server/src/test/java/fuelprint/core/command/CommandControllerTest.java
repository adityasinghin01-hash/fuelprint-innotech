package fuelprint.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.store.RecordStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CommandControllerTest extends CoreSpringTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RecordStore store;
    @Autowired JdbcTemplate jdbc;

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static String noteCommand(String commandId, String noteId, String extraTopLevel) {
        return "{\"schema_version\":\"fp/1\",\"command_id\":\"" + commandId + "\",\"command_type\":\"test_auto_note\","
                + "\"run_id\":null,\"expected_revision\":null," + extraTopLevel
                + "\"payload\":{\"note_id\":\"" + noteId + "\",\"text\":\"over http\"}}";
    }

    JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString();
        return mapper.readTree(body);
    }

    static MockHttpServletRequestBuilder postJson(String json) {
        return post("/api/commands").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void aSignedInStationGetsAnAppliedReceiptWithTheContractFields() throws Exception {
        String commandId = uuid();
        String noteId = uuid();
        JsonNode receipt = send(postJson(noteCommand(commandId, noteId, ""))
                .header(TestHeaderSessionResolver.ACTOR, "analyst@laptop-1")
                .header(TestHeaderSessionResolver.ROLES, "analyst"), 200);

        assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");
        assertThat(receipt.path("command_id").asText()).isEqualTo(commandId);
        assertThat(receipt.path("actor").asText()).isEqualTo("analyst@laptop-1");
        assertThat(receipt.path("automatic").asBoolean()).isFalse();
        assertThat(receipt.has("reason_code")).isTrue();
        assertThat(receipt.path("record_refs").get(0).path("type").asText()).isEqualTo("test_note");
        assertThat(store.get("test_note", noteId)).isPresent();
    }

    // 15
    @Test
    void noRequestCanBecomeTheAutomationActor() throws Exception {
        String extra = "\"actor\":\"automation\",\"automatic\":true,\"session\":{\"actor\":\"automation\"},";
        MockHttpServletRequestBuilder[] attempts = {
                // claims in the body only
                postJson(noteCommand(uuid(), uuid(), extra)),
                // claims in made-up headers
                postJson(noteCommand(uuid(), uuid(), extra)).header("X-Actor", "automation")
                        .header("X-Role", "automation").header("X-Automatic", "true"),
                // claims through the session resolver itself: reserved actor label
                postJson(noteCommand(uuid(), uuid(), extra)).header(TestHeaderSessionResolver.ACTOR, "automation")
                        .header(TestHeaderSessionResolver.ROLES, "analyst"),
                // ... or the automation role
                postJson(noteCommand(uuid(), uuid(), extra)).header(TestHeaderSessionResolver.ACTOR, "mallory")
                        .header(TestHeaderSessionResolver.ROLES, "automation"),
                postJson(noteCommand(uuid(), uuid(), extra)).header(TestHeaderSessionResolver.ACTOR, " Automation ")
                        .header(TestHeaderSessionResolver.ROLES, "analyst,automation"),
                // a cookie claiming it
                postJson(noteCommand(uuid(), uuid(), extra)).cookie(new jakarta.servlet.http.Cookie("fp_session", "automation")),
        };
        for (MockHttpServletRequestBuilder attempt : attempts) {
            JsonNode receipt = send(attempt, 200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commands WHERE command_id = ?", Integer.class,
                    receipt.path("command_id").asText())).as("anonymous requests store nothing").isZero();
            assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");
            assertThat(receipt.path("reason_code").asText()).isEqualTo("NOT_ALLOWED");
            assertThat(receipt.path("actor").asText()).isEqualTo("anonymous");
            assertThat(receipt.path("automatic").asBoolean()).isFalse();
        }
        Integer automaticRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commands WHERE automatic = 1 AND command_type = 'test_auto_note' AND actor <> 'automation'",
                Integer.class);
        assertThat(automaticRows).isZero();
    }

    @Test
    void theSameCommandOverHttpTwiceReturnsTheSameReceipt() throws Exception {
        String json = noteCommand(uuid(), uuid(), "");
        JsonNode first = send(postJson(json).header(TestHeaderSessionResolver.ACTOR, "analyst@laptop-1")
                .header(TestHeaderSessionResolver.ROLES, "analyst"), 200);
        JsonNode second = send(postJson(json).header(TestHeaderSessionResolver.ACTOR, "analyst@laptop-1")
                .header(TestHeaderSessionResolver.ROLES, "analyst"), 200);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void unreadableBodiesGetA400InvalidReceipt() throws Exception {
        String[] bodies = {
                "", "not json", "[1,2]",
                "{\"command_id\":\"" + uuid() + "\",\"command_id\":\"" + uuid() + "\"}",
                "{\"schema_version\":\"fp/1\",\"command_id\":\"" + uuid() + "\",\"command_type\":\"test_auto_note\","
                        + "\"expected_revision\":\"seven\",\"payload\":{}}",
                "{\"schema_version\":\"fp/1\",\"command_id\":\"" + uuid() + "\",\"command_type\":\"test_auto_note\","
                        + "\"payload\":[]}",
                "{\"schema_version\":\"fp/1\",\"command_id\":\"" + uuid() + "\",\"command_type\":\"x\","
                        + "\"payload\":{\"big\":\"" + "a".repeat(CommandController.MAX_BODY_BYTES) + "\"}}",
        };
        for (String body : bodies) {
            JsonNode receipt = send(postJson(body), 400);
            assertThat(receipt.path("status").asText()).isEqualTo("REJECTED");
            assertThat(receipt.path("reason_code").asText()).isEqualTo("INVALID");
        }
    }

    @Test
    void aServerFailureGetsA500AndSavesNothing() throws Exception {
        String commandId = uuid();
        String json = "{\"schema_version\":\"fp/1\",\"command_id\":\"" + commandId + "\",\"command_type\":\"test_throws\","
                + "\"payload\":{}}";
        JsonNode receipt = send(postJson(json).header(TestHeaderSessionResolver.ACTOR, "analyst@laptop-1")
                .header(TestHeaderSessionResolver.ROLES, "analyst"), 500);
        assertThat(receipt.path("reason_code").asText()).isEqualTo("SERVER_ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commands WHERE command_id = ?", Integer.class, commandId))
                .isZero();
    }
}
