# Build plan for the AI side (Claude orchestrator, Codex, Cortex, Kiro)

Written 16 Oct 2026 at the end of the planning chat. This is the working manual for the chat that runs the build on Aditya Singh's Mac. The teammates' guides are in `docs/plan/`. This file covers the parts the AI tools build themselves: the shared core, the science engine and Field, plus how Claude runs and checks everything.

## 0. Read first, in this order

1. `contracts/AGREEMENT.md`: the contract. Only Claude edits it, and only when Aditya Singh agrees.
2. `AGENTS.md`: rules and folder ownership for every agent.
3. `docs/plan/01-team-plan.md` and `docs/plan/04-field-plan.md`: what we promised the team and when.
4. `docs/reference/r3/03-SCIENCE-FIELD-AND-PHONES.md`, `04-CASES-LABORATORY-AND-EVIDENCE.md`, `02-CONTRACTS-DATABASE-AND-RECOVERY.md`: the detailed research design (Codex's R3, 15 Oct). Where it conflicts with AGREEMENT.md, AGREEMENT.md wins for the department build. It was written for a Python backend; we build in Java.
5. `contracts/reference/world-profile.json` and `method-profiles.json`: exact numbers.

## 1. Who works where

| Worker | How to run it | Works on |
|---|---|---|
| Claude (this chat) | Orchestrates, writes briefs, reviews, merges, does visual design | `contracts/`, `apps/shared` look, Field look, PR reviews |
| Codex (model `gpt-6.1-sol`, effort `high` from `~/.codex/config.toml`) | `codex exec -s workspace-write -C <worktree> -o OUT.md - < BRIEF.md` in the background. Continue: `codex exec resume --last -c sandbox_mode='"workspace-write"' -o OUT.md - < NEXT.md` (`resume` does NOT take `-s`). | Shared core, Field code, start scripts |
| Snowflake Cortex Code v1.1.104 | `cortex exec -w <worktree> --effort high --max-turns 200 --file BRIEF.md` (connection `account1`, account FWIHMOS-CC12847, user ADITYA65, role SYSADMIN) | Science engine. Use it as much as possible. |
| Kiro | See memory `kiro-cli-driving` (`kiro-cli chat --no-interactive --model claude-opus-5.5 --trust-tools=fs_read,fs_write,execute_bash "..."`; Opus 5.5 uses 2× credits) | Backup when Codex or Cortex hit limits; test writing |
| Teammates | Their own Codex with the prompts in their guides | ops, cases backend, cases screens |

**Before giving Cortex heavy work**, check the Snowflake account's credit allowance (ask Aditya Singh or query `SNOWFLAKE.ACCOUNT_USAGE.METERING_DAILY_HISTORY` with `snow sql`). Tell him the number before you start.

**Never run two agents in the same folder.** Use git worktrees:

```
cd ~/dev/fuelprint-innotech
git worktree add ../fp-wt/core    -b core/build
git worktree add ../fp-wt/science -b science/build
git worktree add ../fp-wt/field   -b field/build
```

Each agent gets its own worktree. Claude merges branches into `main` after tests pass. `gh` on this Mac is an x86_64 build whose git subprocess fails, so use `gh` only for API calls (PRs, collaborators) and run every git command with native `git`.

## 2. Calendar

| Day | Codex | Cortex | Claude |
|---|---|---|---|
| Sat 17 Oct | K1 to K8 core, merged to `main` by 6 PM. That's the hard deadline: teammates start then. | S1 world and puffs, S2 sensor and transport model | Shared look (tokens, components) in `apps/shared`; announce "core ready" message for WhatsApp |
| Sun 18 Oct | F1 field backend, F2 3D scene, F3 drone and keyboard | S3 detector, S4 ranker | Field visual direction; review teammate PRs (evening) |
| Mon 19 Oct | F4 pairing, lease and phone page, F5 capture stages, F6 QC review screen | S5 planner, S6 scenarios SC1 to SC3, S7 record replay files from real runs | Review PRs; end-to-end API script for the 3 scenarios |
| Tue 20 Oct | Fixes | Fixes | Join day: switch off stubs, run all scenarios, triage mismatches |
| Wed 21 Oct | Fixes | Fixes | Rehearsal checklist, tag `dept-v1` at 10 PM, backup host copy |
| 25 to 29 Oct | Finale items | Finale scenarios, evaluation runs | Finale review |

Aditya Singh is at IBM Z on 17 and 18 Oct. Keep his Mac awake (`caffeinate -dimsu &` while agents run).

## 3. Shared core (Codex), steps K1 to K8

Package `fuelprint.core` and `fuelprint.api`, migrations `V001` to `V099`.

- **K1 Project skeleton.** Maven wrapper, Spring Boot 3.5, Java release 17, packages `core, api, science, ops, cases, field` (empty `package-info.java` with owner comments). SQLite via `org.xerial:sqlite-jdbc`, WAL mode, `PRAGMA synchronous=FULL`, `foreign_keys=ON`. Flyway. JdbcTemplate. Jackson with snake_case. ArchUnit test: nothing outside `fuelprint.science` imports `fuelprint.science.truth`.
- **K2 RecordStore and events.** Generic revision tables (`records(type, id, revision, run_id, json, created_utc, created_by)`, latest view), `events(cursor autoincrement, run_id, event_type, record_type, record_id, revision, sim_time_s, wall_utc)`, `commands(command_id unique, actor, type, payload, receipt_json)`. Blob store `data/runtime/blobs/<sha256>` with exact bytes.
- **K3 CommandBus.** `POST /api/commands`. Per command type a registered handler `(Command, Session, Store) -> HandlerResult(records, events) or Rejection`. Bus checks role, `expected_revision`, idempotency (same `command_id` returns the stored receipt; same id with a different payload is rejected), runs the handler, then saves records, events and the receipt in ONE transaction, then publishes Spring events and broadcasts. One single-threaded executor serialises all commands.
- **K4 Clock and runs.** Run lifecycle commands (`create_run`, `pause_run`, `resume_run`, `reset_run`). World loop at 20 Hz (dt 0.05) on its own thread calling `WorldClock.step`, `ObservationProvider.observe` for sensors N1 to N4 and for vehicle inlets; 1 Hz reading records; 10 Hz `tick` broadcast. Time scale field (1 or 15 for stationary capture).
- **K5 Live socket.** `/ws/live?run_id&after=cursor`: `hello` with `server_epoch`, replay of events after the cursor from the database (or a "reload snapshot" signal if too old), then live events and ticks. Bounded per-client queue: drop superseded ticks, never drop events; close slow clients. `GET /api/snapshot`, `/api/runs/current`, `/api/records/{type}/{id}`, `/api/records/{type}?run_id=`.
- **K6 Sessions and fake mode.** Station login with PIN from `server/config/stations.json` (commit `stations.example.json`), HttpOnly SameSite cookie, Origin check on sockets. Visitor sessions via pairing (Field implements pairing; core provides the session type). **Fake mode** `--fp.mode=replay`: plays `server/src/main/resources/replay/SC1.ndjson`, `SC2`, `SC3` as if live (events plus ticks). On Saturday these files are hand-scripted from `apps/shared/fixtures` values following the scenario story in AGREEMENT section 10. On Monday S7 replaces them with recorded real runs.
- **K7 Stubs and shared front-end library.** Stub implementations of every interface in AGREEMENT section 9 that return fixture-shaped answers (`@Profile("stub")`, default until join day). `apps/shared`: npm workspace package `@fp/shared` with TypeScript types for every record and message, `useLive()`, `useSnapshot()`, `sendCommand()`, `StaleBanner`, `Explain`, `OutcomeBadge`, and the station login form. npm workspaces at `apps/package.json` with `operations`, `cases`, `field`, `shared`, each a Vite + React 18 + TS app with Vitest. Vite dev server proxies `/api` and `/ws` to 8080.
- **K8 Scripts and serving.** `tools/start-fake.sh`, `start-real.sh`, `dev-app.sh <app>`, `test-all.sh`, `stall.sh` (+ `.ps1` versions). Spring Boot serves the built apps at `/operations/`, `/cases/`, `/field/`, `/field/controller`, binding `0.0.0.0:8080`.

**Core done when:** `tools/test-all.sh` passes; fake mode plays SC1 and an app frame sees live ticks; a command retried with the same id applies once; a stale revision is rejected; killing the server mid-run and restarting keeps every committed record and marks the run INTERRUPTED (paused, leases revoked).

## 4. Science engine (Cortex), steps S1 to S7

Package `fuelprint.science` (public API classes) and `fuelprint.science.truth` (hidden generator state). Migrations `V100` to `V199`. Numbers from `world-profile.json`, `method-profiles.json` and R3 doc 03.

- **S1 World and puffs.** ENU metres, origin 28.87 N 77.12 E. Sources S01 to S06 with effective heights. Each active source emits a puff per tick: mass = q × 0.05 s per species (SO2 0.5 g/s, CO2 500 g/s nominal, per scenario). Puff variance starts 4 m², grows by 2K × age (Kx = Ky = 3, Kz = 1 m²/s). Reflected Gaussian over z ≥ 0. Puffs keep source id, age, mass and their own wind history; wind changes advect existing puffs; switching a source off never deletes puffs. Default wind from 270° at 3 m/s, 298.15 K, 101325 Pa; molar masses 64.066 and 44.0095 g/mol for ppb/ppm conversion. Caps: live 900 observed sim seconds and 160,000 puffs (pause and refuse beyond). Rendering never removes puffs.
- **S2 Sensors and transport.** Background 5 ppb SO2 and 420 ppm CO2 with a shared 1 Hz AR(1) (rho 0.95, innovation SD 1, loadings 1 ppb and 0.5 ppm). Per-channel post-response AR(1) noise rho 0.8, SD 5 ppb and 2 ppm. Inlet line 1.5 m × 4 mm ID (18.85 mL), transport tracked by actual sampled volume, flows 0.5 (SO2) and 0.3 (CO2) L/min. Chamber mixing 8 s. First-order response `new = old + (target - old) * (1 - exp(-dt/tau))`, tau = T90 / ln 10, T90 60 s SO2 and 30 s CO2. Failed flow keeps the stale value and raw number, adds no valid volume, sets FLOW_FAULT. Implements `ObservationProvider` and `CaptureRules` (settle error ≤ 5 % after ≥ 120 s, 30 consecutive valid seconds, flow within ±5 %, recovery ≤ 5 %).
- **S3 Detector TEACH-DETECT-1.** Valid 30 s background of the same channel no older than 180 sim s, else BACKGROUND_REQUIRED. Trigger after 15 consecutive valid 1 s enhancements, each ≥ max(25 ppb, 3 × standard uncertainty). Invalid, gapped, stale or calibration-failed seconds reset the count. CO2 alone never triggers. Emits `window` and `trigger` records.
- **S4 Ranker FP-COMPAT-3 (Java port).** Inputs: latest ≤ 6 complete non-overlapping 30 s valid windows per instrument channel, up to 60 entries. Kernel for x > 0 alongwind: sy² = 4 + 2Ky·x/u, sz² = 4 + 2Kz·x/u, `C = exp(-l²/2sy²) / (2π u sy sz) · [exp(-(z-H)²/2sz²) + exp(-(z+H)²/2sz²)]`, 0 for x ≤ 0, evaluated along the recorded inlet history through the same line, chamber and sensor response and the same 30 s window. Explanations: 6 singles + 15 pairs, each with 27 nuisance members (direction −10/0/+10°, speed ×0.8/1/1.2, height −10/0/+10 m). Non-negative least squares per gas (Lawson-Hanson, Apache Commons Math for SVD and Cholesky), whitened by the covariance; persist SSE, rank and failures. Cutoff computed from separate calibration episodes (m ≥ 40, rank min(m, ceil(0.95(m+1)))); the historical 132.253985 is forbidden. Outcomes: INVALID, NO_SIGNAL, UNKNOWN_OR_MISMATCH, AMBIGUOUS_OR_OPTIONAL_MIXTURE, PROVISIONAL_SINGLE; qualification SYNTHETIC_SCOPE_ONLY. UNKNOWN row always present. Writes a deterministic plain `explanation_text`. Oracle tests from R3 doc 03 ("Independent oracles"): proportional columns [1,2]/[2,4] with y [3,6] both fit exactly; identity 2×2 with y [4,3] gives pair SSE 0 and singles 9 and 16; swapping hidden labels with identical public input gives identical analysis.
- **S5 Planner FP-PLAN-INTERVAL-1.** ≤ 8 actions: points (0,100,3), (120,100,3), (280,140,3) [the last is UNREACHABLE for the van], aerial (0,100,35), (120,100,35), depot background, instrument QC. Filter first: unreachable, unsafe, no energy for return, stale dependencies, unaffordable full procedure; keep rejected actions with reasons. For admitted fits, prospective interval per explanation, conservative boxes over nuisance members, separation lower bound minus supported error, clipped at 0, minimum over materially different explanation pairs, divided by complete minutes. All zero means NO_USEFUL_MEASUREMENT. Ties: higher score, shorter time, lower energy, action id. 2 s wall budget; over budget returns pending, never a fake recommendation. Return best plus a cheaper alternative.
- **S6 Scenarios.** Presets SC1-NIGHT-PLUME, SC2-BLOCKED-INLET, SC3-ABOVE-THE-BOXES exactly as AGREEMENT section 10, with hidden truth in `science.truth` and fixed seeds. Check SC1 actually produces a trigger at N2 and that the drone at (0,100,35) separates S01 from S02; tune emission strength (not the rules) until the story holds, and record the tuning.
- **S7 Replay recording.** Headless runner that plays each scenario with a scripted drone path and writes `server/src/main/resources/replay/SC*.ndjson` for fake mode. Also an evaluation runner (finale): many seeds per scenario, report every outcome including misses.

## 5. Field (Codex code, Claude look), steps F1 to F7

Package `fuelprint.field` (migrations `V400` to `V499`) and `apps/field`. Numbers in `docs/plan/04-field-plan.md`.

- **F1 Vehicles.** Drone kinematics at 20 Hz with speed 3 m/s, acceleration 1 m/s², bounds ±450 m and 2 to 80 m, 2 m clearance from building and stack boxes (no tunnelling at 20 Hz), energy 64 Wh usable and 12 Wh reserve with hover 160 W + 10 W + speed² W. Mission state machine from AGREEMENT section 8, driven by position (AT_TARGET within 5 m and inside the height band).
- **F2 3D scene.** Three.js directly (no R3F). `(east, up, -north)` in one function. Context crop: ≤ 300 extrusions at 8 m from `data/context/derived-scene-crop.json`, instanced, shared materials. Factories S01 to S06, boxes N1 to N4, roads, depot, wind arrow and drift specks, reading trail. No plume from true sources. DPR cap 1.5, ≥ 30 FPS on Aditya Singh's Mac. Claude sets the look: night estate, the IDEAVERSE palette (velvet green #1E3B32, ivory #F4F2EC, sage #DCE5DD, ink #16211D, clay #B8663E) adapted for a dark scene.
- **F3 Keyboard flying.** W A S D, R and F, Space stop; field_operator lease; take over.
- **F4 Pairing, queue, lease, phone page.** `create_pairing` (6-character code + two-word phrase, 120 s), `confirm_pairing`, queue, `acquire_lease` (2 s, renewed only by fresh valid input), `/ws/pilot` input at 10 Hz, 400 ms dead man, neutral on blur, visibility change or pointer cancel, no replay after reconnect. `/field/controller` with two touch pads, `touch-action: none` on pads only, 44 px targets. QR drawn locally (no cloud QR service); short code always shown.
- **F5 Capture stages.** `start_capture` with stages setup 30, background 30, purge 30, settle ≥ 120, collect 30 valid, recover ≥ 120, review 30; 15× only while stationary, advancing physics, response, flow, energy and stages together. SC2 fault at collection second 10. Per-channel volumes (0.25 L and 0.15 L for 30 s).
- **F6 QC review.** `review_capture` per channel; a failed rule can't be passed; emits `screening_record` per channel.
- **F7 Finale.** Cutaway, 3D lab workbench, van on roads, labelled prediction overlay, procedure score, 2D fallback.

## 6. Reviewing teammates' pull requests

For each PR (`gh pr list -R adityasinghin01-hash/fuelprint-innotech`, `gh pr diff`):

1. Files touched only in the author's folders (AGENTS.md table). Anything in `contracts/` = request changes.
2. `tools/test-all.sh` passes on a checkout of the branch (use a fresh worktree).
3. No `fuelprint.science.truth` imports, no percentages for scores, no real factory names, no outcome computed in the browser, unknown values not turned into zero.
4. Every visible number has an `Explain` line.
5. Screens checked with Playwright headless screenshots (memory `local-frontend-self-review`).
6. Merge with a normal merge commit, then post a short note in the PR.

## 7. If we fall behind

Cut in this order: van, instrument cutaway, two-word pairing phrase (keep the code), Recharts animations, the hardware page diagram. Never cut: rejected and inconclusive outcomes, persistence and restart, the hidden truth boundary, evidence verify and tamper, the phone dead man.

## 8. Open items at handoff

- Add the four teammates as collaborators when Aditya Singh sends usernames: `gh api -X PUT repos/adityasinghin01-hash/fuelprint-innotech/collaborators/<user> -f permission=push`.
- Check the Snowflake credit allowance before S1.
- `~/Desktop/KIET-InnoTech26-FuelPrint-Upload` (duplicate of Drive) still needs to go to the Trash.
- After 18 Oct, move the SentinelWard and TessNova folders on the Desktop into `Projects/`.
- Department slot (22, 23 or 24 Oct) is still unknown.
