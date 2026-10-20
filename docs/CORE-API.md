# Core API for the other server packages (version 2.3, 19 Oct 2026)

AGREEMENT.md fixes what crosses the network. This file fixes the Java hooks between the core and the other server packages (science, field, alerts, ops, cases), so several jobs can build at the same time and still fit. Only Aditya Singh (through Claude) changes it. The interfaces named here live in `fuelprint.api` (built in P1/P1b); the implementations named "core" live in `fuelprint.core` (built in P3).

## 1. Commands and automation (P1)

- `CommandBus`: every state change. Register a handler per command type with its allowed roles (add `automation` only where AGREEMENT section 5 lists it).
- `AutomationBus.submit(commandType, runId, payload)`: send a command as the `automation` actor from a listener. Never save records from a listener.
- `RecordStore`: read records (`get`, `list`, latest revision). Writes happen only through handler results, except the world loop (section 3).
- `BlobStore`: exact bytes by SHA-256.
- Spring application events published after commit: `RecordSaved(recordType, recordId, revision, runId, actor, automatic)`. Listen with `@EventListener`.

## 2. Sessions (core, P3)

```java
public interface SessionService {
    Optional<Session> resolve(HttpServletRequest request);          // from the fp_session cookie
    Session createStation(String stationId, Set<String> roles);      // used by POST /api/session/station (core)
    Session createVisitor(String visitorLabel);                      // used by field after a pairing code is redeemed
    Session createGuest();                                           // used by POST /api/session/guest (core)
    void writeCookie(HttpServletResponse response, Session session); // HttpOnly, SameSite=Lax, name fp_session
    boolean originAllowed(HttpServletRequest request);               // same-origin check for sockets and POSTs
}
```

- `Session` has `sessionId`, `actorLabel` (for example `supervisor@laptop-1`, `visitor-7f3a`, `guest-21c9`), `roles`.
- Core owns `POST /api/session/station` and `POST /api/session/guest`. Field owns `POST /api/session/join` (pairing) and calls `createVisitor`.
- Guests get only the role `guest`; visitors get `visitor` (which also allows alerts sign-up).
- Tests that send commands log in with a real `fp_session` cookie from `CookieSessionService` (see `CommandControllerTest`). The old header test session is gone.

## 3. The world loop (core runs it, P3)

Core runs one loop thread at 20 Hz (dt 0.05 s of simulation time, times the time scale) while the run is RUNNING. Each step, in this order:

1. `WorldClock.step(dt)` (science).
2. Every `WorldStepListener.onStep(simTimeS, dtS)` (field moves vehicles here).
3. `ObservationProvider.observe(simTimeS, inlets)` (science), where `inlets` = the four fixed sensors plus every `VehicleSource.vehicles()` inlet.
4. Every `ObservationListener.onObservation(simTimeS, batch)` (field feeds capture stages here).
5. Once per simulated second: core saves `reading` records for that second (one transaction, events published after commit), then calls `Detector.windows(input)` for completed windows, followed by `Detector.evaluate(input)` (science). Core saves those windows in the same transaction as any trigger, with actor `automation`, so a trigger can cite them. The default `windows(input)` returns an empty list.
6. 10 times per wall second: core builds and broadcasts the `tick` (wind from `WorldClock.wind()`, `time_scale`, vehicles from `VehicleSource`, latest readings).

```java
public interface WorldStepListener   { void onStep(double simTimeS, double dtS); }
public interface ObservationListener { void onObservation(double simTimeS, ReadingBatch batch); }
public interface VehicleSource       { List<VehicleState> vehicles(); }          // field implements
public interface ClockControl {                                                    // core implements
    double simTimeS(); double timeScale();
    void requestTimeScale(String requester, double scale);  // 1 or 15; core allows 15 only while every vehicle is stationary
    void releaseTimeScale(String requester);
}
```

`VehicleState` has the tick vehicle fields of AGREEMENT section 3 (`vehicle_id`, `label`, `kind`, `pose`, `velocity`, `inlet`, `energy_wh`, `stage`, `lease_holder`, `pilot`).

Field implements `InletHistory`; science's ranker reads recorded vehicle inlet tracks through it:

```java
public record InletSample(double simTimeS, Enu inlet) {}
public interface InletHistory {
    Optional<List<InletSample>> track(String runId, String sensorOrVehicleId, double startS, double endS);
}
```

