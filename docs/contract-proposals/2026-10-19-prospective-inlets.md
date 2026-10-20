# Shared forecast hook — owner-approved 19 October 2026

Prepared 19 October 2026. Aditya Singh explicitly approved this interface in the continuation conversation: "this forecasting interface, you can build it." This is separate from the earlier CaptureRules.windows 2.2 approval. Contract/API documentation now records version 2.3; the existing implementation remains in the uncommitted working branch. Approval does not authorize a commit, main-branch merge, freeze, scenario rewrite or statistical-policy change.

## Problem and behavior

The planner must predict the same flight, inlet offset, background stage and collection stage that field actually executes. `docs/CORE-API.md:105` requires: “Packages never import each other's internals; they talk through `fuelprint.api`, records and events.” A science-private callback implemented by field would violate that rule.

Add this optional Java hook in `fuelprint.api`. Field forecasts an independent copy of its vehicle using its existing autopilot/kinematics/path geometry, retaining the public recorded inlet history through the current time. Science converts the forecast's BACKGROUND/COLLECT stages and samples into its existing temporal-contrast response model. The original recorded InletHistory remains actual observations only.

```java
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
```

## Approved CORE-API addition (version 2.3)

Field implements optional `ProspectiveInlets`. Science obtains its implementations through Spring and uses the returned immutable public samples/stages to forecast immediate execution of a nominated action. Samples before the current simulation time must come from the recorded history for this run/vehicle; future samples are explicitly hypothetical. BACKGROUND/COLLECT stage intervals use the normal [start,end) convention. The forecast includes the complete procedure and return time/energy. The forecast never moves the real vehicle or writes records. Missing history, no safe path, an unsupported vehicle, insufficient run duration or cancellation returns empty; the planner records an unavailable forecast reason and never substitutes an instant steady measurement. The planner checks its two-second budget before invoking the hook, the field checks cancellation within trajectory generation, and science checks cancellation within response propagation. An expired plan is PENDING with no recommended action.

The forecast assumes immediate autopilot execution of the nominated target and valid minimum-duration sampling stages. Approval delays, visitor deviations, later wind changes and later instrument faults remain uncertain. Actual attribution always uses the recorded trajectory and measured gas/flow values.

## Scope

This adds one Java interface with a nested result record and updates the planner/field adapter. It adds no network command, record type or automatic approval. Existing human mission approval and capture review gates remain in effect. The scenario story and pair-survival policy are outside this amendment.

## Concrete verification so far

The implementation was included in the saved passing 528-test Java suite. Three flight regressions cover real geometry and physics: every 20 Hz inlet position matches an independent autopilot/kinematics flight; missing history produces no forecast; cancellation stops an actual forecast mid-loop. Planner and response tests also cover budget expiry and joint gas covariance. Focused post-approval verification and final independent review are recorded in the backend verification report; no main-branch integration is claimed here.
