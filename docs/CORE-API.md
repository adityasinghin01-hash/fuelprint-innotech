# Core API for the other server packages (version 2.1, 18 Oct 2026)

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
