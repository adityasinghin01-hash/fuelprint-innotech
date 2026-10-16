# The FuelPrint agreement (fp/1)

This file is the contract between the five parts of FuelPrint: the shared core, the science engine, Operations, Cases and Field. Every part builds against this file and nothing else, which is how the three groups can work at the same time without waiting for each other.

Only Aditya Singh (through Claude) changes this file. If you need a change, message the group with what you need and why. Do not edit it in your own branch. A pull request that touches `contracts/` without his approval gets closed.

Exact numbers that this file does not repeat live in `contracts/reference/`:

| File | What it holds |
|---|---|
| `world-profile.json` | Map origin, the six fictional factories S01 to S06, the four sensor boxes N1 to N4, roads, clock rates, wind defaults, vehicle limits, noise settings, procedure times |
| `method-profiles.json` | The measuring methods: online gas capture, aerosol filter, fuel material assay, plus the disabled physical ones |
| `command-targets.json` | The original 20 commands with their guards (the long research version of section 5) |
| `contract-record-targets.json` | Every field of all 41 record types (the long research version of section 6) |
| `hardware-selection.json` | The paper hardware choices, for the Operations hardware page |
| `questions-92-trace.json` | The 92 judge questions and their design answers |

When this file and a reference file disagree, this file wins for the department build. Tell Aditya Singh so the conflict can be fixed.

## 1. Ground rules

1. **One server is the boss.** The Java server owns the clock, every record and every decision. Browsers only show what the server sends and ask the server to do things. A browser never calculates a reading, a score or a case outcome.
2. **Everything is a command or a record.** A browser changes the world only by sending a command. The server answers with a receipt. Records are saved in SQLite and never edited in place: a change makes a new revision.
3. **Hidden truth stays hidden.** The science engine knows which factory is really emitting. That knowledge lives only in the Java package `fuelprint.science.truth`. No other package, no API response, no browser file and no AI prompt may contain it. A test fails the build if any class outside `fuelprint.science` imports `fuelprint.science.truth`.
4. **No missing value becomes zero.** If a value is unknown, send `null` with a reason. A failed flow is not clean air.
5. **Three outcomes are all real.** Every investigation can end as supported, rejected or inconclusive. The app must show all three as normal results, not as errors.
6. **Synthetic stays labelled.** Every reading carries `origin`. Department and finale runs are `SYNTHETIC_TEACHING`. The optional bench sensor (finale only) is `PHYSICAL_BENCH`. They are never mixed in one calculation.

## 2. Units, time, IDs and coordinates

| Thing | Rule |
|---|---|
| IDs | UUID v4 strings made by the server. Short display codes (like `S02`, `N3`) are labels, not IDs. |
| Command IDs | UUID v4 made by the browser, used once. Retrying the same command uses the same ID. |
| Simulation time | `sim_time_s`, seconds since the run started, a decimal number. |
| Wall time | `*_utc` fields, ISO 8601 strings like `2026-10-22T10:15:04.120Z`. |
| Coordinates | ENU metres: `east`, `north`, `up`. Origin 28.87 N, 77.12 E. `up` is height above local ground. |
| Three.js | position = `(east, up, -north)`. Field converts with one function only. |
| Wind | `wind_from_deg` is where the wind comes FROM, clockwise from north. 270 means from the west, blowing east. |
| Gases | SO2 in `ppb`, CO2 in `ppm`. Particles in `ug_m3` and labelled optical estimate. |
| Flow | `L_min` (actual litres per minute). Volume in `L`. |
| Mass | `ug` (micrograms) or `mg` as named in the field. |

## 3. How the apps talk to the server

Everything runs from one laptop (the host) at one address, for example `http://192.168.1.20:8080`.

| Path | What it is |
|---|---|
| `/operations/`, `/cases/`, `/field/` | The three built apps, served by the Java server |
| `/field/controller` | The phone joystick page |
| `POST /api/commands` | Send one command, get one receipt |
| `GET /api/runs/current` | The current run's ID and state |
| `GET /api/snapshot?run_id=` | Everything current about one run, plus its `cursor` |
| `GET /api/records/{type}/{id}` | One saved record, latest revision |
| `GET /api/records/{type}?run_id=` | All records of one type in a run |
| `POST /api/session/station` | Laptop login with a station PIN, sets a cookie |
| `POST /api/session/join` | Phone redeems a pairing code, sets a visitor cookie |
| `WS /ws/live?run_id=&after=` | Server pushes `tick` and `event` messages |
| `WS /ws/pilot` | Phone or keyboard sends joystick input |

