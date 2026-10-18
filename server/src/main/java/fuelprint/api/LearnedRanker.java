package fuelprint.api;

/**
 * AI (AI-2): orders the surviving registered explanations. Gets only public data. It can never change
 * the outcome, a candidate status or the UNKNOWN row; {@link Analysis#withLearned} enforces that.
 */
public interface LearnedRanker {

    /** The learned order, its status, the model version id and the history counts. */
    LearnedOrder order(AnalysisInput input, Analysis physics);
}
