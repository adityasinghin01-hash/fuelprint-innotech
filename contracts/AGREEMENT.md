# The FuelPrint agreement (fp/1, version 2.3)

This file is the contract between the parts of FuelPrint: the shared core, the science engine, the AI pieces, the alerts, Operations, Cases and Field. Every part builds against this file and nothing else, which is how the groups can work at the same time without waiting for each other.

Only Aditya Singh (through Claude) changes this file. If you need a change, message the group with what you need and why. Do not edit it in your own branch. A pull request that touches `contracts/` without his approval gets closed.

## 0. What changed in version 2 (18 Oct 2026)

FuelPrint is entered in **AI Solutions for Automation**, so version 2 makes the AI and the automation explicit and complete.

- **Seven AI pieces** (section 11): smoke-spread reasoning, a learned ranker trained on simulated nights (DGX A100), the planner, a learning loop, AI wording by a chat model, a normal-air model trained on real Indian SO2 data, and an autopilot.
- **Automation** (section 12): the server does every routine step itself, as a normal command sent by a new actor `automation`, with a receipt and a log. Five decisions stay human.
- **Citizen SMS alerts** (section 13): opted-in phones get a normal SMS when smoke heads to their area and when the board decides. Sent from an Android phone at the stall.
- **Real data** (section 14): what is real, what is synthetic, and what each real dataset is used for.
- New roles `automation` and `guest`; new commands and records marked **v2** in sections 5 and 6; `receipt` and `event` now carry `actor`; `analysis` gains the learned order; `mission` gains `pilot`; `board_decision.notice` gains `area_ids`.
- Builders: Kiro builds the core, science, AI, alerts and Field code; Claude does the 3D look. Snowflake Cortex is no longer used.

### What changed in version 2.1 (18 Oct 2026)

- Field's queue, internal commands, pairing states and extra record fields are now written down.
- Field shares recorded vehicle inlet positions with science through `InletHistory`. Unknown tracks stay unknown.
- Science sends `WorldCapacityReached`; core sends `complete_run` automatically. The run completes, leases are revoked and the drone stops.
- A planner that uses its 2 s budget returns `PENDING`: "still working it out", with no recommended mission.
- `Detector.windows()` supplies completed windows for core to save with any trigger. The start-up phone purge can use `expired_only`.

### What changed in version 2.2 (18 Oct 2026, owner-approved)

- Capture rules return measured background and collection windows through a backward-compatible default method.
- Usable vehicle windows are saved only with a successful human VALID review, in that same command transaction.
- Vehicle enhancement means collection minus its measured background; the ranker predicts the same temporal contrast, including instrument memory. A target background is never silently assumed plume-free.

### What changed in version 2.3 (19 Oct 2026, owner-approved)

- Aditya Singh approved the shared forecasting interface: "this forecasting interface, you can build it."
- Field supplies an optional `ProspectiveInlets` forecast through public API types. Science uses it to predict an immediate hypothetical mission, including sampling stages and complete procedure/return time and energy, without moving the live vehicle or saving observations.
- Forecasts preserve recorded history, distinguish hypothetical future samples, and support cancellation within the planner's two-second budget. Missing or unavailable forecasts never become instant steady measurements.
- This approval does not change scenario outcomes, pair filtering, human-only decisions or the scenario/freeze gate. The initial product direction is a hardware-and-physics demonstration in 3D; gameplay is deferred. This backend continuation does not build the 3D environment.

Exact numbers that this file does not repeat live in `contracts/reference/`:

| File | What it holds |
|---|---|
| `world-profile.json` | Map origin, the six fictional factories S01 to S06, the four sensor boxes N1 to N4, roads, clock rates, wind defaults, vehicle limits, noise settings, procedure times |
| `method-profiles.json` | The measuring methods: online gas capture, aerosol filter, fuel material assay, plus the disabled physical ones |
| `alert-areas.json` | **v2.** The four fictional residential areas that can receive SMS alerts |
| `command-targets.json` | The original 20 commands with their guards (the long research version of section 5) |
| `contract-record-targets.json` | Every field of all 41 record types (the long research version of section 6) |
| `hardware-selection.json` | The paper hardware choices, for the Operations hardware page |
| `questions-92-trace.json` | The 92 judge questions and their design answers |

When this file and a reference file disagree, this file wins. Tell Aditya Singh so the conflict can be fixed.

## 1. Ground rules

1. **One server is the boss.** The Java server owns the clock, every record and every decision. Browsers only show what the server sends and ask the server to do things. A browser never calculates a reading, a score, an AI output or a case outcome.
2. **Everything is a command or a record.** A browser changes the world only by sending a command. The server answers with a receipt. Records are saved in SQLite and never edited in place: a change makes a new revision.
3. **Hidden truth stays hidden.** The science engine knows which factory is really emitting. That knowledge lives only in the Java package `fuelprint.science.truth`, and in training files under `data/training/` that the server never reads or serves. No other package, no API response, no browser file, no AI model input at run time and no chat-model prompt may contain it. A test fails the build if any class outside `fuelprint.science` imports `fuelprint.science.truth`.
4. **No missing value becomes zero.** If a value is unknown, send `null` with a reason. A failed flow is not clean air.
5. **Three outcomes are all real.** Every investigation can end as supported, rejected or inconclusive. The app must show all three as normal results, not as errors.
6. **Synthetic stays labelled.** Every reading carries `origin`. Department and finale runs are `SYNTHETIC_TEACHING`. The optional bench sensor (finale only) is `PHYSICAL_BENCH`. They are never mixed in one calculation. Every AI model says what it was trained and tested on (section 14).
7. **Automation is visible. (v2)** Every automatic step is a normal command sent by the actor `automation` through the same command bus, with the same checks, a receipt and an event. Nothing changes "silently in the background". Screens can always show what the system did by itself.
8. **AI suggests, people sign. (v2)** Five commands can only come from a person: `approve_mission`, `reject_mission`, `review_capture`, `revise_finding`, `record_board_decision`. The `automation` actor is refused for them, and a test proves it.
9. **AI never overrides the rules. (v2)** The learned ranker can only put surviving explanations in order. It cannot bring back a rejected one, change an outcome or remove the UNKNOWN row. The chat model can only reword; it cannot change a number, a status or a decision.
10. **Private data stays private. (v2)** Phone numbers live only in the alerts contact table. They never appear in a record, an event, a tick, an API response, a log line, an evidence pack or a chat-model prompt. Records show a masked number. All numbers are deleted at the end of the event day.

## 2. Units, time, IDs and coordinates