Rules for the live socket:

- The server sends a `tick` 10 times a second with moving things (vehicles, latest readings, wind, clock).
- The server sends an `event` every time a record is saved. Each event has a `cursor` that goes up by one.
- After a reconnect, the browser asks for `after=<last cursor>`. If the server no longer has that cursor, the browser reloads the snapshot.
- If no message arrives for 3 seconds, the browser shows "stale" and disables buttons until fresh data comes back.

### Message envelope (every socket message)

```json
{
  "schema_version": "fp/1",
  "message_type": "tick | event | hello",
  "run_id": "uuid",
  "server_epoch": "uuid that changes when the server restarts",
  "cursor": 1842,
  "sim_time_s": 412.35,
  "server_time_utc": "2026-10-22T10:15:04.120Z",
  "payload": {}
}
```

### Tick payload (10 per second, not saved)

```json
{
  "wind": {"from_deg": 270, "speed_m_s": 3.0},
  "time_scale": 1,
  "vehicles": [{"vehicle_id": "uuid", "label": "DRONE-1", "kind": "DRONE",
    "pose": {"east": 12.0, "north": 98.5, "up": 35.0, "yaw_deg": 90},
    "velocity": {"east": 1.2, "north": 0, "up": 0},
    "inlet": {"east": 12.4, "north": 98.5, "up": 34.6},
    "energy_wh": 51.3, "stage": "OUTBOUND", "lease_holder": "visitor-7f3a"}],
  "latest_readings": [{"sensor": "N2", "channel": "SO2_ppb", "value": 61.2,
    "quality": ["OK"], "sim_time_s": 412.0}]
}
```

### Command and receipt

```json
{
  "schema_version": "fp/1",
  "command_id": "uuid made by the browser",
  "command_type": "approve_mission",
  "run_id": "uuid",
  "expected_revision": 7,
  "payload": {"mission_id": "uuid", "dependency_hash": "sha256 hex"}
}
```

```json
{
  "receipt_id": "uuid",
  "command_id": "same uuid",
  "status": "APPLIED | REJECTED | QUEUED | INTERRUPTED",
  "reason_code": "STALE_REVISION | NOT_ALLOWED | INVALID | ...",
  "reason_text": "plain words for the screen",
  "record_refs": [{"type": "mission", "id": "uuid", "revision": 8}]
}
```

- The person sending a command comes from the session cookie, never from the payload.
- Sending the same `command_id` again returns the first receipt and changes nothing.
- If `expected_revision` is old, the receipt is `REJECTED` with `STALE_REVISION`. The browser reloads and shows the newer state. It never overwrites.
- The server saves the record, then saves the event, then sends the receipt, then broadcasts. It never broadcasts something it has not saved.

## 4. Roles and stations

| Role | Who uses it | Can do |
|---|---|---|
| `presenter` | Any team laptop | Start, pause, reset runs, pick the scenario |
| `analyst` | Operations laptop 1 | Propose missions |
| `supervisor` | Operations laptop 1 | Approve or reject missions, take over the drone |
| `field_operator` | Field laptop 2 | Fly with the keyboard, start captures, create pairings |
| `qc_reviewer` | Field laptop 2 | Review a capture channel by channel |
| `case_reviewer` | Cases laptops 3 and 4 | Create cases, write findings |
| `inspector_demo` | Cases laptop 4 | Create specimens, add custody steps |
| `lab_demo` | Cases laptop 3 | Enter lab results |
| `exporter` | Cases laptop 4 | Seal and verify evidence |
| `board` | Operations laptop 5 | Record the board decision |
| `visitor` | Phones | Join the queue, fly only while holding the lease |

Each laptop logs in with a station PIN set in `server/config/stations.json` (not committed; an example file is). A station can hold more than one role. Visitors can never approve, capture, change wind, see truth or export.

## 5. Commands

D = needed for the department round. F = finale.

