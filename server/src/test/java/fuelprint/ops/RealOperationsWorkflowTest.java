package fuelprint.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.LearnedRanker;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.RecordStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Actual public Ranker and Planner, with no learned model and no observations. No performance claim. */
@SpringBootTest(properties={"fp.loop.auto=false","fp.field.safety-loop=false","fp.ai.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("real")
class RealOperationsWorkflowTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-real-ops-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r){r.add("fp.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RecordStore store;
    @Autowired CommandBus bus;
    @Autowired CookieSessionService sessions;
    @Autowired ObjectProvider<LearnedRanker> learned;
    @Test void independentSurveyRunsActualScienceAndKeepsEmptyDataInvalid() throws Exception {
        var presenter=new Cookie(CookieSessionService.COOKIE,sessions.createStation("ops-presenter",Set.of("presenter")).sessionId());
        var analyst=new Cookie(CookieSessionService.COOKIE,sessions.createStation("ops-analyst",Set.of("analyst")).sessionId());
        var create=new Command("fp/1",UUID.randomUUID().toString(),"reset_run",null,null,json.createObjectNode().put("preset_id","SC3-ABOVE-THE-BOXES").put("mode","TEACHING"));
        mvc.perform(post("/api/commands").cookie(presenter).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(create))).andExpect(status().isOk());
        for(int i=0;i<5;i++)bus.awaitIdle();
        var run=store.listAll("run").stream().filter(r->r.body().path("state").asText().equals("RUNNING")).findFirst().orElseThrow().id();
        var c=new Command("fp/1",UUID.randomUUID().toString(),"schedule_survey",run,null,json.createObjectNode().put("action_id","SURVEY-DRONE-280-140-45").put("reason","Independent teaching survey"));
        var response=mvc.perform(post("/api/commands").cookie(analyst).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).path("status").asText()).isEqualTo("APPLIED");
        for(int i=0;i<6;i++)bus.awaitIdle();
        assertThat(learned.getIfAvailable()).isNull();
        assertThat(store.list("analysis",run)).hasSize(1);
        var a=store.list("analysis",run).get(0).body();
        assertThat(a.path("outcome").asText()).isEqualTo("INVALID");
        assertThat(a.path("learned_status").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(a.path("algorithm_version").asText()).startsWith("FP-COMPAT-");
        assertThat(a.path("candidates").findValuesAsText("explanation_id")).contains("UNKNOWN");
        assertThat(store.list("proposal",run)).hasSize(1);
        assertThat(store.list("investigation",run).get(0).body().path("warned_area_ids")).isEmpty();
        System.out.println("OPS_REAL_RECEIPT "+response.getContentAsString());
        System.out.println("OPS_REAL_ANALYSIS "+a);
        System.out.println("OPS_REAL_PROPOSAL "+store.list("proposal",run).get(0).body());
    }
}