Samples are oldest first and cover the requested window, including needed boundary samples. An empty Optional means unknown; a present list is nonempty. No guessed vehicle position is used when history is missing.

## 4. Runs and scenarios (P3 core, science implements the setup)

```java
public interface WorldSetup {                     // science implements
    void startRun(RunContext run);                 // load preset SC1/SC2/SC3: sources, wind, hidden truth (in science.truth)
    void stopRun(String runId);
}
public record RunContext(String runId, String presetId, String mode, long seed) {}
```

- Core handles `create_run`, `pause_run`, `resume_run`, `reset_run` and calls `WorldSetup.startRun` / `stopRun`.
- Spring events after commit: `RunStarted(runId, presetId, mode)`, `RunPaused(runId)`, `RunResumed(runId)`, `RunEnded(runId, reason)` with reason COMPLETED, ABORTED, RESET or INTERRUPTED.
- On start-up after a crash, core marks the previous RUNNING run INTERRUPTED and publishes `RunEnded(..., INTERRUPTED)`; field revokes leases and interrupts missions on that event.
- Science publishes `fuelprint.api.WorldCapacityReached(runId, reason, simTimeS, puffCount)` when world capacity is used up. Core listens and submits `complete_run` through `AutomationBus`, with payload `run_id` and `reason: WORLD_CAPACITY`. Only automation may send it, and only for a RUNNING or PAUSED run. It saves COMPLETED; the existing run lifecycle then stops the world and publishes `RunEnded(runId, COMPLETED)` after commit, so field stops the drone and revokes leases. The normal bus supplies revision checks, idempotency and an `automatic: true` receipt.

## 5. Automation settings (core, P3)

```java
public interface AutomationSettings { boolean isOn(String setting); int autopilotWaitS(); }
```

Settings and defaults: AGREEMENT section 6 `automation_settings`. Changed only by `change_automation` (presenter).

## 6. Sockets

- Core owns `/ws/live`. Field owns `/ws/pilot` (registered in the field package with Spring WebSocket, using `SessionService.resolve` and `originAllowed`).
- Nobody else opens sockets.

## 7. Fake replay mode (core, P3)

With `--fp.mode=replay`, the core replays `server/src/main/resources/replay/SC*.ndjson` (events and ticks) instead of running the world loop. Command handlers of ops and cases still run, so teammates can test their commands. Science, field and alerts real beans are not active in replay mode; the stubs are.

## 8. Stubs (core, P3)

The default profile is `stub`, with `@Profile("stub")` service implementations returning fixture-shaped answers from `apps/shared/fixtures/`. Real beans use `@Profile("!stub")`. Tests that need real beans use `@ActiveProfiles("real")`.

## 9. Package rules

- `fuelprint.core` never imports `fuelprint.science`, `fuelprint.field`, `fuelprint.alerts`, `fuelprint.ops`, `fuelprint.cases` or `fuelprint.ai`. It only sees their beans through `fuelprint.api` interfaces.
- Packages never import each other's internals; they talk through `fuelprint.api`, records and events.
- Nothing outside `fuelprint.science` imports `fuelprint.science.truth`.

## 10. Reviewed capture windows (contract 2.2)

```java
public interface CaptureRules {
    ChannelVerdict judge(CaptureInput input);
    default List<Window> windows(CaptureInput input) { return List.of(); }
}
```

`CaptureRules.windows(input)` computes a background window and a complete 30-second collection window for one channel from the attempt's recorded stages, gas readings and branch-flow readings. It does not save records or make the human review decision. Both windows use server UUID v4 IDs. All contributing readings must have the same instrument, gas and origin. Unknown background, insufficient valid consecutive collection, missing or invalid flow, and unsupported channels produce no usable collection window and an explicit diagnostic reason in the capture review result.

The field `review_capture` handler calls this function only for a channel whose automatic pre-check and human decision are both VALID. It obtains the raw input from the saved reading records belonging to the mission vehicle and the attempt's stage intervals. It saves the returned windows with the screening record in the same command transaction. Repeating a command returns its existing receipt and creates no new windows. REJECTED, LIMITED, interrupted and unreviewed capture channels remain available as raw readings, attempts and screening records, but are not admitted as usable capture windows by this amendment.

