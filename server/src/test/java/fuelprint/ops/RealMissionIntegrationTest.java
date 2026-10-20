package fuelprint.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.*;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.*;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

/** Actual Science, Clock, Settings and Field: no seeded mission, observations or recommended benefit. */
@SpringBootTest(properties={"fp.loop.auto=false","fp.field.safety-loop=false"})
@AutoConfigureMockMvc @ActiveProfiles("real")
class RealMissionIntegrationTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-real-mission-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r){r.add("fp.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordStore store;
    @Autowired CommandBus bus;
    @Autowired CookieSessionService sessions;
    @Autowired ClockControl clock;
    @Autowired VehicleSource vehicles;
    String run;
    Cookie cookie(String role){return new Cookie(CookieSessionService.COOKIE,sessions.createStation(UUID.randomUUID().toString(),Set.of(role)).sessionId());}
    ObjectNode obj(){return json.createObjectNode();}
    void settle(){for(int i=0;i<7;i++)bus.awaitIdle();}
    JsonNode send(String type,ObjectNode payload,Long revision,Cookie cookie) throws Exception {
        var command=new Command("fp/1",UUID.randomUUID().toString(),type,run,revision,payload);
        var response=mvc.perform(post("/api/commands").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(command))).andExpect(status().isOk()).andReturn().getResponse();
        settle();JsonNode receipt=json.readTree(response.getContentAsString());System.out.println("MISSION_REAL_RECEIPT "+receipt);return receipt;
    }
    @ParameterizedTest @CsvSource({
        "SC1-NIGHT-PLUME,SURVEY-DRONE-0-100-35,0,100,35",
        "SC1-NIGHT-PLUME,SURVEY-DRONE-280-140-45,280,140,45",
        "SC2-BLOCKED-INLET,SURVEY-DRONE-0-100-35,0,100,35",
        "SC2-BLOCKED-INLET,SURVEY-DRONE-280-140-45,280,140,45",
        "SC3-ABOVE-THE-BOXES,SURVEY-DRONE-0-100-35,0,100,35",
        "SC3-ABOVE-THE-BOXES,SURVEY-DRONE-280-140-45,280,140,45"})
    void independentSurveyGetsHumanApprovalAndRealFieldLeaseInEveryPreset(String preset,String action,double east,double north,double up) throws Exception {
        var presenter=cookie("presenter");var analyst=cookie("analyst");var supervisor=cookie("supervisor");var operator=cookie("field_operator");
        assertThat(send("reset_run",obj().put("preset_id",preset).put("mode","TEACHING"),null,presenter).path("status").asText()).isEqualTo("APPLIED");
        run=store.listAll("run").stream().filter(r->r.body().path("state").asText().equals("RUNNING")).findFirst().orElseThrow().id();
        assertThat(send("schedule_survey",obj().put("action_id",action).put("reason","Independent teaching survey; no claimed planner benefit"),null,analyst).path("status").asText()).isEqualTo("APPLIED");
        assertThat(store.list("analysis",run)).hasSize(1);assertThat(store.list("analysis",run).get(0).body().path("outcome").asText()).isEqualTo("INVALID");
        assertThat(store.list("proposal",run).get(0).body().path("result").asText()).isEqualTo("NO_USEFUL_MEASUREMENT");
        assertThat(store.list("mission",run)).isEmpty();
        var survey=store.list("survey",run).get(0);
        assertThat(send("propose_mission",obj().put("survey_id",survey.id()).put("action_id",action),survey.revision(),analyst).path("status").asText()).isEqualTo("APPLIED");
        var mission=store.list("mission",run).get(0);var body=mission.body();
        assertThat(body.path("target").path("east").asDouble()).isEqualTo(east);assertThat(body.path("target").path("north").asDouble()).isEqualTo(north);assertThat(body.path("target").path("up").asDouble()).isEqualTo(up);
        assertThat(body.path("expires_s").asDouble()).isEqualTo(clock.simTimeS()+300);
        var approval=obj().put("mission_id",mission.id()).put("dependency_hash",body.path("dependency_hash").asText());
        assertThat(send("approve_mission",approval,mission.revision(),supervisor).path("status").asText()).isEqualTo("APPLIED");
        assertThat(send("acquire_lease",obj().put("mission_id",mission.id()).put("vehicle_id",body.path("vehicle_id").asText()),null,operator).path("status").asText()).isEqualTo("APPLIED");
        assertThat(store.get("mission",mission.id()).orElseThrow().body().path("state").asText()).isEqualTo("LEASED");
        assertThat(vehicles.vehicles().stream().filter(v->v.vehicleId().equals(body.path("vehicle_id").asText())).findFirst().orElseThrow().pilot()).isEqualTo(Pilot.OPERATOR);
        System.out.println("MISSION_REAL_APPROVED "+store.get("mission",mission.id(),2).orElseThrow().body());
        // Reset retains genuine history and Field's established interruption/revocation path.
        assertThat(send("reset_run",obj().put("preset_id",preset).put("mode","TEACHING"),null,presenter).path("status").asText()).isEqualTo("APPLIED");
        assertThat(store.get("mission",mission.id()).orElseThrow().body().path("state").asText()).isEqualTo("INTERRUPTED");
        assertThat(store.list("lease",run)).allSatisfy(l->assertThat(l.body().path("state").asText()).isEqualTo("REVOKED"));
        assertThat(send("approve_mission",approval,null,supervisor).path("reason_code").asText()).isEqualTo("WRONG_STATE");
    }
}
