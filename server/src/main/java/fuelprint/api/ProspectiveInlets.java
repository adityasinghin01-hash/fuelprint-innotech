package fuelprint.api;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Proposed planner/field port: a hypothetical flight is distinct from recorded InletHistory. */
public interface ProspectiveInlets {
    /** Copy the vehicle and forecast immediate execution; never move live state or save records. */
    Optional<Forecast> forecast(PlanContext context, VehicleState vehicle, Enu target, BooleanSupplier budgetExpired);

    /** Stages use their normal [start,end) convention; samples contain recorded past and hypothetical future. */
    record Forecast(List<InletSample> samples, List<Stage> stages, double completeSeconds, double energyWh) {
        public Forecast { samples=List.copyOf(samples); stages=List.copyOf(stages); }
    }
}
