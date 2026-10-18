package fuelprint.api;

import java.util.List;

/**
 * Public inputs to one analysis, for {@link Ranker} and {@link LearnedRanker}. Never contains hidden truth.
 *
 * @param runId           the run
 * @param investigationId the investigation being analysed
 * @param simTimeS        simulation time of the analysis
 * @param windows         the windows the analysis may use
 * @param wind            the wind now
 */
public record AnalysisInput(String runId, String investigationId, double simTimeS, List<Window> windows,
                            WindNow wind) {

    public AnalysisInput {
        windows = List.copyOf(windows);
    }
}