| Thing | Rule |
|---|---|
| IDs | UUID v4 strings made by the server. Short display codes (like `S02`, `N3`, `AREA-E`) are labels, not IDs. |
| Command IDs | UUID v4 made by the browser (or by the server for `automation`), used once. Retrying the same command uses the same ID. |
| Simulation time | `sim_time_s`, seconds since the run started, a decimal number. |
| Wall time | `*_utc` fields, ISO 8601 strings like `2026-10-22T10:15:04.120Z`. |
| Coordinates | ENU metres: `east`, `north`, `up`. Origin 28.87 N, 77.12 E. `up` is height above local ground. |
| Three.js | position = `(east, up, -north)`. Field converts with one function only. |
| Wind | `wind_from_deg` is where the wind comes FROM, clockwise from north. 270 means from the west, blowing east. |
| Gases | SO2 in `ppb`, CO2 in `ppm`. Particles in `ug_m3` and labelled optical estimate. Real CPCB SO2 arrives in µg/m³ and is converted with ppb = µg/m³ × 24.45 / 64.066 (25 °C, 1 atm). |
| Flow | `L_min` (actual litres per minute). Volume in `L`. |
| Mass | `ug` (micrograms) or `mg` as named in the field. |
| Phone numbers | E.164 (`+919812345678`) inside the alerts contact table only. Everywhere else masked as `+91 98xxxxxx78`. |

## 3. How the apps talk to the server

Everything runs from one laptop (the host) at one address, for example `http://192.168.1.20:8080`.

| Path | What it is |
|---|---|
| `/operations/`, `/cases/`, `/field/` | The three built apps, served by the Java server |
| `/field/controller` | The phone joystick page |
| `/alerts/` | **v2.** The phone page where a visitor signs up for SMS alerts |
| `POST /api/commands` | Send one command, get one receipt |
| `GET /api/runs/current` | The current run's ID and state |
| `GET /api/snapshot?run_id=` | Everything current about one run, plus its `cursor` |
| `GET /api/records/{type}/{id}` | One saved record, latest revision |
| `GET /api/records/{type}?run_id=` | All records of one type in a run (`model_version`, `alert_subscription`, `notification` and `automation_settings` are not tied to a run; omit `run_id`) |
| `POST /api/session/station` | Laptop login with a station PIN, sets a cookie |
| `POST /api/session/join` | Phone redeems a pairing code, sets a visitor cookie |
| `POST /api/session/guest` | **v2.** Phone opening `/alerts/` gets a guest cookie (no PIN, rate limited) |
| `GET /api/field/queue` | Public queue: `run_id`, `entries[]` (`mission_id`, position within that mission, `visitor` label), `your_position` (null if not queued), `lease_holder`. Visitor labels only, no session cookies or phone numbers |
| `WS /ws/live?run_id=&after=` | Server pushes `tick` and `event` messages |
| `WS /ws/pilot` | Phone or keyboard sends joystick input |

The chat model (section 11, AI-5) and the SMS gateway (section 13) are called only by the server. No browser talks to them.

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
    "energy_wh": 51.3, "stage": "OUTBOUND", "lease_holder": "visitor-7f3a",
    "pilot": "VISITOR"}],
  "latest_readings": [{"sensor": "N2", "channel": "SO2_ppb", "value": 61.2,
    "quality": ["OK"], "sim_time_s": 412.0}]
}
```

`pilot` is `NONE`, `VISITOR`, `OPERATOR` or `AUTOPILOT`.

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
  "record_refs": [{"type": "mission", "id": "uuid", "revision": 8}],
  "actor": "supervisor@laptop-1 | automation | visitor-7f3a",
  "automatic": false
}
```

- The person sending a command comes from the session cookie, never from the payload. The `automation` actor exists only inside the server; no cookie, PIN or payload can claim it.
- Sending the same `command_id` again returns the first receipt and changes nothing.
- `expected_revision` is the revision of the command's subject record, the main record named in its payload (for example the mission for `approve_mission`). Each handler says which record that is; a command with no subject sends `null`.
- The bus checks, in order: the role, then idempotency by `command_id`, then `expected_revision`. (Idempotency comes before the revision check, so re-sending an applied command returns its first receipt instead of a stale error.)
- If `expected_revision` is old, the receipt is `REJECTED` with `STALE_REVISION`. The browser reloads and shows the newer state. It never overwrites.
- The server saves the record, then saves the event, then sends the receipt, then broadcasts. It never broadcasts something it has not saved.

## 4. Roles and stations

| Role | Who uses it | Can do |
|---|---|---|
| `presenter` | Any team laptop | Start, pause, reset runs, pick the scenario, change automation settings, retrain, send a test SMS, purge phone numbers |
| `analyst` | Operations laptop 1 | Propose missions, schedule surveys, run an analysis again, retrain |
| `supervisor` | Operations laptop 1 | Approve or reject missions, take over the drone, start or stop the autopilot |
| `field_operator` | Field laptop 2 | Fly with the keyboard, start captures, create pairings, start or stop the autopilot |
| `qc_reviewer` | Field laptop 2 | Review a capture channel by channel |
| `case_reviewer` | Cases laptops 3 and 4 | Create cases, write findings |
| `inspector_demo` | Cases laptop 4 | Create specimens, add custody steps |
| `lab_demo` | Cases laptop 3 | Enter lab results |
| `exporter` | Cases laptop 4 | Seal and verify evidence |
| `board` | Operations laptop 5 | Record the board decision |
| `visitor` | Phones that paired | Join the queue, fly only while holding the lease, sign up for alerts |
| `guest` | **v2.** Phones on `/alerts/` | Sign up for alerts and remove their own sign-up. Nothing else. |
| `bench_adapter` | Finale only: the optional bench sensor box | Only `ingest_frames` |
| `automation` | **v2.** The server itself | Only the commands whose Role column lists `automation`. Never the five human commands. |

Each laptop logs in with a station PIN set in `server/config/stations.json` (not committed; an example file is). A station can hold more than one role. Visitors and guests can never approve, capture, change wind, see truth, export, retrain or see anyone's phone number.

## 5. Commands

D = needed for the department round. F = finale. **v2** = new or changed in version 2.