For a vehicle collection window, `mean_enhancement` is the measured collection mean minus the measured BACKGROUND-stage mean. It is a temporal measurement contrast, not a claim that the background was plume-free. The collection window cites its saved background through `background_window_id`. Its uncertainty includes both averaging operations and their correlation under the declared synthetic noise model.

Science predicts this same collection-minus-background contrast, propagating the gas-specific transport, chamber and sensor response through the recorded inlet history. It must not compare a temporal contrast against an absolute steady concentration. Missing background records or required inlet history exclude the window with a reason. Analyses using vehicle contrasts declare the possible plume content of the background and the finite-history initialization assumption in `limitations[]`.


Input and Window signatures are unchanged. The default method returns no windows so existing implementations remain compatible. Fixed-box detector windows keep their existing meaning. One-second readings are right endpoints: a 30-sample window at integer times T through T+29 is represented by (T-1,T+29]. Selection from BACKGROUND/COLLECT stage records uses each stage's start-inclusive, end-exclusive interval. The inverse model uses the same discrete sample times.

## 11. Hypothetical mission forecasts (contract 2.3)

Owner-approved on 19 October 2026 in [the shared-hook proposal](contract-proposals/2026-10-19-prospective-inlets.md).

```java
public interface ProspectiveInlets {
    Optional<Forecast> forecast(PlanContext context, VehicleState vehicle, Enu target,
                               java.util.function.BooleanSupplier budgetExpired);
    record Forecast(List<InletSample> samples, List<Stage> stages,
                    double completeSeconds, double energyWh) {}
}
```

Field implements this optional port; science obtains implementations through Spring. Field forecasts an independent copy with the same autopilot, kinematics, geometry and inlet offsets as actual flight. It never moves the live vehicle or writes records. Returned sample/stage lists are immutable copies. Samples through the current simulation time come from recorded history for the same run and vehicle; future samples are hypothetical. Actual `InletHistory` remains observations only.

Science propagates the forecast through its gas response and background/collection contrast model, including joint gas covariance. Stage intervals use [start,end). Duration and energy include the complete procedure and return, rather than travel alone. Missing history, no safe path, unsupported vehicles, insufficient run duration or cancellation returns empty; the planner records an unavailable forecast reason and never substitutes an instant steady measurement.

The planner checks its two-second budget before invoking the hook, field checks cancellation inside trajectory generation, and science checks inside response propagation. An expired plan is PENDING with no recommended action. Forecasts assume immediate autopilot execution and valid minimum-duration sampling stages. Approval delays, visitor deviations, later wind changes and later instrument faults remain uncertain. Attribution always uses recorded trajectories and measured gas/flow values. No network command, automatic approval or change to human mission/capture decisions is introduced.

## 12. Initial mission departure authority (internal Java integration)

The reviewed backend workflow adds a read-only port in `fuelprint.api`:

```java
public interface MissionAuthority {
    Decision checkInitialDeparture(String runId, String missionId);
    record Decision(boolean allowed, String reasonCode, String reasonText) {}
}
```

Operations implements it using the same current run, simulation expiry and dependency checks as supervisor approval. It reads committed public records, current clock and wind; it neither approves nor expires a mission and writes no records. The concrete Decision constructor accepts either permission with both reason fields null, or refusal with nonblank code and text.

Field calls this port on the serialized command thread before the initial APPROVED-to-LEASED grant in `acquire_lease`, human/automatic `start_autopilot`, and initial `take_over`. A missing, ambiguous, failing or null-returning validator refuses departure with AUTHORITY_UNAVAILABLE. Changed dependencies or expired authority return the specific refusal from Operations. A refusal saves no lease or departure revision. A mission revision check alone cannot detect analysis, wind or time changes that leave the mission revision unchanged.

Automatic EXPIRED persistence still uses the ordinary `expire_mission` command. Correctness does not depend on expiry overtaking an already queued departure. Already-started handover/resume and safety RETURN retain their existing lifecycle. Isolated Field tests may explicitly substitute the port; genuine Operations/Field integrations use the real validator. There is no production fallback for missions missing approval fields, new network message, changed human role or scientific calculation in this port.

This describes the local reviewed implementation. No commit/publication or shared-branch integration is implied. The concrete Operations storage/evidence/mission encoding handoff is recorded separately in `docs/contract-proposals/2026-10-19-backend-integration-decisions.md`; its proposed board metrics remain unimplemented and unaccepted as shared definitions.
