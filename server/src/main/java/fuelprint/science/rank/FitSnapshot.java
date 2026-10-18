package fuelprint.science.rank;

import fuelprint.api.WindNow;

import java.util.List;
import java.util.Set;

/**
 * Immutable fit snapshot behind one analysis, for the planner (FP-PLAN-INTERVAL-1). Holds, for each surviving
 * explanation, its admitted nuisance members (normalised SSE within the cutoff) with amplitudes and whitened
 * designs. Public-input only.
 */
public record FitSnapshot(List<String> inputWindowIds, WindNow wind, double cutoff, int entries, Set<Gas> gases,
                          List<Surviving> surviving) {

    public FitSnapshot {
        inputWindowIds = List.copyOf(inputWindowIds);
        gases = Set.copyOf(gases);
        surviving = List.copyOf(surviving);
    }

    /** A surviving explanation and the members whose fit is within the cutoff. */
    public record Surviving(Explanation explanation, List<FitEngine.MemberFit> admitted) {
        public Surviving {
            admitted = List.copyOf(admitted);
        }
    }
}
