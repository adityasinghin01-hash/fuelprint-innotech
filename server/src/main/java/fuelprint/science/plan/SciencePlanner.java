package fuelprint.science.plan;

import fuelprint.api.Analysis;
import fuelprint.api.PlanContext;
import fuelprint.api.Planner;
import fuelprint.api.Proposal;
import fuelprint.science.rank.ScienceRanker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The real {@link Planner} bean (FP-PLAN-INTERVAL-1). Uses the immutable fit snapshot the ranker kept for the
 * analysis. A proposal whose {@code result} is null is pending (the 2 s wall budget ran out): it carries no best or
 * cheaper action and must not be shown as a recommendation.
 */
@Component
@Profile("!stub")
public class SciencePlanner implements Planner {

    private final ScienceRanker ranker;
    private final IntervalPlanner planner;

    public SciencePlanner(ScienceRanker ranker) {
        this.ranker = ranker;
        this.planner = new IntervalPlanner(ranker.engine().engine(), System::nanoTime,
                IntervalPlanner.DEFAULT_BUDGET_NANOS);
    }

    @Override
    public Proposal plan(Analysis analysis, PlanContext context) {
        return detailed(analysis, context).proposal();
    }

    public IntervalPlanner.Result detailed(Analysis analysis, PlanContext context) {
        return planner.plan(analysis, context, ranker.snapshotFor(analysis));
    }
}
