# FuelPrint two-day connected demo implementation plan

> **For agentic workers:** Use the executing-plans skill task by task. The user requested two new-chat lanes; do not launch additional workers automatically. Steps use checkboxes for execution tracking. This document was prepared without starting implementation.

**Goal:** Deliver the selected rules-based AI prototype, Antara's remaining Cases backend, a minimal connected 3D drone/readings/dashboard demonstration, functional integration and evidence-backed whole-system verification.

**Architecture:** Preserve the accepted backend as the common base. Lane A implements an honest rules-based presentation of existing physics results, then a Three.js view driven by real server ticks and the minimal connected screens. Lane B adds versioned evidence signing/offline verification and separate aerosol Cases records/QC. Lane A integrates the reviewed B delta and the compatible portions of the pinned Operations contribution, then owns final system qualification.

**Tech stack:** Java17, Spring Boot3.5, Maven wrapper, SQLite/JdbcTemplate/Flyway; React18/TypeScript/Vite/shared live client; Three.js for Field only; Java17 Ed25519 and bounded ZIP handling. No Python training, ONNX runtime installation or rented GPU in this milestone.

## Current decision and authority

On20October2026 Aditya selected the4–8h rules-based prototype and requested this complete plan, saved memory and two execution prompts. The scope is five deliverables: prototype, complete Antara backend, minimal demo, integrate everything available/relevant, and whole-system demo verification. "Adam prototype" is interpreted as AI demo prototype. This is a planning/handoff turn; no application implementation or new test jobs were started. The supplied new-chat prompts explicitly authorize their bounded execution without a second "start".

The requested parallel plan permits two coordinated new-chat workers. It does not require automatic agents in this planning session. Integration means functional composition in an isolated candidate; commit/Git merge/push/deploy/freeze remain separate gates. The current request authorizes the minimal Operations/Cases screen adaptations required to integrate and demonstrate the selected work; it does not transfer unrelated screen redesigns or claim that other contributors have delivered work. Obey any narrower explicit owner restriction discovered at execution and propose its concrete required exception before dependent changes.

## Global constraints

