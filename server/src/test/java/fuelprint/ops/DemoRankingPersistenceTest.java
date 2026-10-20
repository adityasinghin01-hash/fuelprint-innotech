package fuelprint.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.*;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Saves ordered presentation while passing unmodified physics facts to the planner. */
@SpringBootTest(properties={"fp.loop.auto=false","fp.field.safety-loop=false","fp.ai.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("real") @Import(DemoRankingPersistenceTest.Config.class)
class DemoRankingPersistenceTest {
    static final Path DATA=CoreSpringTest.TempFolders.create("fp-missing-learned-");
    @DynamicPropertySource static void data(DynamicPropertyRegistry r){r.add("fp.data-dir",DATA::toString);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired RecordStore store;
    @Autowired CommandBus bus; @Autowired CookieSessionService sessions; @Autowired ObjectProvider<LearnedRanker> learned;
    @Test void absentModelPersistsPhysicsWithModelMissingAndNoInventedMetadata() throws Exception {
        var presenter=new Cookie(CookieSessionService.COOKIE,sessions.createStation("presenter",Set.of("presenter")).sessionId());
        var analyst=new Cookie(CookieSessionService.COOKIE,sessions.createStation("analyst",Set.of("analyst")).sessionId());
        var create=new Command("fp/1",UUID.randomUUID().toString(),"reset_run",null,null,json.createObjectNode().put("preset_id","SC1-NIGHT-PLUME").put("mode","TEACHING"));
        mvc.perform(post("/api/commands").cookie(presenter).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(create))).andExpect(status().isOk());
        for(int i=0;i<5;i++)bus.awaitIdle();
        var run=store.listAll("run").stream().filter(r->r.body().path("state").asText().equals("RUNNING")).findFirst().orElseThrow().id();
        var c=new Command("fp/1",UUID.randomUUID().toString(),"schedule_survey",run,null,json.createObjectNode().put("action_id","SURVEY-DRONE-0-100-35").put("reason","Independent survey"));
        var response=mvc.perform(post("/api/commands").cookie(analyst).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(c))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).path("status").asText()).isEqualTo("APPLIED");
        for(int i=0;i<6;i++)bus.awaitIdle();
        assertThat(learned.getIfAvailable()).isNull();
        assertThat(store.list("analysis",run)).hasSize(1);
        var a=store.list("analysis",run).get(0).body();
        assertThat(a.path("outcome").asText()).isEqualTo("AMBIGUOUS_OR_OPTIONAL_MIXTURE");
        assertThat(a.path("learned_status").asText()).isEqualTo("MODEL_MISSING");
        assertThat(a.path("history_counts").isNull()).isTrue();
        assertThat(a.path("learned_model_version_id").isNull()).isTrue();
        assertThat(a.path("learned_order")).isEmpty();
        assertThat(a.path("candidates").findValuesAsText("explanation_id")).containsExactly("S01", "S02", "UNKNOWN", "S03");
        assertThat(Config.plannerInput.candidates()).containsExactlyElementsOf(rows());
        assertThat(a.path("candidates").get(3).path("status").asText()).isEqualTo("REJECTED");
    }
    static List<Candidate> rows() { return List.of(
        new Candidate("S02",List.of("S02"),2.,CandidateStatus.SURVIVES,null),
        new Candidate("UNKNOWN",List.of(),null,CandidateStatus.SURVIVES,"Unknown remains possible"),
        new Candidate("S03",List.of("S03"),.01,CandidateStatus.REJECTED,"Rejected by physics"),
        new Candidate("S01",List.of("S01"),1.,CandidateStatus.SURVIVES,null)); }
    @TestConfiguration static class Config {
        static volatile Analysis plannerInput;
        @Bean @Primary Ranker rankerFixture(){return input->Analysis.physics(List.of(),Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE,Qualification.SYNTHETIC_SCOPE_ONLY,rows(),List.of(),List.of("Fixture only"),"Fixture ambiguity","FIXTURE-1");}
        @Bean @Primary Planner plannerFixture(){return (a,c)->{plannerInput=a;return new Proposal(c.analysisId(),List.of(),ProposalResult.NO_USEFUL_MEASUREMENT,null,null);};}
    }
}