| Command | Role | Payload | Main guard | Owner | When |
|---|---|---|---|---|---|
| `create_run` | presenter | `preset_id`, `mode` (BLIND or TEACHING) | makes a new run, never reuses one | core | D |
| `pause_run` / `resume_run` | presenter | `run_id` | run is RUNNING or PAUSED | core | D |
| `reset_run` | presenter | `preset_id`, `mode` | new run; old missions interrupted, leases revoked | core | D |
| `complete_run` | automation only | `run_id`, `reason` (WORLD_CAPACITY) | run is RUNNING or PAUSED; saves COMPLETED and publishes `RunEnded(runId, COMPLETED)` after commit | core | D |
| `change_automation` **v2** | presenter | `setting`, `value` | setting is in the `automation_settings` list | core | D |
| `open_investigation` **v2** | automation | `trigger_id` | one open investigation per run; a later trigger is attached to it | ops | D |
| `schedule_survey` | analyst | `action_id`, `reason`, `anomaly_id` (optional, v2) | lets a mission happen without an alarm | ops | D |
| `run_analysis` **v2** | automation or analyst | `investigation_id` | calls Ranker, LearnedRanker, Planner; saves exactly what they return | ops | D |
| `propose_mission` **v2** | analyst or automation | `proposal_id` or `survey_id`, `action_id` | the action is feasible in the latest proposal; automation only proposes `best_action_id` and only if `auto_propose` is on | ops | D |
| `approve_mission` | supervisor (human only) | `mission_id`, `dependency_hash` | hash matches current analysis, wind and route; not expired | ops | D |
| `reject_mission` | supervisor (human only) | `mission_id`, `reason` | mission is PROPOSED | ops | D |
| `expire_mission` **v2** | automation | `mission_id`, `reason` (NEEDS_NEW_APPROVAL, TIMED_OUT) | mission APPROVED and not started | ops | D |
| `create_pairing` | field_operator | `mission_id`, `vehicle_id` | mission APPROVED; code lives 120 s | field | D |
| `redeem_pairing` (internal, through session join) | visitor | `pairing_id`, `code` | code matches; pairing CREATED and not expired; saves REDEEMED and the visitor label | field | D |
| `confirm_pairing` | field_operator | `pairing_id` | pairing REDEEMED and not expired; saves CONFIRMED and adds the visitor to the queue | field | D |
| `expire_pairing` (internal) | automation only | `pairing_id` | pairing CREATED or REDEEMED and expiry time reached; saves EXPIRED | field | D |
| `acquire_lease` | visitor or field_operator | `mission_id`, `vehicle_id` | no other person holds a lease; an autopilot lease is handed over only when the mission is not COLLECTING | field | D |
| `release_lease` | lease holder | `lease_id` | holder matches | field | D |
| `end_lease` (internal) | automation only | `lease_id`, `reason` (EXPIRED) | lease exists and is ACTIVE; saves EXPIRED and clears the active mission's pilot. Field's timer sends it when input expires | field | D |
| `advance_mission` (internal) | automation only | `mission_id`, `from_state`, `to_state` | current state matches; only LEASED to OUTBOUND (drone not landed), OUTBOUND to AT_TARGET (within 5 m and height band), RETURNING to COMPLETE (landed at depot; releases lease) | field | D |
| `interrupt_field` (internal) | automation only | `run_id`, `reason` (optional, default RUN_ENDED) | valid run ID; after `RunEnded`, interrupts open missions and COLLECTING attempts, revokes ACTIVE leases and expires CREATED or REDEEMED pairings. Drone stops before this command | field | D |
| `take_over` | supervisor or field_operator | `vehicle_id` | revokes any visitor or autopilot lease, vehicle stops first | field | D |
| `control_input` | lease holder | sent on `/ws/pilot`, see section 7 | fresh sequence, lease active | field | D |
| `start_autopilot` **v2** | supervisor, field_operator or automation | `mission_id`, `vehicle_id` | mission APPROVED or later and not COMPLETE; no person holds the lease; automation only if `auto_autopilot` is on and no pilot for `autopilot_wait_s` | field | D |
| `stop_autopilot` **v2** | supervisor or field_operator | `vehicle_id` | autopilot active; vehicle stops first | field | D |
| `return_to_depot` **v2** | automation, supervisor or field_operator | `vehicle_id`, `reason` (BATTERY_RESERVE, MISSION_DONE, OPERATOR) | vehicle away from depot | field | D |
| `start_capture` **v2** | field_operator or automation | `mission_id`, `method_id`, `channel_ids` | vehicle at target, approval current; automation only if `auto_capture` is on | field | D |
| `update_attempt` (internal) | automation only | `attempt_id` | attempt COLLECTING and its capture is running; saves stages, checks channels after COLLECT, then saves COMPLETE (a usable channel) or FAILED after REVIEW | field | D |
| `review_capture` | qc_reviewer (human only) | `attempt_id`, `channel_decisions[]` | cannot pass a channel that failed a rule | field | D |
| `create_case` | case_reviewer | `screening_record_ids[]`, `purpose` | records exist; rejected ones kept with their limits | cases | D |
| `create_specimen` | inspector_demo | `case_id`, `site_id`, `material`, `method_id`, `container`, `seal_id`, `mass_mg` | case in INTAKE or later; mass at least 50 mg | cases | D |
| `append_custody` | inspector_demo or lab_demo | `specimen_id`, `to_holder`, `seal_state`, `event_time_utc`, `exceptions[]` | from the current holder, or an explicit exception | cases | D |
| `request_lab` | lab_demo | `specimen_id`, `method_id`, `qc_inputs` | method matches material | cases | D |
| `revise_finding` | case_reviewer (human only) | `case_id`, `outcome`, `cited_refs[]`, `alternatives[]`, `limitations[]` | outcome allowed by the cited records | cases | D |
| `record_board_decision` **v2** | board (human only) | `case_id`, `decision`, `rationale`, `notice` (with `area_ids[]`) | case has a finding; notice names no factory | ops | D |
| `seal_export` **v2** | exporter or automation | `case_id`, `case_revision` | finding exists; all cited bytes present; automation only if `auto_seal` is on | cases | D |
| `verify_export` **v2** | exporter or automation | `export_id` | none | cases | D |
| `tamper_copy` | exporter | `export_id`, `member_path` | changes one byte in a COPY, never the original | cases | D |
| `record_anomaly` **v2** | automation | `sensor`, `channel`, `start_s`, `end_s`, values | normal-air model active | ai | D |
| `write_wording` **v2** | automation | `target` ref, `purpose` | target exists; never blocks the target's own command | ai | D |
| `retrain_model` **v2** | presenter, analyst or automation | `model_kind` (LEARNED_RANKER), `reason` | automation only if `auto_retrain` is on, after a case is SEALED | ai | D |
| `subscribe_alerts` **v2** | guest or visitor | `phone_number`, `area_ids[]`, `consent` (must be `true`), `consent_text_version` | valid Indian mobile number; at most 1 active sign-up per number | alerts | D |
| `unsubscribe_alerts` **v2** | guest or visitor (own sign-up), presenter (any) | `subscription_id` | sign-up ACTIVE | alerts | D |
| `send_alert` **v2** | automation or presenter | `kind`, `area_ids[]`, `cause` ref | `auto_alerts` on (for automation); rate limit and daily cap (section 13) | alerts | D |
| `purge_subscriptions` **v2** | presenter or automation | `expired_only` (optional boolean, default false) | false deletes every phone number; true deletes only expired sign-ups' numbers. Automation uses false at local midnight and true at start-up | alerts | D |
| `change_teaching_config` | presenter | `parameter`, `value`, `reason` | TEACHING mode only; allowed list | science + ops | F |
| `revise_placement` | analyst | `node_id`, `inlet_pose` | allowed mount, not inside a building | ops | F |
| `ingest_frames` | bench adapter | `device_id`, `boot_id`, `frames[]` | provisioned device; max 100 frames | core | F |