| Command | Role | Payload | Main guard | Owner | When |
|---|---|---|---|---|---|
| `create_run` | presenter | `preset_id`, `mode` (BLIND or TEACHING) | makes a new run, never reuses one | core | D |
| `pause_run` / `resume_run` | presenter | `run_id` | run is RUNNING or PAUSED | core | D |
| `reset_run` | presenter | `preset_id`, `mode` | new run; old missions interrupted, leases revoked | core | D |
| `schedule_survey` | analyst | `action_id`, `reason` | lets a mission happen without an alarm | ops | D |
| `propose_mission` | analyst | `proposal_id` or `survey_id`, `action_id` | the action is feasible in the latest proposal | ops | D |
| `approve_mission` | supervisor | `mission_id`, `dependency_hash` | hash matches current analysis, wind and route; not expired | ops | D |
| `reject_mission` | supervisor | `mission_id`, `reason` | mission is PROPOSED | ops | D |
| `create_pairing` | field_operator | `mission_id`, `vehicle_id` | mission APPROVED; code lives 120 s | field | D |
| `confirm_pairing` | field_operator | `pairing_id` | phone has redeemed the code | field | D |
| `acquire_lease` | visitor or field_operator | `mission_id`, `vehicle_id` | no other active lease on that vehicle | field | D |
| `release_lease` | lease holder | `lease_id` | holder matches | field | D |
| `take_over` | supervisor or field_operator | `vehicle_id` | revokes any visitor lease, vehicle stops first | field | D |
| `control_input` | lease holder | sent on `/ws/pilot`, see section 7 | fresh sequence, lease active | field | D |
| `start_capture` | field_operator | `mission_id`, `method_id`, `channel_ids` | vehicle at target, approval current | field | D |
| `review_capture` | qc_reviewer | `attempt_id`, `channel_decisions[]` | cannot pass a channel that failed a rule | field | D |
| `create_case` | case_reviewer | `screening_record_ids[]`, `purpose` | records exist; rejected ones kept with their limits | cases | D |
| `create_specimen` | inspector_demo | `case_id`, `site_id`, `material`, `method_id`, `container`, `seal_id`, `mass_mg` | case in INTAKE or later; mass at least 50 mg | cases | D |
| `append_custody` | inspector_demo or lab_demo | `specimen_id`, `to_holder`, `seal_state`, `event_time_utc`, `exceptions[]` | from the current holder, or an explicit exception | cases | D |
| `request_lab` | lab_demo | `specimen_id`, `method_id`, `qc_inputs` | method matches material | cases | D |
| `revise_finding` | case_reviewer | `case_id`, `outcome`, `cited_refs[]`, `alternatives[]`, `limitations[]` | outcome allowed by the cited records | cases | D |
| `record_board_decision` | board | `case_id`, `decision`, `rationale`, `notice` | case has a finding | ops | D |
| `seal_export` | exporter | `case_id`, `case_revision` | finding exists; all cited bytes present | cases | D |
| `verify_export` | exporter | `export_id` | none | cases | D |
| `tamper_copy` | exporter | `export_id`, `member_path` | changes one byte in a COPY, never the original | cases | D |
| `change_teaching_config` | presenter | `parameter`, `value`, `reason` | TEACHING mode only; allowed list | science + ops | F |
| `revise_placement` | analyst | `node_id`, `inlet_pose` | allowed mount, not inside a building | ops | F |
| `ingest_frames` | bench adapter | `device_id`, `boot_id`, `frames[]` | provisioned device; max 100 frames | core | F |

## 6. Records

Every record has `id`, `run_id`, `revision`, `created_utc` and `created_by`. Only the extra fields are listed. Full field lists for the research version are in `contract-record-targets.json`.

