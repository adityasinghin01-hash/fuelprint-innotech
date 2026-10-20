package fuelprint.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A number written in a command keeps every digit through the HTTP reader, the saved record and a later
 * fresh read, and the request digest tells two requests that differ only in far digits apart.
 */
class NumberPrecisionTest extends CoreSpringTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RecordStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired CookieSessionService sessions;

    static String uuid() { return UUID.randomUUID().toString(); }

    Cookie analyst() { return new Cookie(CookieSessionService.COOKIE, sessions.createStation("lab-x", Set.of("analyst")).sessionId()); }

    String body(String commandId, String id, String valuesJson) {
        return "{\"schema_version\":\"fp/1\",\"command_id\":\"" + commandId + "\",\"command_type\":\"test_number_echo\","
                + "\"run_id\":null,\"expected_revision\":null,\"payload\":{\"id\":\"" + id + "\",\"values\":" + valuesJson + "}}";
    }

    JsonNode send(String json, Cookie who) throws Exception {
        String text = mvc.perform(post("/api/commands").cookie(who).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(text);
    }

    @Test
    void literalDecimalsAndHugeIntegersSurviveHttpSaveAndAFreshRead() throws Exception {
        String id = uuid();
        String huge = "1" + "0".repeat(312);
        String values = "{\"a\":20.0000000000000001,\"b\":5e-324,\"c\":" + huge + ",\"d\":2010.0,\"e\":0.1,\"f\":-0.0000000000000000001}";
        JsonNode receipt = send(body(uuid(), id, values), analyst());
        assertThat(receipt.path("status").asText()).as(receipt.toString()).isEqualTo("APPLIED");

        JsonNode read = store.get("test_numbers", id).orElseThrow().body().path("values");
        assertThat(read.path("a").decimalValue()).isEqualByComparingTo(new BigDecimal("20.0000000000000001"));
        assertThat(read.path("a").decimalValue().toPlainString()).isEqualTo("20.0000000000000001");
        assertThat(read.path("b").decimalValue()).isEqualByComparingTo(new BigDecimal("5e-324"));
        assertThat(read.path("c").bigIntegerValue()).isEqualTo(new BigInteger(huge));
        assertThat(read.path("d").decimalValue().toPlainString()).isEqualTo("2010.0");
        assertThat(read.path("d").isDouble()).as("ordinary numbers are still read as doubles").isTrue();
        assertThat(read.path("e").isDouble()).isTrue();
        assertThat(read.path("a").isBigDecimal()).as("a number a double would round keeps every digit").isTrue();
        assertThat(read.path("b").isBigDecimal()).as("a number a double would flush to zero keeps every digit").isTrue();
        assertThat(read.path("e").decimalValue().toPlainString()).isEqualTo("0.1");
        assertThat(read.path("f").decimalValue()).isEqualByComparingTo(new BigDecimal("-0.0000000000000000001"));
        String stored = jdbc.queryForObject("SELECT body FROM records WHERE type = 'test_numbers' AND id = ?", String.class, id);
        assertThat(stored).contains("20.0000000000000001").contains(huge);
    }

    @Test
    void aRecordSavedFromJavaDecimalsReloadsWithTheSameDigits() {
        String id = uuid();
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        ObjectNode values = fields.putObject("values");
        values.put("a", new BigDecimal("20.0000000000000001"));
        values.put("b", new BigDecimal("5e-324"));
        values.put("c", new BigInteger("1" + "0".repeat(312)));
        store.save(RecordChange.create("test_numbers", id, null, fields), "tester", null);
        JsonNode read = store.get("test_numbers", id).orElseThrow().body().path("values");
        assertThat(read.path("a").decimalValue()).isEqualByComparingTo(new BigDecimal("20.0000000000000001"));
        assertThat(read.path("b").decimalValue()).isEqualByComparingTo(new BigDecimal("5e-324"));
        assertThat(read.path("c").bigIntegerValue()).isEqualTo(new BigInteger("1" + "0".repeat(312)));
    }

    @Test
    void ordinaryDoublesStillReadAsTheSameNumbers() {
        String id = uuid();
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.putObject("values").put("x", 9.523809523809524).put("y", 2000.0).put("n", 42);
        store.save(RecordChange.create("test_numbers", id, null, fields), "tester", null);
        JsonNode read = store.get("test_numbers", id).orElseThrow().body().path("values");
        assertThat(read.path("x").asDouble()).isEqualTo(9.523809523809524);
        assertThat(read.path("y").asDouble()).isEqualTo(2000.0);
        assertThat(read.path("n").asLong()).isEqualTo(42);
        assertThat(read.path("n").isIntegralNumber()).isTrue();
        assertThat(read.path("x").isDouble() && read.path("y").isDouble()).as("legacy double nodes, so existing equality and hashes are unchanged").isTrue();
    }

    @Test
    void ordinaryDecimalBodiesAreReadExactlyAsBefore() throws Exception {
        String id = uuid();
        JsonNode receipt = send(body(uuid(), id, "{\"x\":2.0,\"y\":9.523809523809524,\"z\":1.0E-5,\"w\":123456789.125,\"n\":7}"), analyst());
        assertThat(receipt.path("status").asText()).as(receipt.toString()).isEqualTo("APPLIED");
        JsonNode read = store.get("test_numbers", id).orElseThrow().body().path("values");
        assertThat(read.isObject()).isTrue();
        for (String name : new String[] {"x", "y", "z", "w"}) assertThat(read.path(name).isDouble()).as(name).isTrue();
        assertThat(read.path("x")).isEqualTo(com.fasterxml.jackson.databind.node.DoubleNode.valueOf(2.0));
        assertThat(read.path("z")).isEqualTo(com.fasterxml.jackson.databind.node.DoubleNode.valueOf(1.0E-5));
        assertThat(read.path("n").isInt()).isTrue();
    }

    @Test
    void twoRequestsDifferingOnlyBeyondBinary64AreDifferentRequests() throws Exception {
        String commandId = uuid(), id = uuid();
        Cookie who = analyst();
        JsonNode first = send(body(commandId, id, "{\"a\":20.0000000000000001}"), who);
        assertThat(first.path("status").asText()).as(first.toString()).isEqualTo("APPLIED");
        JsonNode second = send(body(commandId, id, "{\"a\":20.0000000000000002}"), who);
        assertThat(second.path("status").asText()).as("same id, different number: not a replay").isEqualTo("REJECTED");
        assertThat(second.path("reason_code").asText()).isEqualTo("INVALID");
        JsonNode same = send(body(commandId, id, "{\"a\":20.0000000000000001}"), who);
        assertThat(same).as("the identical body still returns the first receipt").isEqualTo(first);
    }

    @Test
    void strictDuplicateKeySizeAndShapeGuardsStillApply() throws Exception {
        for (String bad : new String[] {
                "{\"command_id\":\"" + uuid() + "\",\"payload\":{\"a\":1.5,\"a\":2.5}}",
                "{\"command_id\":\"" + uuid() + "\",\"expected_revision\":1.5,\"payload\":{}}",
                "{\"command_id\":\"" + uuid() + "\",\"payload\":[1.5]}" }) {
            mvc.perform(post("/api/commands").cookie(analyst()).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        String big = "{\"command_id\":\"" + uuid() + "\",\"payload\":{\"x\":\"" + "a".repeat(CommandController.MAX_BODY_BYTES) + "\"}}";
        mvc.perform(post("/api/commands").cookie(analyst()).contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isBadRequest());
    }
}