## 6. Records

Every record has `id`, `run_id` (null for records not tied to a run), `revision`, `created_utc` and `created_by` (the actor, `automation` for automatic steps). Only the extra fields are listed. Full field lists for the research version are in `contract-record-targets.json`.

| Record | Main fields | Owner | When |
|---|---|---|---|
| `run` | `preset_id`, `mode`, `state` (CREATED, RUNNING, PAUSED, COMPLETED, ABORTED), `parent_run_id` | core | D |
| `sensor` | `label` (N1 to N4), `inlet` (ENU), `channels[]`, `status`, `calibration_state` | core | D |
| `reading` | `sensor_or_vehicle_id`, `channel`, `value`, `raw_value`, `unit`, `sim_time_s`, `quality[]`, `origin` | science writes, core stores | D |
| `window` | `sensor_or_vehicle_id` (v2), `channel`, `start_s`, `end_s`, `valid_seconds`, `mean_enhancement`, `std_uncertainty`, `background_window_id` | science | D |
| `trigger` | `channel`, `sensor`, `intervals[]`, `background_window_id`, `rule` (TEACH-DETECT-1) | science | D |
| `anomaly` **v2** | `sensor`, `channel`, `start_s`, `end_s`, `observed_mean`, `expected_low`, `expected_high`, `model_version_id`, `state` (OPEN, CLOSED), `suggestion` (SCHEDULE_SURVEY or null) | ai | D |
| `investigation` | `trigger_id` or `survey_id`, `state` (OPEN, ANALYSED, MISSION_PROPOSED, MISSION_APPROVED, EVIDENCE_BACK, CLOSED), `analysis_ids[]`, `warned_area_ids[]` (v2) | ops | D |
| `analysis` **v2** | `input_window_ids[]`, `outcome`, `qualification`, `candidates[]`, `exclusions[]`, `limitations[]`, `explanation_text`, `algorithm_version`, plus `learned_order[]` (each: `explanation_id`, `rank`, `order_score`), `learned_status` (OK, MODEL_MISSING, NOT_APPLICABLE, FAILED), `learned_model_version_id`, `history_counts` (source label to number of earlier runs where it survived) | science (physics part), ai (learned part, saved by ops through `run_analysis`) | D |
| `proposal` | `analysis_id`, `actions[]` (each: `action_id`, `kind`, `vehicle`, `target`, `score`, `complete_minutes`, `feasible`, `rejection_reason`), `result` (BEST_ACTION, NO_USEFUL_MEASUREMENT or PENDING), `best_action_id`, `cheaper_action_id` | science | D |
| `mission` **v2** | `proposal_id` or `survey_id`, `action_id`, `vehicle_id`, `target`, `route[]`, `method_id`, `channel_ids[]`, `dependency_hash`, `state`, `expires_s`, `pilot` (NONE, VISITOR, OPERATOR, AUTOPILOT) | ops creates, field moves | D |
| `pairing` | `mission_id`, `vehicle_id`, `code` (6 characters), `phrase` (2 words), `expires_utc`, `redeemed_by`, `confirmed_by` (actor labels, null until set), `state` (CREATED, REDEEMED, CONFIRMED, EXPIRED) | field | D |
| `lease` **v2** | `mission_id`, `vehicle_id`, `holder_session` (actor label, `autopilot` for the autopilot), `holder_kind` (VISITOR, OPERATOR, AUTOPILOT), `purpose` (MISSION or RETURN), `state` (ACTIVE, RELEASED, REVOKED, EXPIRED), `expires_utc` (null for autopilot), `last_sequence`, `ended_reason` | field | D |
| `attempt` | `mission_id`, `method_id`, `stages[]` (stage, start_s, end_s), `channels[]` (channel, valid_seconds, volume_L, status, reasons[], `checked_by` (CAPTURE_RULES or FIELD, null before check), `mean_enhancement`, `mean_enhancement_note` (reason when unknown)), `state` | field | D |
| `screening_record` | `attempt_id`, `mission_id`, `channel`, `named_use`, `decision` (VALID, LIMITED, REJECTED), `reasons[]`, `summary` (`valid_seconds`, `volume_L`, `mean_enhancement_<unit>`, `automatic_status`, `reviewed_by`) | field | D |
| `case` | `investigation_id`, `screening_record_ids[]`, `purpose`, `state` (DRAFT, INTAKE, SPECIMEN, LAB, FINDING, SEALED) | cases | D |
| `specimen` | `case_id`, `site_id`, `material`, `method_id`, `container`, `seal_id`, `mass_mg`, `authority_basis` (always INSPECTOR_DEMO), `current_holder` | cases | D |
| `custody_event` | `specimen_id`, `from_holder`, `to_holder`, `seal_state` (INTACT, BROKEN, MISSING), `event_time_utc`, `recorded_utc`, `exceptions[]` | cases | D |
| `lab_result` | `specimen_id`, `method_id`, `raw` (gross_ug, blank_ug, mass_mg, dup_a_pct, dup_b_pct, spike_added_ug, spike_recovered_ug), `computed` (net_ug, percent_w_w, rpd_pct, recovery_pct), `qc_status`, `qc_reasons[]`, `reporting` (REPORTED or BELOW_LOQ) | cases | D |
| `finding` | `case_id`, `outcome` (SUPPORTED_WITHIN_SCOPE, REJECTED_FOR_USE, INCONCLUSIVE), `cited_refs[]`, `alternatives[]`, `limitations[]`, `legal_suitability` (always NOT_ASSESSED) | cases | D |
| `board_decision` **v2** | `case_id`, `decision` (MORE_INVESTIGATION, NO_SUPPORTED_SOURCE, CANDIDATE_SUPPORTED_SIMULATED, SAMPLE_INVALID), `rationale`, `notice` (`area_ids[]`, area text, interval, status_text) | ops | D |
| `export` | `case_id`, `case_revision`, `manifest_sha256`, `members[]` (path, length, sha256), `signature` (F), `state` | cases | D |
| `verification_report` | `export_id`, `axes` (integrity, key_trust, lineage, trusted_time, custody_documentation, measurement_qualification, legal_suitability), `reasons[]` | cases | D |
| `model_version` **v2** | `model_kind` (LEARNED_RANKER, NORMAL_AIR), `version`, `parent_id`, `state` (ACTIVE, RETIRED, REJECTED_BY_GATE), `trained_on` (synthetic_nights, case_examples, real_hours, note), `trained_where` (MAC_CPU, DGX_A100), `test_results` (counts only, see section 11), `real_checks[]` (dataset, what, result_text), `gate` (passed, reason), `lessons[]` (case ids used and case ids skipped with reason), `file` (path, sha256) | ai | D |
| `wording` **v2** | `target` (type, id, revision), `purpose` (EXPLANATION, FINDING_SUMMARY, SMS_SMOKE_WARNING, SMS_BOARD_UPDATE), `text`, `source` (LLM or TEMPLATE), `model`, `prompt_version`, `checks[]` (check, passed), `fallback_reason` | ai | D |
| `alert_subscription` **v2** | `phone_masked`, `area_ids[]`, `consent_text_version`, `consent_utc`, `state` (ACTIVE, REMOVED, PURGED), `expires_utc` | alerts | D |
| `notification` **v2** | `kind` (SMOKE_WARNING, BOARD_UPDATE, TEST), `area_ids[]`, `text`, `wording_id`, `cause` (type, id, revision), `recipients`, `sent`, `failed`, `state` (QUEUED, SENDING, SENT, PARTIAL, FAILED, SUPPRESSED), `suppressed_reason` (RATE_LIMIT, DAILY_CAP, NO_SUBSCRIBERS, GATEWAY_OFF), `gateway_mode` (REAL or LOG_ONLY) | alerts | D |
| `automation_settings` **v2** | `auto_propose`, `auto_autopilot`, `autopilot_wait_s`, `auto_capture`, `auto_seal`, `auto_alerts`, `auto_retrain` (all on by default; wait 60 s) | core | D |
| `event` **v2** | `cursor`, `event_type`, `record_type`, `record_id`, `revision`, `sim_time_s`, `wall_utc`, `actor` | core | D |
| `receipt` **v2** | see section 3 (now with `actor` and `automatic`) | core | D |

