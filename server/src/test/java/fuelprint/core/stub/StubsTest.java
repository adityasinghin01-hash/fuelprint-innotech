package fuelprint.core.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import fuelprint.api.AlertAreas;
import fuelprint.api.Analysis;
import fuelprint.api.AnalysisInput;
import fuelprint.api.Candidate;
import fuelprint.api.GatewayMode;
import fuelprint.api.LearnedRanker;
import fuelprint.api.LearnedStatus;
import fuelprint.api.NormalAirModel;
import fuelprint.api.PlanContext;
import fuelprint.api.Planner;
import fuelprint.api.Ranker;
import fuelprint.api.SmsGateway;
import fuelprint.api.WindNow;
import fuelprint.api.WordingPurpose;
import fuelprint.api.WordingRequest;
import fuelprint.api.WordingService;
import fuelprint.api.WordingSource;
import fuelprint.core.CoreSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The stub profile gives a fixture-shaped answer for every interface other packages implement. */
class StubsTest extends CoreSpringTest {

    @Autowired Ranker ranker;
    @Autowired Planner planner;
    @Autowired LearnedRanker learned;
    @Autowired NormalAirModel normalAir;
    @Autowired WordingService wording;
    @Autowired AlertAreas areas;
    @Autowired SmsGateway sms;

    @Test
    void scienceAndAiStubsAreFixtureShapedAndKeepTheRules() {
        AnalysisInput input = new AnalysisInput("r", "i", 412, List.of(), new WindNow(270, 3));
        Analysis analysis = ranker.analyze(input);
        assertThat(analysis.candidates()).filteredOn(Candidate::isUnknown).hasSize(1);
        assertThat(analysis.learnedOrder()).isEmpty();
        var order = learned.order(input, analysis);
        assertThat(order.learnedStatus()).isEqualTo(LearnedStatus.MODEL_MISSING);
        assertThat(analysis.withLearned(order).outcome()).isEqualTo(analysis.outcome());
        assertThat(planner.plan(analysis, new PlanContext("r", "a-1", 412, new WindNow(270, 3), List.of())).analysisId())
                .isEqualTo("a-1");
        assertThat(normalAir.expected("N2", "SO2_ppb", 1, new WindNow(270, 3))).isEmpty();
        var text = wording.write(new WordingRequest(null, WordingPurpose.SMS_SMOKE_WARNING, "x"));
        assertThat(text.source()).isEqualTo(WordingSource.TEMPLATE);
        assertThat(text.text()).startsWith("FuelPrint DEMO (simulated):").hasSizeLessThanOrEqualTo(160);
    }

    @Test
    void alertsStubUsesTheRealDownwindRuleAndNeverSends() {
        assertThat(areas.downwindOf("N2", new WindNow(270, 3))).containsExactly("AREA-E");
        assertThat(areas.downwindOf("N2", new WindNow(90, 3))).containsExactly("AREA-W");
        assertThat(areas.downwindOf("N9", new WindNow(270, 3))).isEmpty();
        assertThat(sms.send("+919812345678", "test").gatewayMode()).isEqualTo(GatewayMode.LOG_ONLY);
    }
}