- Read /Users/aditya/.agents/memory/MEMORY.md and the current phase handoff, then repository AGENTS.md and contracts/AGREEMENT.md. Never edit AGENTS.md or contracts/**. Write an exact proposal for any missing public format/command/API decision; get that decision before dependent implementation. Other independent tasks continue.
- Preserve every inherited tracked/untracked/needed ignored file. The accepted backend is largely UNCOMMITTED: HEAD/main8db5244b56cc3172eba43558ab29c2a0da2b21d6 alone is not the accepted source. Do not start from a clean main checkout and lose it.
- No commit, Git merge, push, deployment, freeze, paid compute, signup, dataset upload or external teammate message. No production keys/secrets in repository/evidence/logs.
- Maximum two active actors total. Additional independent review uses a slot freed by pausing/releasing a worker; do not add a third actor. A fresh reviewer/new context must not be the author approving their own change.
- One Java/Maven job across both chats. No actor edits server/src during any Java/Maven run, including jobs in another worktree. Use the global protocol below; do not rely on process-local ownership messages.
- Existing native1265JavaPASS/101suites,429Cases tests/16families and40backend families are accepted BASELINE evidence, not qualification of changed bytes. Original full02 failure and full03 success remain preserved.
- Original V4 remains57/60raw compatibility versus8/60final exact-support. Never change scientific cutoffs, rejection/outcome policy, physical profiles or seeds to force a successful story. SC1/SC3 can remain inconclusive; SC2 invalid SO2 must remain excluded.
- No trained model/accuracy/retraining completion claim. Keep learned_status MODEL_MISSING/NOT_APPLICABLE/FAILED as appropriate; no learned_status OK or fictitious model_version for a heuristic. UNKNOWN and all physical candidate/outcome facts remain intact. Browser screens never compute scores/readings/outcomes.
- Keep five human decisions human: approve_mission, reject_mission, review_capture, revise_finding, record_board_decision. Automation cannot bypass them. SMS stays LOG_ONLY; no real numbers or hidden truth leave their permitted stores.

## Evidence and exact inputs

- Accepted source W=/Users/aditya/dev/fp-wt/science2 . Original execution evidence E=/Users/aditya/dev/fuelprint-backend-execution-2026-10-18/workflow-backend . Plans here are outside application source.
- E/FINAL-BACKEND-CLOSEOUT-20261020.json and FINAL-BACKEND-HANDOFF-20261020.md; E/OWNER-ACCEPTANCE-20261020.json; E/full-verification-20261020-03/verification.json; E/final-working-file-list-20261020.json.
- E/ANTARA-WORK-INVENTORY-20261020.md: already-complete N1–N5 and software N6; remaining versioned RFC8785, Ed25519, ZIP/CLI, aerosol Cases. User confirmed Antara has not started separately.
- E/INTEGRATION-REVIEW-PROPOSAL-20261020.md and E/integration-review-20261020/file-disposition.json; pinned fork ADI238IND/fuelprint-innotech ops/singhal473ce064a41fff3b84f6540417655b8141d28292. Preserve snapshot; do not blindly import duplicate backend handlers or its contract2.2.
- AI-TRAINING-READINESS-20261020.md and gpu-provider-research-20261020.md are historical research for a LATER trained-model milestone. Do not repeat provider research or start training for this plan.

## Two-day capacity and checkpoint schedule

Treat two days as a48-calendar-hour target from the first actual execution start, recorded once in the new phase state. It is not a promise of completion, and not16total engineering hours. Do not reset that clock on a new chat. Measured elapsed time and active effort remain separate.

| Work package | Provisional focused actor-hours | Owner |
|---|---:|---|
| Honest AI rules-based prototype and dashboard integration |4–8|A|
| Minimal connected3D Field demo |6–12|A|
| Antara complete remaining backend software, including its tests/review |24–40|B|
| Minimal Cases/Operations screens and functional integration |6–12|A, B handoff|
| Whole-system checks, correction allowance, independent review and handoff |4–8|A; review slot rotates|
| Total across both lanes |44–80|Not additive wall time when lanes run concurrently|

Practical critical path is roughly34–60calendar hours with two lanes, serial Maven and prompt decisions;48h is a stretch target. Wider Field aerosol physics, missing contract decisions, unavailable physical devices or extensive UI integration can push it beyond two days. Do not silently drop Antara features, reduce test expectations, or replace integration with a scripted fake to fit the date.

| Relative time | Lane A | Lane B | Completion gate |
|---|---|---|---|
|H0–2|Preserve exact accepted base; bootstrap both candidates; confirm task interfaces and decisions|Adopt common base; prepare evidence/aerosol proposals and test matrix|Exact base manifest and ownership published; no missing base files|
|H2–10|Complete prototype, meaningful tests and live presentation|Versioned canonical manifest, signing/trust and legacy compatibility|Prototype labels honest; B format/key decisions explicit|
|H10–22|Minimal3D world, live readings/dashboard, start minimal screen integration|Bounded ZIP/CLI, then aerosol Cases path|Drone/readings derive from server; good/tampered archive behavior proven|
|H22–34|Adapt pinned useful Ops screens; implement thin Cases flow; receive B interfaces|Finish aerosol/QC/lineage and package regression|No fake readings or duplicate handlers; B readiness measured|
|H34–40|Integrate reviewed B delta; perform independent B review with slot released|Publish exact source delta/test results; pause writes for handoff/review|Candidate source identity and feature coverage agree|
|H40–44|Serial whole-system qualification|Reviewer/corrections only under coordinator|Changed candidate full checks, real workflows and source stability|
|H44–48|Fix material failures, rerun affected checks, rehearse and publish final results|Support bounded fixes under coordination|Independent SPEC/QUALITY review; truthful delivered/blocked/deferred list|

AtH10,H24,H36,H44 publish actual completed items, remaining estimates and blockers. AtH36, if B is not integration-ready, report schedule risk immediately; continue full scope unless user approves a concrete reduction. Never describe the baseline as a newly completed product.

## Cross-chat workspace and test coordination

1. A bootstraps E/two-day-demo-20261020/ containing immutable baseline manifest/snapshot, mutable coordination state, laneA-status.json, laneB-status.json and per-lane logs. B may inspect original files read-only while waiting for BASE_READY; B must not create a second divergent baseline.
2. Before writes, inspect current jobs and any new source drift. Adopt an existing job instead of duplicating it. Do not kill unknown jobs. Record original first-start UTC/deadline, worktree paths and baseline hashes.
3. Use using-git-worktrees for isolated detached candidates A and B, overlaying the SAME verified accepted dirty/untracked source snapshot onto HEAD. Include resources/tools/docs and any required ignored assets by manifest; never copy .git pointers, credentials or live runtime databases blindly. Keep original W untouched. Verify every accepted source hash in each candidate before edits; main alone is insufficient. Record chosen paths in shared state rather than inventing missing worktrees in reports.
4. Before server/src writes, atomically acquire the global coordination mutex (mkdir), verify no JAVA_RUNNING and no live Java job, register the lane as SERVER_WRITING, then release the mutex. Multiple isolated writers may be registered concurrently. Clear your registration under the mutex immediately after each edit batch. A Java run may start only when the writers set is empty; register JAVA_RUNNING with lane, PID, command, cwd, start time and log before releasing mutex. New writers cannot register until it is cleared. Launch failure also clears the reservation after verifying no process exists.
5. Use a separate field for server-only demo processes: their source identity stays fixed while running. Do not treat a stale lock as permission to delete it; inspect PID/host/start and coordinate recovery. Once Java completes, archive exit/result, then clear ownership under the mutex. CPU/browser/app-only work can continue during the other lane's Java test.
6. A alone owns integrated candidate composition, apps/package*.json, apps/shared/**, public API/migration coordination and final builds. B owns cases/** and matching tests; CLI source also stays under cases/** with a main entry point. B requests any pom, core download route or Field adapter through A with exact patch/proposal. No shared file is written by both chats.
7. Each lane publishes a manifest of created/modified/deleted files with before/after SHA256, test commands/results and any config/format decisions. A applies B's reviewed nonoverlapping file delta to the candidate without Git merge or overwriting original W. Overlaps need explicit comparison and tests. Preserve B's complete outputs even when a patch is not adopted.
8. On handoff failure keep branches/snapshots, resumable state and logs; do not call the work complete. Workers must give progress updates at least once per60seconds while actively working.

## Deliverable and acceptance matrix

|ID|Required behavior|Evidence|
|A1|Existing saved physics candidate facts shown in deterministic server order; plain explanations; synthetic label|Unit/property regressions plus live analysis UI screenshot/record refs|
|A2|Learned model honestly unavailable; no invented model_version, performance or normal-air band|Boundary tests and actual saved analysis fields|
|A3|3D drone pose/inlet and readings come from same real-mode server run/ticks; server-approved autopilot route|Live browser trace with matching run/tick/window IDs and timestamps|
|B1|Legacy stored bytes and verifiers remain valid; versioned JCS format explicitly identified|Legacy regression and official canonicalization vectors|
|B2|Ed25519 signature over exact manifest bytes; external trust anchor distinguishes trusted/untrusted/unsigned/invalid|Correct key, wrong key, missing key and tamper tests; no private key in outputs|
|B3|Bounded ZIP and independent offline Java verifier|Valid, altered, missing, duplicate, unsafe-path and oversized input tests; isolated process without server/database|
|B4|Separate aerosol media/custody/loading/QC/LOQ path with declared teaching inputs|Unit, command/role/idempotency/lineage and sealed-export workflow tests|
|I1|Adapt useful pinned Ops contribution; thin Cases flow exposes required backend actions/results|UI role/stale/rejected/null-target tests and real-mode workflow|
|V1|Full changed-candidate checks and real end-to-end rehearsal; independent final review|New logs, manifests, screenshots, counts and SPEC/QUALITY review|

Details and task-level tests are in PLAN-A-AI-MINIMAL-DEMO-20261020.md and PLAN-B-ANTARA-BACKEND-20261020.md. Use the existing backend APIs and records; any proposed public extension remains gated until concretely accepted.

## Functional integration task

**Files:** A's apps/operations/src/**, apps/cases/src/**, apps/field/**, apps/shared/src/types.ts only where actual current public payload requires correction; no contracts edits. Adapt tests under corresponding app tests/e2e. Consult fork file-disposition.json before importing each file.

- [ ] Preserve accepted handlers and encoding FP-MISSION-DEPS-1; do not import seven duplicate fork handlers/listeners or the fork's mission hash implementation. Start-up must prove one canonical handler per command, without bean override workarounds.
- [ ] Port useful Operations screens/styles with their provenance. Mission rejected actions with target:null must render the rejection, not dereference coordinates. Correct the shared ProposalAction target type to nullable only after matching actual server payload and checking consumers; do not fabricate zero coordinates.
- [ ] Keep daily metrics unavailable with explicit reasons until endpoint, duration definition and Field timing provenance are accepted and implemented. Do not compute substitute outcomes or timing metrics in the browser.
- [ ] Implement the minimal Cases route/controls using shared sendCommand/session/revision mechanics: intake -> specimen/custody -> lab/QC -> human finding -> seal/download/verify -> tamper COPY. Show signature/key trust and aerosol-versus-fuel distinctions. Do not build unrelated complete polished Cases suite. Use declared lab teaching inputs with labels; sensor readings remain server-produced.
- [ ] Ensure independent survey creation works when planner reports NO_USEFUL_MEASUREMENT, using the accepted public survey catalogue and correct human approvals; no scenario or planner changes to force a mission.
- [ ] Connect B's export/download/CLI results only after its protocol is accepted. Physical second-laptop result stays pending until actually run on that laptop.
- [ ] Test loading/empty/ready/pending/stale/not-allowed/rejected/interrupted states; keyboard operation,200%zoom and phone button size where that view is in scope. Disconnect makes command controls read-only; reconnect loads current server state without duplicating actions.
- [ ] Run app typecheck/tests/build and targeted live UI tests. Save the adapted source manifest and actual imported/omitted contribution list. Absent teammate code is reported absent/unavailable, not integrated or complete.

## Final verification and review task

**Files:** New phase evidence only; bounded fixes in the owning packages. Do not overwrite final03, V4 or owner acceptance.

- [ ] At integration-ready, stop source writers, record exact source/resource/dependency hashes and build the integrated candidate. Acquire the global Java reservation; never run two full checks from two chats.
- [ ] Inspect tools/test-all.sh before use: it packages Java, typechecks/tests apps and runs FAKE-server e2e. That fake e2e is a regression check, not the real-mode acceptance demo. Run it once for the changed candidate, capture full output/exit and report skips. Also run npm run build in apps and the existing wind/e2e-cleanup Python checks where applicable.
- [ ] Start a built real-profile server against a NEW isolated data directory, record port/PID/log/config and source identity. Exercise actual real sensor -> analysis -> approved independent survey/mission -> autopilot/capture -> human QC -> Cases -> lab/finding -> seal/offline verify/tamper -> Board/LOG_ONLY chain. Use accepted six natural workflow variants as the regression coverage matrix; actual ambiguous/inconclusive outcomes remain valid and disclosed.
- [ ] Demonstrate3D flight and dashboard readings refer to the same run and public server data. Pause/resume and reconnect; verify no stale fabricated motion/readings and no commands from stale views. Run completion/abort leaves no continuing capture/lease or silent state mutation.
- [ ] Test signature wrong/untrusted key and damaged ZIP on the same build; verify original pack still passes. Package the standalone verifier with exact Java17 invocation and test in a clean directory with no live DB/server. Run second laptop/Windows/phone tests only if actually available; report each unavailable device gate plainly.
- [ ] Compare pre/post source hashes, attribute every changed byte and record test suite freshness/counts. Logs with failures or warnings stay archived. On a fix rerun affected checks; repeat full qualification only if changed integration bytes require it.
- [ ] Pause the implementation worker and use a free actor slot for independent final SPEC/QUALITY review of current source, contract boundaries, cases cryptography/parser limits, live behavior, privacy and evidence. Resolve substantive findings and verify fixes before final approval. Do not ask the author to independently approve their own code.
- [ ] Publish TWO-DAY-DEMO-RESULTS.md and machine-readable result index: delivered/blocked/deferred perA1–V1, exact commands/results, source hashes, screenshots/run refs, known scientific limits, actual elapsed time versus48h target, launch/stop commands and separate physical/publishing gates.

## Explicit exclusions and gates

No heavy or light model training, ONNX integration, normal-air learned bands, automatic trained learning-loop success, paid GPU, full phone-controller/alerts redesign, gameplay, new scientific qualification, arbitrary-source inference or aerosol flight physics is included. Existing phone/alerts backend behavior is regression-tested and LOG_ONLY remains visible as needed; this is not a new phone experience.

Antara's Cases aerosol records/QC/export are included. A new Field producer or aerosol capture simulation beyond those documented human-recorded collection inputs is a separate design/estimate, not silently represented as finished by B4.

Protected contract changes, external device access, signing trust-anchor acceptance and Git publication remain explicit gates. Agents prepare concrete diffs/protocols/results before asking; do not ask the user to say "start" again for already-authorized tasks. A deadline cannot auto-approve a format, key trust, human legal decision or merge.

## Planning self-review (completed in this handoff)

All five requested workstreams map to tasks/acceptance IDs. Two chat prompts cover both lanes and assign integration/final verification to A rather than leaving them ownerless. The inherited dirty baseline, single Maven/no-source-writes rule, legacy evidence compatibility, no-fake-model status, Cases skeleton, aerosol boundary, independent review and physical gates are explicit. Estimates are provisional and the two-day target is disclosed as uncertain. No application code was changed to prepare this plan.
