# Build plan for the AI side (version 2, Kiro builds)

Written 18 Oct 2026. Replaces the 16 Oct manual. This is the working manual for the chat that runs the build on Aditya Singh's Mac. The teammates' guides are in `docs/plan/`. Read `contracts/AGREEMENT.md` (version 2) and `AGENTS.md` first; this file only says who builds what, when, and how Claude checks it.

## 1. Roles

| Worker | Does | How |
|---|---|---|
| Kiro (Opus 5.5, effort high) | All AI-side code: core, science, ai, alerts, field, shared screen kit, scripts, model training | `kiro-cli chat --no-interactive --model claude-opus-5.5 --effort high --trust-tools=fs_read,fs_write,execute_bash "<brief>"`, run from the job's worktree, in the background, output to a log. Continue a job with `--resume` from the same folder. |
| Claude (this chat) | Briefs, checks, merges, teammate PR reviews, the 3D look of Field | Never codes what Kiro can code. Checks every result on disk and runs the tests itself. |
| Codex | Backup if Kiro runs out of credits or stalls | See memory `codex-cli-driving` |
| Teammates | ops, cases backend, cases screens | Their own Codex with the prompts in their guides |

Kiro account: Kiro Pro, 1000 credits, resets 1 Nov. Check usage for free with `kiro-cli chat --no-interactive --trust-tools= "/usage"`. Log the number after every job.

## 2. Mac limits (checked 18 Oct)

- Apple M1, **8 GB RAM**, **about 14 GB free disk (93 % full)**. Keep downloads and training data small (under 2 GB in total). No video files.
- Java 17 is the Intel build (runs through Rosetta). ONNX Runtime must use its macOS x86_64 natives on this Mac; teammates use Windows x64.
- Python: use `uv` with Python 3.12 (`uv venv --python 3.12`). Never install into the system Python.
- `gh` is an x86_64 build whose git subprocess fails: use `gh` only for API calls; use native `git` for everything else.
- Keep the Mac awake while jobs run: wrap each job in `caffeinate -dimsu`.

## 3. Worktrees and branches

One worktree per job. Never two jobs in one folder. Claude merges into `main` with a normal merge commit after the checks in section 6.

| Job | Worktree | Branch |
|---|---|---|
| A core | `~/dev/fp-wt/core` | `core/build` |
| B real data and normal-air training | `~/dev/fp-wt/data` | `ai/data` |
| C science | `~/dev/fp-wt/science` | `science/build` |
| D ai (Java side) | `~/dev/fp-wt/ai` | `ai/build` |
| E field | `~/dev/fp-wt/field` | `field/build` |
| F alerts | `~/dev/fp-wt/alerts` | `alerts/build` |

## 4. Phases

| Phase | Job | Starts when | Builds | Done when | Target |
|---|---|---|---|---|---|
| P1 Core foundation | A | now | K1 skeleton, K2 record store + events + blobs, K3 command bus with actors, `AutomationBus`, every interface of AGREEMENT section 9 | `tools/test-all.sh` passes; repeated command applies once; stale rejected; automation refused where not listed; ArchUnit truth boundary | Mon 19, 9 AM |
| P2 Data and normal-air | B | now (parallel) | `tools/data/fetch_real.sh`, real CPCB SO2 + Open-Meteo wind + tracer kit in `data/real/`, FP-NORMAL-1 trained (Python) and exported to ONNX, background profile, model card | Real data fetched and documented (or a clear report of what could not be fetched); ONNX output matches Python on test rows | Mon 19, 9 AM |
| P3 Core rest | A | P1 merged | K4 clock and runs, K5 live socket, K6 sessions (station, join, guest) and fake replay SC1-SC3 with v2 records, K7 stubs for every interface + `@fp/shared` (types, `useLive`, `useSnapshot`, `sendCommand`, `Explain`, `StaleBanner`, `OutcomeBadge`, `AutomationLog`, login form, IDEAVERSE palette), K8 scripts and serving incl. `/alerts/`; `automation_settings` + `change_automation` | Fake mode plays SC1 and an app sees ticks; restart keeps records and marks the run INTERRUPTED; teammates' `tools/start-fake.sh` and `dev-app.sh` work | Mon 19, 6 PM (teammates start) |
| P4 Science part 1 | C | P1 merged | S1 world and puffs, S2 sensors and transport (background SO2 from the P2 profile), S3 detector, S4 ranker FP-COMPAT-3 with the oracle tests | Oracle tests pass; SC1 gives a trigger at N2 | Mon 19 night |
| P5 AI Java side | D | P1 and P2 merged | `NormalAirModel` (ONNX Runtime) + `record_anomaly` automation, `WordingService` (Anthropic API, checks, templates, `write_wording` automation), `LearnedRanker` loading any ONNX model with a deterministic fallback status MODEL_MISSING, `model_version` records from model cards, `retrain_model` + quality gate + lessons | Tests: anomaly after 5 minutes above band; wording falls back on a bad number, a banned word and no key; learned order never changes outcome or statuses; gate keeps the parent when the child is worse | Tue 20, midday |
| P6 Field backend | E | P3 merged | F1 vehicles, F4 pairing/queue/lease/`/ws/pilot`/dead man/phone page, autopilot + `return_to_depot`, F5 capture stages incl. automatic start, F6 QC review screen (plain 2D) | Drone stops within 500 ms after silence; autopilot flies SC1 route inside limits; SC2 rejects SO2 and keeps CO2 | Tue 20, evening |
| P7 Alerts | F | P3 merged | `fuelprint.alerts`: contact table, `AlertAreas`, `SmsGateway` (LOG_ONLY + REAL for SMS Gateway for Android local API), subscribe/unsubscribe/send/purge, smoke warning and board update automation, rate limit and daily cap; `apps/alerts` sign-up page | Privacy test (no number outside the contact table); SC1 warns AREA-E only; suppressed reasons saved | Tue 20, evening |
| P8 Science part 2 | C | P4 done | S5 planner, S6 scenarios tuned, S7 replay recording, **night generator** for training (random nights, labels only in `data/training/`) | SC1/SC2/SC3 end as AGREEMENT section 10; generator writes N nights with a fixed seed | Tue 20, evening |
| P9 Learned ranker v1 | B | P8 done | Generate ~20,000 nights (size-checked), train LightGBM ranker on the Mac, export ONNX, model card with test counts on held-out nights, smoke-maths check on one tracer dataset | Test counts written honestly, misses included; ONNX matches Python | Wed 21, 10 AM |
| P10 Join | all | Wed 21, 2 PM | Stubs off, real parts on, end-to-end API script for SC1-SC3 with automation on and off, fixes | All three scenarios end as specified on all screens | Wed 21, 8 PM |
| Freeze | Claude | | Tag `dept-v1`, backup copy of the host folder | | Wed 21, 11 PM |