| Record | Main fields | Owner | When |
|---|---|---|---|
| `run` | `preset_id`, `mode`, `state` (CREATED, RUNNING, PAUSED, COMPLETED, ABORTED), `parent_run_id` | core | D |
| `sensor` | `label` (N1 to N4), `inlet` (ENU), `channels[]`, `status`, `calibration_state` | core | D |
| `reading` | `sensor_or_vehicle_id`, `channel`, `value`, `raw_value`, `unit`, `sim_time_s`, `quality[]`, `origin` | science writes, core stores | D |
| `window` | `channel`, `start_s`, `end_s`, `valid_seconds`, `mean_enhancement`, `std_uncertainty`, `background_window_id` | science | D |
| `trigger` | `channel`, `sensor`, `intervals[]`, `background_window_id`, `rule` (TEACH-DETECT-1) | science | D |
| `investigation` | `trigger_id` or `survey_id`, `state` (OPEN, ANALYSED, MISSION_PROPOSED, MISSION_APPROVED, EVIDENCE_BACK, CLOSED), `analysis_ids[]` | ops | D |
| `analysis` | `input_window_ids[]`, `outcome`, `qualification`, `candidates[]`, `exclusions[]`, `limitations[]`, `explanation_text`, `algorithm_version` | science | D |
| `proposal` | `analysis_id`, `actions[]` (each: `action_id`, `kind`, `vehicle`, `target`, `score`, `complete_minutes`, `feasible`, `rejection_reason`), `result` (BEST_ACTION or NO_USEFUL_MEASUREMENT), `best_action_id`, `cheaper_action_id` | science | D |
| `mission` | `proposal_id` or `survey_id`, `action_id`, `vehicle_id`, `target`, `route[]`, `method_id`, `channel_ids[]`, `dependency_hash`, `state`, `expires_s` | ops creates, field moves | D |
| `pairing` | `mission_id`, `vehicle_id`, `code` (6 characters), `phrase` (2 words), `expires_utc`, `state` | field | D |
| `lease` | `vehicle_id`, `holder_session`, `state` (ACTIVE, RELEASED, REVOKED, EXPIRED), `expires_utc`, `last_sequence` | field | D |
| `attempt` | `mission_id`, `method_id`, `stages[]` (stage, start_s, end_s), `channels[]` (channel, valid_seconds, volume_L, status, reasons[]), `state` | field | D |
| `screening_record` | `attempt_id`, `channel`, `named_use`, `decision` (VALID, LIMITED, REJECTED), `reasons[]`, `summary` | field | D |
| `case` | `investigation_id`, `screening_record_ids[]`, `purpose`, `state` (DRAFT, INTAKE, SPECIMEN, LAB, FINDING, SEALED) | cases | D |
| `specimen` | `case_id`, `site_id`, `material`, `method_id`, `container`, `seal_id`, `mass_mg`, `authority_basis` (always INSPECTOR_DEMO), `current_holder` | cases | D |
| `custody_event` | `specimen_id`, `from_holder`, `to_holder`, `seal_state` (INTACT, BROKEN, MISSING), `event_time_utc`, `recorded_utc`, `exceptions[]` | cases | D |
| `lab_result` | `specimen_id`, `method_id`, `raw` (gross_ug, blank_ug, mass_mg, dup_a_pct, dup_b_pct, spike_added_ug, spike_recovered_ug), `computed` (net_ug, percent_w_w, rpd_pct, recovery_pct), `qc_status`, `qc_reasons[]`, `reporting` (REPORTED or BELOW_LOQ) | cases | D |
| `finding` | `case_id`, `outcome` (SUPPORTED_WITHIN_SCOPE, REJECTED_FOR_USE, INCONCLUSIVE), `cited_refs[]`, `alternatives[]`, `limitations[]`, `legal_suitability` (always NOT_ASSESSED) | cases | D |
| `board_decision` | `case_id`, `decision` (MORE_INVESTIGATION, NO_SUPPORTED_SOURCE, CANDIDATE_SUPPORTED_SIMULATED, SAMPLE_INVALID), `rationale`, `notice` (area, interval, status_text) | ops | D |
| `export` | `case_id`, `case_revision`, `manifest_sha256`, `members[]` (path, length, sha256), `signature` (F), `state` | cases | D |
| `verification_report` | `export_id`, `axes` (integrity, key_trust, lineage, trusted_time, custody_documentation, measurement_qualification, legal_suitability), `reasons[]` | cases | D |
| `event` | `cursor`, `event_type`, `record_type`, `record_id`, `revision`, `sim_time_s`, `wall_utc` | core | D |
| `receipt` | see section 3 | core | D |

### Analysis outcomes (science sets them, nobody overrides them)

| Outcome | Meaning on screen |
|---|---|
| `INVALID` | Not enough usable data to analyse |
| `NO_SIGNAL` | Usable data, but nothing above the floor. This does NOT mean the factories are clean. |
| `UNKNOWN_OR_MISMATCH` | None of the registered factories explains the data. Maybe an unregistered source. |
| `AMBIGUOUS_OR_OPTIONAL_MIXTURE` | More than one explanation still fits |
| `PROVISIONAL_SINGLE` | One registered factory explains the data best, inside this synthetic model only |

Each entry in `candidates[]` has `explanation_id` (like `S02`, `S01+S02`, `BACKGROUND`, `UNKNOWN`), `sources[]`, `fit` (normalised error, lower fits better, `null` for UNKNOWN), `status` (SURVIVES or REJECTED) and `reason`. The UNKNOWN row is always present. Example values for every record are in `apps/shared/fixtures/`.

`qualification` is always `MODEL_NOT_QUALIFIED` or `SYNTHETIC_SCOPE_ONLY` in our builds. A score is a fit number, never a probability, and the screen must never show it as a percentage chance.

