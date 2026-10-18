package fuelprint.api;

/** Science (AI-3): picks the most useful next measurement. */
public interface Planner {

    /** A proposal of possible actions for this analysis. */
    Proposal plan(Analysis analysis, PlanContext context);
}