`proposal.result = PENDING` means the planner used its 2 s budget. `best_action_id` and `cheaper_action_id` are null; nothing is recommended. Operations shows "still working it out" and must not propose a mission from that proposal.

### Analysis outcomes (science sets them, nobody overrides them)

| Outcome | Meaning on screen |
|---|---|
| `INVALID` | Not enough usable data to analyse |
| `NO_SIGNAL` | Usable data, but nothing above the floor. This does NOT mean the factories are clean. |
| `UNKNOWN_OR_MISMATCH` | None of the registered factories explains the data. Maybe an unregistered source. |
| `AMBIGUOUS_OR_OPTIONAL_MIXTURE` | More than one explanation still fits |
| `PROVISIONAL_SINGLE` | One registered factory explains the data best, inside this synthetic model only |

Each entry in `candidates[]` has `explanation_id` (like `S02`, `S01+S02`, `BACKGROUND`, `UNKNOWN`), `sources[]`, `fit` (normalised error, lower fits better, `null` for UNKNOWN), `status` (SURVIVES or REJECTED) and `reason`. The UNKNOWN row is always present. Example values for every record are in `apps/shared/fixtures/`.

`learned_order[]` lists only registered explanations with status SURVIVES, ranked 1, 2, 3 and so on. `order_score` is an ordering number, not a probability: screens show the rank, never the score as a percentage or a chance. The UNKNOWN row is never ranked and is always shown next to the order. If the outcome is INVALID or NO_SIGNAL, `learned_status` is NOT_APPLICABLE and the order is empty.

`qualification` is always `MODEL_NOT_QUALIFIED` or `SYNTHETIC_SCOPE_ONLY` in our builds. A fit is a fit number, never a probability, and the screen must never show it as a percentage chance.

## 7. Pilot input (phone, keyboard and autopilot)

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

**Autopilot (v2).** The autopilot is a server-side pilot. It holds a lease with `holder_session` = `autopilot` and produces the same kind of velocity input internally, inside the same limits (speed, acceleration, area, 2 m clearance, energy). It flies the mission `route[]` to the target, holds position inside the height band while the capture runs, then returns to the depot.
- With `auto_autopilot` on, the server starts it by itself when a mission is APPROVED and no person has taken the lease for `autopilot_wait_s` (60 s).
- A visitor or operator can take over at any time except while the mission is COLLECTING: the drone stops first, then the lease moves. When their lease ends, the drone stops (dead man), and after 10 s with no pilot the autopilot resumes if it is still enabled.
- If the energy left would not cover the return trip plus the 12 Wh reserve, the server sends `return_to_depot` with reason BATTERY_RESERVE, whoever is flying.

## 8. State machines

```
run:          CREATED -> RUNNING <-> PAUSED ; RUNNING or PAUSED -> COMPLETED | ABORTED
              capacity used up -> complete_run (automation, WORLD_CAPACITY) -> COMPLETED
              (reset makes a NEW run; RunEnded(COMPLETED) stops the drone and revokes leases)
pairing:      CREATED -> REDEEMED -> CONFIRMED ; CREATED or REDEEMED -> EXPIRED
              code lives 120 wall seconds; redemption and confirmation refuse expired codes
              confirmation adds the visitor to the mission queue; run end expires unconfirmed codes
mission:      PROPOSED -> APPROVED -> LEASED -> OUTBOUND -> AT_TARGET -> COLLECTING
              -> RETURNING -> COMPLETE
              any state before COMPLETE -> INTERRUPTED | EXPIRED ; PROPOSED -> REJECTED
              wind or analysis change after approval -> EXPIRED, needs a NEW approval
              (pilot can change between VISITOR, OPERATOR and AUTOPILOT without changing state)
attempt:      COLLECTING -> COMPLETE | FAILED | INTERRUPTED   (a retry is a NEW attempt)
case:         DRAFT -> INTAKE -> SPECIMEN -> LAB -> FINDING -> SEALED
custody:      append only; corrections are new events, never edits
model_version: (new) -> ACTIVE | REJECTED_BY_GATE ; ACTIVE -> RETIRED when a newer one becomes ACTIVE
notification: QUEUED -> SENDING -> SENT | PARTIAL | FAILED ; QUEUED -> SUPPRESSED
subscription: ACTIVE -> REMOVED | PURGED
```

