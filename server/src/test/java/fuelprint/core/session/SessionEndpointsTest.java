package fuelprint.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.core.CoreSpringTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SessionEndpointsTest extends CoreSpringTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired StationDirectory stations;

    MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    MockHttpServletRequestBuilder login(String station, String pin, String ip) {
        return post("/api/session/station").contentType(MediaType.APPLICATION_JSON)
                .content("{\"station_id\":\"" + station + "\",\"pin\":\"" + pin + "\"}")
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
    }

    @Test
    void theFiveExampleStationsHaveTheTeamPlanRoles() throws Exception {
        assertThat(stations.usingExample()).isTrue();
        JsonNode list = json(call(get("/api/session/stations")));
        assertThat(list).hasSize(5);
        assertThat(list.toString()).doesNotContain("pin").doesNotContain("1111");
        assertThat(list.get(0).path("roles").toString()).contains("analyst", "supervisor", "presenter");
        assertThat(list.get(1).path("roles").toString()).contains("field_operator", "qc_reviewer", "presenter");
        assertThat(list.get(2).path("roles").toString()).contains("case_reviewer", "lab_demo");
        assertThat(list.get(3).path("roles").toString()).contains("case_reviewer", "inspector_demo", "exporter");
        assertThat(list.get(4).path("roles").toString()).contains("board", "presenter", "analyst");
    }

    @Test
    void aStationLogsInWithItsPinAndGetsAnHttpOnlyLaxCookie() throws Exception {
        MockHttpServletResponse response = call(login("laptop-1", "1111", "10.0.0.1"));
        assertThat(response.getStatus()).isEqualTo(200);
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("fp_session=").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        JsonNode body = json(response);
        assertThat(body.path("station_id").asText()).isEqualTo("laptop-1");
        assertThat(body.toString()).doesNotContain(setCookie.substring(11, 40)); // the secret is never in a body

        Cookie cookie = new Cookie("fp_session", setCookie.substring(11, setCookie.indexOf(';')));
        JsonNode me = json(call(get("/api/session").cookie(cookie)));
        assertThat(me.path("signed_in").asBoolean()).isTrue();
        assertThat(me.path("roles").toString()).contains("supervisor");

        call(delete("/api/session").cookie(cookie));
        assertThat(json(call(get("/api/session").cookie(cookie))).path("signed_in").asBoolean()).isFalse();
    }

    @Test
    void aWrongPinIsRefusedAndTriesAreRateLimited() throws Exception {
        assertThat(call(login("laptop-1", "0000", "10.0.0.2")).getStatus()).isEqualTo(401);
        int last = 0;
        for (int i = 0; i < SessionController.STATION_TRIES_PER_MINUTE; i++) {
            last = call(login("laptop-1", "0000", "10.0.0.2")).getStatus();
        }
        assertThat(last).isEqualTo(429);
        assertThat(call(login("laptop-1", "1111", "10.0.0.3")).getStatus()).as("other IPs unaffected").isEqualTo(200);
    }

    @Test
    void guestsGetOnlyTheGuestRoleAndAreLimitedToTenAMinutePerIp() throws Exception {
        for (int i = 0; i < SessionController.GUEST_PER_MINUTE; i++) {
            MockHttpServletResponse response = call(post("/api/session/guest").with(r -> {
                r.setRemoteAddr("10.0.9.9");
                return r;
            }));
            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode body = json(response);
            assertThat(body.path("roles").toString()).isEqualTo("[\"guest\"]");
            assertThat(body.path("actor_label").asText()).startsWith("guest-");
        }
        MockHttpServletResponse limited = call(post("/api/session/guest").with(r -> {
            r.setRemoteAddr("10.0.9.9");
            return r;
        }));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(json(limited).path("reason_code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void aForeignOriginCannotLogIn() throws Exception {
        MockHttpServletResponse response = call(login("laptop-1", "1111", "10.0.0.4")
                .header("Origin", "http://evil.example").header("Host", "localhost:8080"));
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
