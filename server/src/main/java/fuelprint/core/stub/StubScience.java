package fuelprint.core.stub;

import java.util.List;

import fuelprint.api.Analysis;
import fuelprint.api.AnalysisInput;
import fuelprint.api.CaptureInput;
import fuelprint.api.CaptureRules;
import fuelprint.api.ChannelStatus;
import fuelprint.api.ChannelVerdict;
import fuelprint.api.PlanContext;
import fuelprint.api.Planner;
import fuelprint.api.Proposal;
import fuelprint.api.Ranker;
import fuelprint.api.Window;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Stub Ranker, Planner and CaptureRules: the fixture analysis, the fixture proposal and VALID channels. */
@Component
@Profile("stub")
public class StubScience implements Ranker, Planner, CaptureRules {

    @Override
    public Analysis analyze(AnalysisInput input) {
        Analysis fixture = Fixtures.as("analysis", Analysis.class);
        List<String> windowIds = input.windows().isEmpty() ? fixture.inputWindowIds()
                : input.windows().stream().map(Window::id).toList();
        return Analysis.physics(windowIds, fixture.outcome(), fixture.qualification(), fixture.candidates(),
                fixture.exclusions(), fixture.limitations(), fixture.explanationText(), "FP-COMPAT-3-stub");
    }

    @Override
    public Proposal plan(Analysis analysis, PlanContext context) {
        Proposal fixture = Fixtures.as("proposal", Proposal.class);
        return new Proposal(context.analysisId(), fixture.actions(), fixture.result(), fixture.bestActionId(),
                fixture.cheaperActionId());
    }

    @Override
    public ChannelVerdict judge(CaptureInput input) {
        double volume = input.channel().startsWith("SO2") ? 0.25 : 0.15;
        return new ChannelVerdict(input.channel(), 30.0, volume, ChannelStatus.VALID, List.of());
    }
}