## 9. Interfaces (Java)

These live in `server/src/main/java/fuelprint/api/`. The core ships simple stub versions of the science, ai and alerts services below (returning fixture-shaped answers, `@Profile("stub")`), so every group can build before the real parts are finished.

```java
// science implements these
public interface ObservationProvider {
    // Readings at each fixed sensor and each vehicle inlet for this tick.
    ReadingBatch observe(double simTimeS, List<InletPose> inlets);
}
public interface WorldClock   { void step(double dtS); WindNow wind(); }
public interface Detector {
    default List<Window> windows(DetectorInput input) { return List.of(); }
    Optional<Trigger> evaluate(DetectorInput input);
}
public interface Ranker       { Analysis analyze(AnalysisInput input); }
public interface Planner      { Proposal plan(Analysis analysis, PlanContext context); }
public interface CaptureRules {
    ChannelVerdict judge(CaptureInput input);
    default List<Window> windows(CaptureInput input) { return List.of(); }
}

// field implements this; science's ranker reads vehicle inlet tracks through it
public record InletSample(double simTimeS, Enu inlet) {}
public interface InletHistory {
    Optional<List<InletSample>> track(String runId, String sensorOrVehicleId, double startS, double endS);
}

// field optionally forecasts hypothetical missions; science consumes public samples/stages
public interface ProspectiveInlets {
    Optional<Forecast> forecast(PlanContext context, VehicleState vehicle, Enu target,
                               java.util.function.BooleanSupplier budgetExpired);
    record Forecast(List<InletSample> samples, List<Stage> stages,
                    double completeSeconds, double energyWh) {}
}

// science publishes this Spring event; core listens through the api type
public record WorldCapacityReached(String runId, String reason, double simTimeS, int puffCount) {}

// ai implements these (v2). They only receive public data.
// LearnedOrder carries learned_order, learned_status, the model version id and history_counts
// (the ai package reads earlier runs' public analyses itself).
public interface LearnedRanker  { LearnedOrder order(AnalysisInput input, Analysis physics); }
public interface NormalAirModel { Optional<ExpectedBand> expected(String sensor, String channel, double simTimeS, WindNow wind); }
public interface WordingService { Wording write(WordingRequest request); }   // never throws; falls back to a template

// alerts implements these (v2). SmsGateway is LOG_ONLY in tests, on teammates' laptops and in fake mode.
public interface AlertAreas { List<String> downwindOf(String sensorLabel, WindNow wind); }   // section 13 rule
public interface SmsGateway { SendResult send(String phoneE164, String text); }
```

The input and output types of these interfaces (`DetectorInput`, `AnalysisInput`, `PlanContext`, `CaptureInput`, `SendResult` and the rest) are defined in `fuelprint.api` by the core; the implementing package asks Aditya Singh for any change.

`Detector.windows(input)` returns windows completed by now. Once per simulated second, core calls it before `evaluate(input)` and saves those windows in the same transaction as any trigger, so the trigger can cite them.

`CaptureRules.windows(input)` computes a background window and a complete 30-second collection window for one channel from the attempt's recorded stages, gas readings and branch-flow readings. It does not save records or make the human review decision. Both windows use server UUID v4 IDs. All contributing readings must have the same instrument, gas and origin. Unknown background, insufficient valid consecutive collection, missing or invalid flow, and unsupported channels produce no usable collection window and an explicit diagnostic reason in the capture review result.

The field `review_capture` handler calls this function only for a channel whose automatic pre-check and human decision are both VALID. It obtains the raw input from the saved reading records belonging to the mission vehicle and the attempt's stage intervals. It saves the returned windows with the screening record in the same command transaction. Repeating a command returns its existing receipt and creates no new windows. REJECTED, LIMITED, interrupted and unreviewed capture channels remain available as raw readings, attempts and screening records, but are not admitted as usable capture windows by this amendment.

For a vehicle collection window, `mean_enhancement` is the measured collection mean minus the measured BACKGROUND-stage mean. It is a temporal measurement contrast, not a claim that the background was plume-free. The collection window cites its saved background through `background_window_id`. Its uncertainty includes both averaging operations and their correlation under the declared synthetic noise model.

Science predicts this same collection-minus-background contrast, propagating the gas-specific transport, chamber and sensor response through the recorded inlet history. It must not compare a temporal contrast against an absolute steady concentration. Missing background records or required inlet history exclude the window with a reason. Analyses using vehicle contrasts declare the possible plume content of the background and the finite-history initialization assumption in `limitations[]`.


`InletHistory` returns recorded samples oldest first, covering the requested window with any needed boundary samples. An empty Optional means unknown; a present list has at least one sample. Science never guesses a vehicle's position when its track is unknown.

`ProspectiveInlets` forecasts immediate autopilot execution on a copy of the vehicle. It never moves live state or saves records. Samples through the current simulation time come from that run/vehicle's recorded history; subsequent samples are hypothetical. BACKGROUND/COLLECT stages use [start,end) intervals. The immutable result includes the complete procedure and return time/energy. Missing history, no safe path, an unsupported vehicle, insufficient remaining run duration or cancellation returns empty; science records an unavailable forecast reason and never substitutes an instant steady measurement. The planner checks its budget before the hook, field checks cancellation during trajectory generation, and science checks during response propagation. Budget expiry returns PENDING with no recommendation. Approval delays, visitor deviations, future wind changes and instrument faults remain uncertain; attribution always uses actual recorded measurements. Human mission approval and capture review remain required.

When science publishes `WorldCapacityReached`, core submits `complete_run` through `AutomationBus`, with `run_id` and reason WORLD_CAPACITY. After commit, core publishes `RunEnded(runId, COMPLETED)`. Field stops the drone and revokes its leases.

The core also gives every package `AutomationBus.submit(commandType, payload)`, which sends a command as the `automation` actor through the normal command bus. Event listeners never save records directly; they submit a command.

## 10. Scenarios

