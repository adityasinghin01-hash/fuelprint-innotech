package fuelprint.science.truth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hidden truth of one run: which sources really emit, how much, and when. Only {@code fuelprint.science} may
 * use this package (AGREEMENT ground rule 3); no public type outside it returns an instance.
 */
public final class EmissionPlan {

    /** From {@code timeS} on, source {@code sourceId} emits these rates (zero = off). */
    public record Change(double timeS, String sourceId, double so2GramsPerSecond, double co2GramsPerSecond) {
    }

    private final List<Change> changes;
    private int next;

    public EmissionPlan(List<Change> changes) {
        List<Change> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingDouble(Change::timeS));
        this.changes = List.copyOf(sorted);
    }

    /** The changes whose time has come at {@code timeS}; each is handed out once. */
    public List<Change> due(double timeS) {
        List<Change> out = new ArrayList<>();
        while (next < changes.size() && changes.get(next).timeS() <= timeS + 1e-9) {
            out.add(changes.get(next++));
        }
        return out;
    }

    /** A copy that starts again from time zero. */
    public EmissionPlan fresh() {
        return new EmissionPlan(changes);
    }

    List<Change> all() {
        return changes;
    }
}