Claude's own build work: the Field 3D scene and drone look (F2, F3 look) on Tue 20 and Wed 21, on top of P6.

Finale (25 to 29 Oct): learned ranker version 2 (PyTorch on the KIET DGX A100, one GPU), ranker test on real tracer data, weak-plume scenario for the normal-air model, the finale items in each guide.

## 5. Briefs

Each brief is a file in the session scratchpad (`fp/<phase>-BRIEF.md`) passed to Kiro as its prompt. Every brief:

1. Names the worktree and branch and says "you are the only agent in this folder".
2. Says to read `AGENTS.md`, `contracts/AGREEMENT.md` and the listed sections of this file fully first.
3. Lists exactly which folders it may edit (from the AGENTS.md table).
4. Lists the tests that must pass.
5. Ends: run the tests, commit on the branch in small commits, do not push, do not merge, final message = files changed, test counts, what is unfinished or assumed, any contract issue.

## 6. How Claude checks a job before merging

1. Read Kiro's final message, but trust only what is on disk.
2. `git diff main...<branch> --stat`: files only in the job's folders.
3. Run `tools/test-all.sh` in the worktree myself. Read the test names: every rule from the brief has a test, including failure cases.
4. Spot-read the risky code: transactions, idempotency, automation refusal, truth boundary, phone-number privacy, anything that touches money or time.
5. For screens: Playwright headless screenshots, read the images.
6. Merge with a normal merge commit, push `main`, message the group if teammates are waiting.
7. Log the Kiro credit count.

If a job fails a check, resume the same Kiro session with a short list of what to fix. After two failed rounds, hand the fix to Codex.

## 7. Teammates' pull requests

For each PR (`gh pr list -R adityasinghin01-hash/fuelprint-innotech`, `gh pr diff`):

1. Files only in the author's folders. Anything in `contracts/` or `AGENTS.md` = request changes.
2. `tools/test-all.sh` passes on a checkout of the branch (fresh worktree).
3. No `fuelprint.science.truth` imports, no percentages for scores or ranks, no real factory names, no outcome computed in the browser, unknown values not turned into zero, listeners use `AutomationBus`, human-only commands refuse automation.
4. Every visible number has an `Explain` line; automatic steps marked "done automatically".
5. Screens checked with Playwright headless screenshots.
6. Merge with a normal merge commit, then a short note in the PR.

## 8. If we fall behind

Cut in this order: van, instrument cutaway, two-word pairing phrase (keep the code), Recharts animations, hardware page diagram, AI wording (falls back to fixed text), normal-air model (the fixed alarm still works), live retraining (show the model versions only).

Never cut: rejected and inconclusive outcomes, persistence and restart, the hidden truth boundary, evidence verify and tamper, the phone dead man, the learned ranker's "order only" rule, the five human-only decisions, phone-number privacy and the midnight purge.

## 9. Open items

- Anthropic API key for AI wording (`server/config/llm.json`). Without it the fixed text is used.
- Which teammate's Android phone is the SMS sender; install "SMS Gateway for Android" on rehearsal day.
- KIET DGX A100 booking for the version 2 training (finale).
- Department slot (22, 23 or 24 Oct).
- The old Kiro P1 attempt left a skeleton in `~/dev/fp-wt/core`; job A reuses or replaces it.