| Preset | What happens | Expected ending |
|---|---|---|
| `SC1-NIGHT-PLUME` | S02 emits, wind from 270 at 3 m/s. N2 sees a sustained SO2 rise: the alarm opens an investigation and the normal-air model flags N2 as unusual. A smoke warning SMS goes to opted-in phones in the downwind area (AREA-E). Ranking keeps S01 and S02 and the learned ranker puts them in order (whatever order the model gives; it is never scripted). The planner proposes the drone to (0, 100, 35) and automation proposes that mission. The supervisor approves. A visitor flies it, or the autopilot does after 60 s. The capture runs by itself, the QC reviewer confirms, only S02 survives. Cases records a specimen, lab result and finding. The export seals and verifies itself. The board decides, a board update SMS goes out, and the learned ranker retrains from the sealed case. | `PROVISIONAL_SINGLE` S02, finding SUPPORTED_WITHIN_SCOPE for the synthetic observation only; one new `model_version` (ACTIVE or REJECTED_BY_GATE, both are fine) |
| `SC2-BLOCKED-INLET` | Same start. At collection second 10 the SO2 branch flow drops to zero while the raw number still looks normal. | SO2 REJECTED, CO2 still VALID, finding REJECTED_FOR_USE for SO2; the retrain skips this case with the reason "SO2 rejected, not a lesson" |
| `SC3-ABOVE-THE-BOXES` | S03's hot 45 m plume passes over all 3 m inlets. No alarm, no anomaly, no warning SMS. The analyst schedules a survey. The drone (visitor or autopilot) result fits two explanations equally. | `AMBIGUOUS_OR_OPTIONAL_MIXTURE` or `NO_USEFUL_MEASUREMENT`, finding INCONCLUSIVE; the retrain skips it with the reason "inconclusive, not a lesson" |

Finale adds: wind turning mid-mission, two factories at once, a sensor fault, an unregistered source, a weak plume with drift (where the normal-air model notices what the fixed alarm rule misses), and the combined-error case from question FP92.

## 11. The AI pieces (v2)

| # | Piece | Kind of AI | Package |
|---|---|---|---|
| AI-1 | Smoke-spread reasoning | Physics model plus constrained best fit (inverse modelling) | `fuelprint.science` |
| AI-2 | Learned ranker | Supervised machine learning, trained on the DGX A100 | `fuelprint.ai` (run time), `tools/train/` (training) |
| AI-3 | Planner | Decision-making search over possible measurements | `fuelprint.science` |
| AI-4 | Learning loop | Retraining from closed cases with an automatic quality gate | `fuelprint.ai` |
| AI-5 | AI wording | An existing chat model (Claude Haiku 5.5 through the Anthropic API) | `fuelprint.ai` |
| AI-6 | Normal-air model | Machine learning on real Indian SO2 measurements | `fuelprint.ai` (run time), `tools/train/` (training) |
| AI-7 | Autopilot | Automatic flight control along the approved route | `fuelprint.field` |

**AI-1 Smoke-spread reasoning (FP-COMPAT-3).** Unchanged from version 1: 6 singles and 15 pairs, 27 weather variations each, non-negative least squares, cutoff from separate calibration runs, the five outcomes, the UNKNOWN row. Its smoke-spread maths is also checked against real tracer field experiments (section 14); the results go into `model_version.real_checks`.

**AI-2 Learned ranker (FP-LEARN).**
- Input: only public data: the windows used by the analysis, the wind, the sensor and factory positions and heights from `world-profile.json`, the AI-1 fit of every explanation, and `history_counts` (earlier runs in this database where each factory survived, the "past nights" signal).
- Output: `learned_order[]` for the surviving registered explanations (see section 6).
- Training data: random simulated nights made by the science engine's night generator: random emitting factory, pair, unregistered source or none; random winds drawn from the real Kundli wind history; random sensor faults. The labels (which factory really emitted) come from hidden truth and exist only in `data/training/`. The settings and seeds of SC1, SC2 and SC3 are never used for training.
- Split: train, validation and test by seed and by emitting factory, so the model can't pass by memory.
- Where: version 1 trains on the Mac CPU (gradient-boosted trees) for the department round. Version 2 trains on the KIET DGX A100 (a neural network on far more nights) for the finale. Both are exported as ONNX and run in Java with ONNX Runtime.
- `test_results` are counts on test nights never used in training: `nights`, `first_correct`, `in_top_3`, `wrong_when_no_source`, `wrong_when_unregistered`, `misses`. Shown as counts, like "first in N of M test nights", never as a percentage on a candidate.

**AI-3 Planner (FP-PLAN-INTERVAL-1).** Unchanged from version 1. With `auto_propose` on, automation proposes the planner's `best_action_id` as a mission straight away; a person still approves.

**AI-4 Learning loop.**
- When a case reaches SEALED and `auto_retrain` is on, automation sends `retrain_model`. A person can also press "Retrain".
- A case is a lesson only if its finding is SUPPORTED_WITHIN_SCOPE and its lab result has qc_status PASS. The label is the specimen's `site_id`. Every other case is listed in `lessons[]` as skipped, with the reason.
- The new version is trained from the active version plus all lessons so far. It is tested on the same held-out test nights. **Quality gate:** it becomes ACTIVE only if it is not worse than its parent on `first_correct` by more than 2 nights; otherwise it is saved as REJECTED_BY_GATE and the old one stays ACTIVE. Both results are normal and shown.
- No hidden truth is ever used here, only human findings and lab results.

**AI-5 AI wording.**
- Automation sends `write_wording` after an analysis, a finding, and before each SMS. The chat model gets only the public text of the target record (never truth, never phone numbers) and writes plain words.
- Checks before the text is used: every number in the output appears in the input; no factory label S01 to S06 in any SMS; none of the banned words (guilty, illegal, proved, coal, probability, chance, %); SMS at most 160 characters.
- If any check fails, there is no internet, no key (`server/config/llm.json`, not committed) or no answer within 4 s, the server uses the fixed template text and records `fallback_reason`. The deterministic text is always kept and can always be shown.
- The wording never changes a number, a status, an outcome or a decision.

**AI-6 Normal-air model (FP-NORMAL).**
- Trained on real hourly SO2 from the CPCB station at Murthal, Sonipat (or the nearest station with usable SO2 if Murthal's data can't be downloaded), joined with Open-Meteo wind for the same hours. It learns the normal SO2 band (low and high) for each hour of day, month and wind.
- In the simulation, the background SO2 level and its daily pattern follow that real station (the readings are still synthetic and labelled so).
- At run time it compares each box's one-minute averages with the band. Five minutes in a row above the band makes an `anomaly` (sent by automation with `record_anomaly`) with `suggestion` SCHEDULE_SURVEY. The analyst decides whether to schedule a survey.
- It never fires the alarm. The alarm stays the fixed rule TEACH-DETECT-1. An anomaly is a hint.

**AI-7 Autopilot.** See section 7. It follows the approved route only; it never picks a target by itself.

## 12. Automation (v2)

The whole chain. AUTO = sent by `automation`. HUMAN = only a person.

