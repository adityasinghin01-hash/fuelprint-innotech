package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Session;
import fuelprint.core.store.StoredRecord;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** F4 pairing: code and phrase, 120 s, join, confirm, queue. */
class PairingTest extends FieldSpringTest {

    @Autowired MockMvc mvc;

    StoredRecord createPairing(String mission) {
        Receipt receipt = send(OPERATOR, "create_pairing", missionVehicle(mission));
        assertThat(receipt.applied()).as(receipt.reasonText()).isTrue();
        return record("pairing", receipt.recordRefs().get(0).id());
    }

    MvcResult join(String body, String address) throws Exception {
        return mvc.perform(post("/api/session/join").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })).andReturn();
    }

    @Test
    void createPairingMakesASixCharacterCodeAndTwoWordPhraseThatLive120Seconds() {
        String mission = approvedMission();
        StoredRecord pairing = createPairing(mission);
        JsonNode body = pairing.body();
        assertThat(body.path("code").asText()).matches("[A-HJKMNP-Z2-9]{6}");
        assertThat(body.path("phrase").asText()).matches("[a-z]+ [a-z]+");
        assertThat(body.path("state").asText()).isEqualTo("CREATED");
        assertThat(java.time.Instant.parse(body.path("expires_utc").asText()))
                .isEqualTo(time.now().plusSeconds(120));
        assertThat(body.path("mission_id").asText()).isEqualTo(mission);
    }

    @Test
    void onlyAFieldOperatorCreatesAPairingForAnApprovedMissionAndItsVehicle() {
        String mission = approvedMission();
        assertThat(send(SUPERVISOR, "create_pairing", missionVehicle(mission)).reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(send(sessions.createVisitor("visitor-p1"), "create_pairing", missionVehicle(mission)).reasonCode())
                .isEqualTo("NOT_ALLOWED");
        assertThat(send(OPERATOR, "create_pairing", payload().put("mission_id", mission)
                .put("vehicle_id", UUID.randomUUID().toString())).reasonCode()).isEqualTo("WRONG_VEHICLE");
        assertThat(send(OPERATOR, "create_pairing", missionVehicle(UUID.randomUUID().toString())).reasonCode())
                .isEqualTo("NOT_FOUND");
        assertThat(send(OPERATOR, "create_pairing", payload()).reasonCode()).isEqualTo("INVALID");
    }

    @Test
    void aPhoneJoinsWithTheCodeGetsAVisitorCookieAndTheOperatorConfirms() throws Exception {
        String mission = approvedMission();
        StoredRecord pairing = createPairing(mission);
        String code = pairing.body().path("code").asText();

        MvcResult joined = join("{\"code\": \"" + code.substring(0, 3).toLowerCase() + " - " + code.substring(3) + "\"}",
                "10.0.0.5");
        assertThat(joined.getResponse().getStatus()).as(joined.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode answer = JSON.readTree(joined.getResponse().getContentAsString());
        assertThat(answer.path("phrase").asText()).isEqualTo(pairing.body().path("phrase").asText());
        assertThat(answer.path("mission_id").asText()).isEqualTo(mission);
        assertThat(answer.path("visitor").asText()).startsWith("visitor-");
        assertThat(joined.getResponse().getContentAsString()).doesNotContain(code);
        String cookie = joined.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).startsWith("fp_session=").contains("HttpOnly");
        settle();
        assertThat(record("pairing", pairing.id()).body().path("state").asText()).isEqualTo("REDEEMED");
        assertThat(record("pairing", pairing.id()).body().path("redeemed_by").asText()).isEqualTo(answer.path("visitor").asText());
        assertThat(world.queue(mission)).as("not in the queue until confirmed").isEmpty();

        // The code is in no stored command payload.
        List<String> payloads = jdbc.queryForList("SELECT payload FROM commands WHERE command_type = 'redeem_pairing'", String.class);
        assertThat(payloads).isNotEmpty().allSatisfy(p -> assertThat(p).doesNotContain(code));

        // A second phone with the same code is refused.
        assertThat(join("{\"code\": \"" + code + "\"}", "10.0.0.6").getResponse().getStatus()).isEqualTo(404);

        Receipt confirmed = send(OPERATOR, "confirm_pairing", payload().put("pairing_id", pairing.id()));
        assertThat(confirmed.applied()).isTrue();
        assertThat(world.queue(mission)).hasSize(1);
        assertThat(world.queue(mission).get(0).visitor()).isEqualTo(answer.path("visitor").asText());

        String token = cookie.substring("fp_session=".length(), cookie.indexOf(';'));
        mvc.perform(get("/api/field/queue").cookie(new Cookie("fp_session", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.your_position").value(1))
                .andExpect(jsonPath("$.entries[0].visitor").value(answer.path("visitor").asText()))
                .andExpect(jsonPath("$.entries.length()").value(1));
        mvc.perform(get("/api/field/queue")).andExpect(status().isOk()).andExpect(jsonPath("$.your_position").doesNotExist());

        // The joined visitor can now take the lease with their cookie's session.
        Receipt lease = send(sessions.resolveToken(token).orElseThrow(), "acquire_lease", missionVehicle(mission));
        assertThat(lease.applied()).as(lease.reasonText()).isTrue();
    }

    @Test
    void confirmNeedsARedeemedCodeAndTheCodeExpiresAfter120Seconds() throws Exception {
        String mission = approvedMission();
        StoredRecord pairing = createPairing(mission);
        assertThat(send(OPERATOR, "confirm_pairing", payload().put("pairing_id", pairing.id())).reasonCode())
                .isEqualTo("NOT_REDEEMED");
        assertThat(send(SUPERVISOR, "confirm_pairing", payload().put("pairing_id", pairing.id())).reasonCode())
                .isEqualTo("NOT_ALLOWED");

        time.advanceMillis(119_000);
        world.safetyTick();
        settle();
        assertThat(automatic("expire_pairing")).isEmpty();
        time.advanceMillis(2_000);
        world.safetyTick();
        settle();
        assertThat(appliedAutomatic("expire_pairing")).hasSize(1);
        assertThat(record("pairing", pairing.id()).body().path("state").asText()).isEqualTo("EXPIRED");

        String code = pairing.body().path("code").asText();
        assertThat(join("{\"code\": \"" + code + "\"}", "10.0.0.7").getResponse().getStatus()).isEqualTo(404);
        assertThat(send(OPERATOR, "confirm_pairing", payload().put("pairing_id", pairing.id())).reasonCode())
                .isEqualTo("NOT_REDEEMED");
    }

    @Test
    void aRedeemedButUnconfirmedCodeAlsoExpires() throws Exception {
        String mission = approvedMission();
        StoredRecord pairing = createPairing(mission);
        assertThat(join("{\"code\": \"" + pairing.body().path("code").asText() + "\"}", "10.0.0.8").getResponse().getStatus())
                .isEqualTo(200);
        settle();
        time.advanceMillis(121_000);
        assertThat(send(OPERATOR, "confirm_pairing", payload().put("pairing_id", pairing.id())).reasonCode())
                .isEqualTo("EXPIRED");
        world.safetyTick();
        settle();
        assertThat(record("pairing", pairing.id()).body().path("state").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void joinRefusesBadCodesWrongOriginAndTooManyTries() throws Exception {
        String mission = approvedMission();
        createPairing(mission);
        assertThat(join("{\"code\": \"ZZZZZZ\"}", "10.0.1.1").getResponse().getStatus()).isEqualTo(404);
        assertThat(join("{\"code\": \"abc\"}", "10.0.1.1").getResponse().getStatus()).isEqualTo(400);
        assertThat(join("{}", "10.0.1.1").getResponse().getStatus()).isEqualTo(400);
        MvcResult evil = mvc.perform(post("/api/session/join").contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://evil.example").content("{\"code\": \"ZZZZZZ\"}")).andReturn();
        assertThat(evil.getResponse().getStatus()).isEqualTo(403);
        for (int i = 0; i < 10; i++) {
            join("{\"code\": \"ZZZZZZ\"}", "10.0.2.2");
        }
        assertThat(join("{\"code\": \"ZZZZZZ\"}", "10.0.2.2").getResponse().getStatus()).isEqualTo(429);
        assertThat(join("{\"code\": \"ZZZZZZ\"}", "10.0.2.3").getResponse().getStatus()).as("another address").isEqualTo(404);
    }

    @Test
    void aVisitorSessionCannotCreateOrConfirmPairings() {
        String mission = approvedMission();
        StoredRecord pairing = createPairing(mission);
        Session visitor = sessions.createVisitor("visitor-x7");
        assertThat(send(visitor, "confirm_pairing", payload().put("pairing_id", pairing.id())).reasonCode())
                .isEqualTo("NOT_ALLOWED");
        assertThat(send(visitor, "redeem_pairing", payload().put("pairing_id", pairing.id()).put("code", "WRONG1"))
                .reasonCode()).isEqualTo("CODE_NOT_VALID");
    }
}