## 7. Pilot input (phone and keyboard)

Sent on `/ws/pilot` about 10 times a second:

```json
{"lease_id": "uuid", "sequence": 1532, "neutral": false,
 "velocity_enu": {"east": 1.5, "north": -0.4, "up": 0.0}, "sent_utc": "..."}
```

- The server limits speed (drone 3 m/s, van 2 m/s), acceleration (1 m/s per second) and the allowed area (drone east and north within plus or minus 450 m, height 2 to 80 m).
- If no fresh input arrives for 400 ms, the server sets velocity to zero. Target: stopped within 500 ms.
- The lease lasts 2 s and renews only with valid fresh input. An old sequence number is ignored and does not renew the lease.
- When the phone hides the page, loses touch or loses focus, it sends one `neutral: true` message.
- After a reconnect the phone stays neutral until the visitor presses "fly" again. The server never replays old input.

## 8. State machines

```
run:      CREATED -> RUNNING <-> PAUSED -> COMPLETED | ABORTED   (reset makes a NEW run)
mission:  PROPOSED -> APPROVED -> LEASED -> OUTBOUND -> AT_TARGET -> COLLECTING
          -> RETURNING -> COMPLETE
          any state before COMPLETE -> INTERRUPTED | EXPIRED ; PROPOSED -> REJECTED
          wind or analysis change after approval -> needs a NEW approval
attempt:  COLLECTING -> COMPLETE | FAILED | INTERRUPTED   (a retry is a NEW attempt)
case:     DRAFT -> INTAKE -> SPECIMEN -> LAB -> FINDING -> SEALED
custody:  append only; corrections are new events, never edits
```

## 9. Science interfaces (Java)

These live in `server/src/main/java/fuelprint/api/`. Science implements them. Ops and Field call them. The core ships simple stub versions that return fixture data, so Ops and Field can build before science is finished.

```java
public interface ObservationProvider {
    // Readings at each fixed sensor and each vehicle inlet for this tick.
    ReadingBatch observe(double simTimeS, List<InletPose> inlets);
}
public interface WorldClock { void step(double dtS); WindNow wind(); }
public interface Detector   { Optional<Trigger> evaluate(DetectorInput input); }
public interface Ranker     { Analysis analyze(AnalysisInput input); }
public interface Planner    { Proposal plan(Analysis analysis, PlanContext context); }
public interface CaptureRules { ChannelVerdict judge(CaptureInput input); }
```

## 10. Scenarios

| Preset | What happens | Expected ending |
|---|---|---|
| `SC1-NIGHT-PLUME` | S02 emits, wind from 270 at 3 m/s. N2 sees a sustained SO2 rise and raises an alarm. Ranking keeps S01 and S02. The planner sends the drone downwind of both candidates, for example (0, 100, 35). After a valid capture only S02 survives. | `PROVISIONAL_SINGLE` S02, finding SUPPORTED_WITHIN_SCOPE for the synthetic observation only |
| `SC2-BLOCKED-INLET` | Same start. At collection second 10 the SO2 branch flow drops to zero while the raw number still looks normal. | SO2 REJECTED, CO2 still VALID, finding REJECTED_FOR_USE for SO2 |
| `SC3-ABOVE-THE-BOXES` | S03's hot 45 m plume passes over all 3 m inlets. No alarm. The analyst schedules a survey. The drone result fits two explanations equally. | `AMBIGUOUS_OR_OPTIONAL_MIXTURE` or `NO_USEFUL_MEASUREMENT`, finding INCONCLUSIVE |

Finale adds: wind turning mid-mission, two factories at once, a sensor fault, an unregistered source, a weak plume with drift, and the combined-error case from question FP92.

## 11. Ownership of folders

| Folder or package | Owner | Builder |
|---|---|---|
| `contracts/` | Aditya Singh | Claude |
| `server/.../fuelprint/core`, `.../api` | Aditya Singh | Codex on Aditya Singh's Mac |
| `server/.../fuelprint/science` | Aditya Singh | Snowflake Cortex |
| `server/.../fuelprint/field`, `apps/field` | Aditya Singh | Claude (visuals) and Codex |
| `server/.../fuelprint/ops`, `apps/operations` | Aditya Singhal (Abhivansh: health panel and hardware page) | Their Codex |
| `server/.../fuelprint/cases` | Antara | Her Codex |
| `apps/cases` | Aditya | His Codex |
| `apps/shared` | Aditya Singh | Claude |