| Step | Who | Command |
|---|---|---|
| Boxes report every second | AUTO | (world loop) |
| Alarm fires on the fixed rule | AUTO | `trigger` record by science |
| Normal-air model flags unusual air | AUTO | `record_anomaly` |
| Investigation opens | AUTO | `open_investigation` |
| Ranking, learned order, plan | AUTO | `run_analysis` |
| Plain-words explanation | AUTO | `write_wording` |
| Smoke warning SMS to downwind areas | AUTO | `send_alert` (SMOKE_WARNING) |
| Mission proposed | AUTO | `propose_mission` |
| Mission approved or rejected | HUMAN | `approve_mission` / `reject_mission` |
| Approval expires when wind or analysis changes | AUTO | `expire_mission` |
| Drone flies | AUTO (autopilot) or a visitor | `start_autopilot` or a lease |
| Battery reserve return, dead man stop | AUTO | `return_to_depot`, server safety loop |
| Capture stages and blocked-tube detection | AUTO | `start_capture`, CaptureRules |
| Channel pre-check | AUTO | `attempt.channels[].status` |
| Channel review | HUMAN | `review_capture` |
| Ranking updates with the new sample | AUTO | `run_analysis` |
| Lab maths and QC | AUTO (on the lab person's numbers) | `request_lab` |
| Finding | HUMAN | `revise_finding` |
| Evidence sealed and verified | AUTO | `seal_export`, `verify_export` |
| Board decision | HUMAN | `record_board_decision` |
| Board update SMS | AUTO | `send_alert` (BOARD_UPDATE) |
| Learned ranker retrains | AUTO | `retrain_model` |
| Phone numbers deleted at midnight | AUTO | `purge_subscriptions` |

- Each AUTO step can be switched off in `automation_settings` (presenter, on the AI and automation page), so the demo can show the same run done by hand.
- The **automation log** is the list of receipts with `automatic: true`, in time order, with plain words. Every app can show it (`AutomationLog` in `@fp/shared`).
- Automation never retries a REJECTED command in a loop. It records the rejection and stops that step.

## 13. Citizen SMS alerts (v2)

- **Areas.** Four fictional residential areas around the estate (`contracts/reference/alert-areas.json`): AREA-W West village, AREA-N North colony, AREA-E East colony, AREA-S South village.
- **Sign-up.** A visitor scans the stall QR, opens `/alerts/`, enters a mobile number, picks one or more areas and ticks a consent line ("Send me demo SMS alerts from the FuelPrint stall today. My number is deleted tonight."). This sends `subscribe_alerts`.
- **Smoke warning.** When `open_investigation` runs for a trigger, ops asks `AlertAreas.downwindOf(sensor, wind)` for the areas whose centre lies within ±30° of the downwind direction (wind_from + 180°) from the triggering box and within 2 km, and saves them in `investigation.warned_area_ids`. A survey without a trigger warns no area. The alerts package listens for that investigation and automation sends one SMOKE_WARNING to the active sign-ups of those areas.
- **Board update.** When a board decision is saved, automation sends one BOARD_UPDATE to the sign-ups of `notice.area_ids`, with text that matches the decision: acted on (CANDIDATE_SUPPORTED_SIMULATED), still investigating (MORE_INVESTIGATION), no source confirmed and monitoring continues (NO_SUPPORTED_SOURCE), sample could not be used and investigation continues (SAMPLE_INVALID).
- **Text rules.** At most 160 characters, starts with "FuelPrint DEMO (simulated):", names no factory, uses the AI wording only if it passes the checks in AI-5.
- **Limits.** At most 1 SMOKE_WARNING per area per 10 wall minutes, and at most `daily_cap` (default 80) SMS per day in total. Over a limit the notification is saved as SUPPRESSED with the reason, never silently dropped.
- **Gateway.** One teammate's Android phone with a SIM runs the open-source "SMS Gateway for Android" app in local server mode on the stall Wi-Fi. Settings in `server/config/sms.json` (not committed): `mode` (LOG_ONLY or REAL), `gateway_url`, `username`, `password`, `daily_cap`. Default LOG_ONLY: the message is written to the log and the screen, nothing is sent. Only the host at the stall uses REAL. A failed send is retried twice, then counted as failed.
- **Privacy.** See ground rule 10. A test checks that no phone number appears in any record, event, export or prompt.

## 14. Real data we use (v2)

| Data | Real or synthetic | Source | Used for |
|---|---|---|---|
| Kundli building outlines | Real | Overture Maps (Google Open Buildings, Microsoft, OpenStreetMap), `data/context/` | The 3D context buildings |
| Kundli hourly wind, Nov 2025 to Jan 2026 | Real (computer-modelled weather) | Open-Meteo, `data/context/` | Random winds for the training nights; the normal-air model |
| Hourly SO2 at CPCB Murthal, Sonipat | Real measurements | CPCB continuous monitoring station (CPCB portal or OpenAQ), `data/real/cpcb/` | Training the normal-air model; the background SO2 pattern in the simulation |
| Tracer field experiments with known sources: Kincaid (187 m stack), Copenhagen (115 m), Indianapolis, Lillestrøm, Prairie Grass | Real measurements | Model Validation Kit (harmo.org) and the Irwin tracer archive (harmo.org/jsirwin), `data/real/tracer/` | Checking the smoke-spread maths (department) and the learned ranker (finale) against real air |
| Factories S01 to S06, their emissions, every sensor and drone reading, lab results, cases | Synthetic | Our simulation | Everything in the demo |

- There is no public dataset that links Indian factories to sensor readings, so the learned ranker trains on simulated nights. We say so.
- Real data folders are downloaded by `tools/data/fetch_real.sh`, not committed, and keep their source notes and licences.
- A number from a real dataset and a synthetic number are never put into one calculation without saying so in the record.

## 15. Ownership of folders

| Folder or package | Owner | Builder |
|---|---|---|
| `contracts/`, `AGENTS.md` | Aditya Singh | Claude |
| `server/.../fuelprint/core`, `.../api`, `tools/` | Aditya Singh | Kiro |
| `server/.../fuelprint/science` | Aditya Singh | Kiro |
| `server/.../fuelprint/ai`, `tools/train/` | Aditya Singh | Kiro |
| `server/.../fuelprint/alerts`, `apps/alerts` | Aditya Singh | Kiro |
| `server/.../fuelprint/field`, `apps/field` | Aditya Singh | Kiro (code) and Claude (3D look) |
| `server/.../fuelprint/ops`, `apps/operations` | Aditya Singhal (Abhivansh: health panel, hardware page, AI and automation page) | Their Codex |
| `server/.../fuelprint/cases` | Antara | Her Codex |
| `apps/cases` | Aditya | His Codex |
| `apps/shared` | Aditya Singh | Kiro (Claude reviews the look) |
