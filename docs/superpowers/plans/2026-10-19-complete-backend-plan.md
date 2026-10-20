# Complete FuelPrint Backend Implementation Plan

> **For agentic workers:** Execute task by task using the existing executing-plans/subagent-driven-development workflows. This is a planning deliverable, not authorization to start code during this turn. Preserve all existing work and verification evidence.

Prepared 19 October 2026 from the already established session context and user decisions. No additional document reading or research was performed to create this plan. This plan supersedes the earlier Operations/Cases-only execution plan as the whole-backend coordination plan; existing numerical qualification remains evidence for its original source boundary.

## Decisions incorporated

| Decision | Implementation consequence |
|---|---|
| We will build all backend components ourselves | Core/API, science, field, alerts, Operations, Cases and backend AI services are our responsibility. No implementation task depends on a teammate delivering a backend module. Independent technical review is still required |
| Complete the backend first | Finish and verify server workflows before starting new screens, 3D or gameplay |
| First visual product is a 3D hardware/physics demonstration; gameplay later | Backend exposes actual simulated state, measurements, failures and evidence. It must work without game progression or scripted outcomes |
| ProspectiveInlets forecasting interface approved | Retain and integrate the existing verified interface and contract/API 2.3; do not rebuild unchanged forecasting work |
| CaptureRules.windows approved | Retain automatic VALID plus human VALID persistence and exact measured background/collection semantics |
| Preserve actual results and source-identification limits | Keep 57/60 raw compatible supports distinct from 8/60 final exact-support survivors. Do not tune physics, thresholds, pair filtering or scenarios to force success |
| Model training remains separate | Implement honest backend model/status/wording/learning interfaces, but do not claim trained-model operation before a real model is trained, imported and evaluated |
| Cover many situations | Implement a traced scenario/guard/fault matrix, not only three successful demonstrations. Count actual tests and configurations; do not promise exhaustive coverage |

Original SC1/SC3 narrative replacement and final freeze remain separate acceptance decisions. This plan does not infer their approval or use them as a reason to stop independent backend implementation. Human-only mission approval/rejection, capture review, findings and board decisions remain human-only.

## Verified starting point

- Existing worktree: `/Users/aditya/dev/fp-wt/science2`, branch `science/join`, HEAD/main checkpoint `8db5244`.
- Saved ten-phase classification: eight complete, two partial. This is not an effort percentage or proof that every newly included backend module is complete.
- Existing core/science/field/alerts infrastructure, capture windows and forecast implementation are retained.
- V4 calibration/evaluation and twelve-cell wind sweep are complete; final closeout audit and independent review passed within scope. Original evidence/freeze is immutable.
- Saved full suite: 528 Java tests plus existing screen/type/e2e/Python checks. Subsequent focused replay cleanup passed one test and forecasting/wiring checks passed 29 tests. The original full-suite SQLite teardown logs are disclosed; no pristine-log claim.
- Operations/Cases/AI production packages were placeholders at the latest inspection. No Operations/Cases code was started in the interrupted/read-only turns. A documentation handoff and a draft plan were created.
- Desktop team handoff: `/Users/aditya/Desktop/FuelPrint-Backend-Update.md`. Its original coordination proposal is historical context; the latest user decision is that we own the complete backend execution.

## Architecture and boundaries

Use the existing Java 17/Spring Boot server, SQLite record/event persistence, CommandBus, AutomationBus, RecordStore and BlobStore. Commands validate state and return record/event changes; the bus applies roles, idempotency, expected revisions, atomic writes and after-commit publication. Event listeners submit normal commands and never write records directly.

Modules communicate through public API types, saved public records and events. Operations calls the science/AI interfaces and stores returned results; Cases consumes screening/investigation/finding records without importing private module implementation. Field consumes genuinely approved missions and produces measured capture evidence. Alerts consumes public investigation/board decisions and remains LOG_ONLY for this build.

Unknown values remain null with reasons. Real-profile behavior, controlled fixtures and replay are separately labelled. No hidden truth reaches inference, a browser, public records, exports or wording. Phone numbers remain confined to the existing contact store.

## Execution controls

1. Preserve inherited tracked/untracked files and save a new execution snapshot before implementation. Use the current worktree; no reset, clean, new checkout, commit, merge, push or deployment.
2. Record the latest ownership decision in the execution ledger and maintain one explicit owner per active file/task. Teammate backend delivery is no longer a dependency; screen assignments are outside this backend scope.
3. At most two active actors/jobs; heavy Java/Maven runs serially. A reviewer does source/offline-evidence checks rather than duplicating the implementer's unchanged heavy run.
4. Complete meaningful red → green tests for each behavior. Use independent literal arithmetic and exact-byte oracles, real sessions/cookies and real command handling. Test-only fixtures/cleanup stay in tests.
5. Preserve original V4 files and reviewed reports. Track added workflow classes and any later source changes in a separate manifest. Statistical changes require separate scientific approval and fresh qualification; do not reuse observed held-out seeds as untouched.
6. No new network command/schema or changed human authority by implication. If an implementation gap genuinely requires an API amendment, prepare its concrete diff and continue other authorized tasks while that specific decision is pending.
7. Use focused verification during development. Run the full suite after integration; repeat or broaden it only when changes/failures warrant it. Obtain independent review before completion/readiness claims.
8. Update task ledger, coverage trace, retained command outputs and hashes as work proceeds. No new research/calibration/wind sweep for unchanged completed science.

## Task 1: Execution baseline and backend completion checklist

**Deliverables:** preserved worktree snapshot, execution ledger, explicit module ownership, completion/acceptance checklist and stable evidence index.

- [ ] Save tracked patch/status/untracked archive without discarding inherited files.
- [ ] Record the latest user instruction: complete backend implementation belongs to us; no teammate backend dependency. Keep all five human decision roles distinct from implementation ownership.
- [ ] Freeze the starting source/evidence identities and identify earlier passing checks as baseline evidence, not tests of new workflows.
- [ ] Establish the existing 40-family matrix as the minimum workflow trace: O01–O14, C01–C16, X01–X10. Add justified cases as actual gaps arise.

**Acceptance:** every remaining task has an owner, source/test boundary and observable output; no implementation is silently marked complete.

## Task 2: Operations investigations and analysis orchestration

**Files:** focused handlers/services in `server/src/main/java/fuelprint/ops/`; matching `OperationsWorkflowTest` and focused validation tests under its test package. Use existing generic record persistence rather than duplicate database writes.

- [ ] Real-bus failing tests for trigger-driven investigation creation, one active investigation per run, later-trigger attachment, scheduled survey without alarm, wrong-role, stale and duplicate submissions.
- [ ] Implement open_investigation, schedule_survey and run_analysis with exact recorded run/provenance relationships. Survey-only paths do not invent warning areas.
- [ ] Build AnalysisInput from same-run public windows, clock and wind. Call Ranker and optional LearnedRanker; save returned outcomes/candidates/exclusions/limitations exactly, preserving UNKNOWN.
- [ ] Call the public Planner with the saved analysis identity and public vehicle state. Save BEST_ACTION, NO_USEFUL_MEASUREMENT and PENDING faithfully; no recommendation from PENDING.
- [ ] After committed triggers/intake/evidence events, submit normal automatic commands with deduplication and correct revision/run linkage. Avoid repeated analyses caused by duplicate channel events or replay.
- [ ] Verify O01–O06/O13 and relevant X02–X04. Preserve red/green logs and actual receipts.

**Acceptance:** real investigations and analysis/proposal records exist through production handlers, including all five analysis outcomes and truthful unavailable learned-model status.

## Task 3: Mission proposal, independent surveys, human approval and expiry

**Files:** focused mission handlers, deterministic dependency hashing and public survey catalogue in `fuelprint.ops`; `MissionWorkflowTest` and catalogue tests.

- [ ] Test manual/automatic proposal, latest feasible action guards, auto_propose on/off and automation best-action-only before implementing them.
- [ ] Implement documented independent survey actions usable in any preset, including public target coordinates (0,100,35) and (280,140,45). Do not branch on hidden source identity or fabricate planner usefulness. Unknown action IDs and invalid/unavailable vehicles fail with reasons.
- [ ] Create genuine PROPOSED missions with server UUID, public vehicle, target/declared route, method/channels, pilot NONE and 300-second simulation expiry.
- [ ] Bind dependency SHA-256 to the recorded basis/current analysis and proposal revisions, wind direction/speed buckets and full declared route. Test each changed dependency and rounding/time boundary independently.
- [ ] Implement supervisor-only approve_mission/reject_mission. Validate current state, supplied/current hash, expiry, revision and run. Test automation refusal and payload identity spoofing.
- [ ] Expire APPROVED, unstarted missions on stale analysis/wind/time through AutomationBus. Preserve started-flight lifecycle rules and existing Field interruption behavior.
- [ ] Verify O07–O12 and cross-run/retry/reset combinations; retain precise rejection reasons and histories.

**Acceptance:** Field receives a real human-approved mission; integration no longer depends on inserting a test-only APPROVED mission.

## Task 4: Cases intake, specimens and append-only custody

**Files:** focused handlers and package-private record validation in `fuelprint.cases`; `CasesWorkflowTest` and custody tests.

- [ ] Test create_case against valid, limited, rejected, missing and mixed-run screening records. Preserve rejected evidence and its limitations.
- [ ] Implement intake and investigation/mission linkage, case state guards and repeated-command behavior.
- [ ] Implement create_specimen with the declared material/method, minimum 50 mg, seal/container/site and INSPECTOR_DEMO authority. Separate specimen identity from air screening identity.
- [ ] Create initial custody, then implement current-holder transfers and explicit permitted exceptions. Corrections append and reference earlier records without modifying them.
- [ ] Test broken/missing seals, absent/refusing recipients, non-holder transfer, correction history and persistence/restart.

**Acceptance:** C01–C07 pass through actual command handlers, preserving distinct objects, authority, same-run linkage and immutable history.

## Task 5: Lab arithmetic, quality checks and human findings

**Files:** pure lab arithmetic/QC plus lab/finding handlers in `fuelprint.cases`; literal numerical-oracle and command-level tests.

- [ ] Independently test the declared example: 2010−10 = 2000 µg net, 100 mg specimen → 2.0% w/w; duplicates 2.0/2.2 → RPD 9.5238095238%; spike 90/100 → 90% recovery.
- [ ] Implement raw-input persistence, finite/null-safe arithmetic and every declared blank/duplicate/recovery/material/method/hold-time rule. Missing QC stays PENDING; undefined arithmetic remains unavailable with a reason.
- [ ] Test immediately below/at/above QC/reporting limits, including 0.01% w/w reporting and 300 simulated seconds hold time. BELOW_LOQ never becomes zero.
- [ ] Implement human-only revise_finding with SUPPORTED_WITHIN_SCOPE, REJECTED_FOR_USE and INCONCLUSIVE citation rules. Preserve alternatives, limitations, prior findings and legal_suitability NOT_ASSESSED.
- [ ] Refuse unsupported guilt/fuel/legal claims and automation. A valid specimen result does not prove it caused the plume.

**Acceptance:** C08–C13 pass with independent numerical expectations and actual role/state/revision failures.

## Task 6: Exact-byte evidence export, verification and tamper-copy demonstration

**Files:** canonical-byte serializer, evidence vault, export handlers and sealing/verification listeners in `fuelprint.cases`; `EvidenceVaultTest`/`ExportWorkflowTest`.

- [ ] Write exact UTF-8 byte oracles for recursive sorted-key compact JSON; retain array order and stored bytes. Never derive expected bytes using the serializer under test.
- [ ] Implement seal_export with exact case/cited revisions, manifest fp.evidence/1, member lengths/SHA-256 and all required public lineage. Missing bytes, invalid references, unsafe member names and forbidden/private record types fail explicitly.
- [ ] Verify stored manifest/member bytes directly; do not regenerate them from live latest records. Report separate integrity, key trust, lineage, trusted time, custody, measurement and legal axes.
- [ ] Implement tamper_copy by altering one member in a new copy, preserving original bytes/expected hashes. Prove copy fails and original still passes.
- [ ] Implement finding→seal→verify through ordinary after-commit automatic commands with settings/deduplication and manual exporter paths. Prevent loops and automatic treatment of tamper copies as new sealed evidence.

**Acceptance:** C14–C16/X06/X08/X09 pass; integrity alone never becomes a physical/legal accuracy claim.

## Task 7: Board decisions, alerts and full event-chain integration

**Files:** board handler and integration listeners in `fuelprint.ops`; targeted alerts/core integration changes only if demonstrated necessary; actual command/event tests.

- [ ] Implement human-only board decisions requiring the correct case/finding, allowed decision values, valid notice areas and no source names in public notices.
- [ ] Connect actual investigation warning areas and board notices to existing Alerts behavior. Preserve consent, privacy, rate/cap/suppression guards and LOG_ONLY mode; no physical SMS is sent.
- [ ] Verify that receipt/event actors and automatic flags reflect actual commands, and failed transactions publish no premature success.
- [ ] Exercise analysis→proposal→approval→field evidence→case/finding→export→board/notification with real production linkage; no private module imports or direct listener writes.

**Acceptance:** O14 plus full-chain event/privacy tests pass; alerts evidence reflects actual sent/suppressed/log-only outcomes.

## Task 8: Backend AI service contracts and honest absent-model behavior

**Files:** backend AI services/handlers under `fuelprint.ai`, matching tests and only required owned dependencies/resources. Concrete dependency/version selection is verified at implementation time; no version is invented by this plan.

- [ ] Implement or connect model-version metadata, artifact validation/loading boundary and learned-order service against the already specified model format/API. Training datasets/jobs are separate.
- [ ] With no usable artifact, return MODEL_MISSING; INVALID/NO_SIGNAL gives NOT_APPLICABLE. Failed loading/inference is reported honestly. Only surviving registered explanations can be ordered; physics outcome/statuses and UNKNOWN stay unchanged.
- [ ] Provide normal-air inference boundary with explicit unavailable state before a trained model exists. Do not fabricate an expected band or anomaly evidence.
- [ ] Implement deterministic safe wording/template fallback and public-record validation. Optional external-provider support remains disabled without authorized configuration; tests make no real paid/network calls. Wording cannot change measurements, statuses or privacy rules.
- [ ] Implement retraining command/lesson eligibility and quality-gate lifecycle where supported by the specified contract. With no training implementation/artifact, return an explicit unavailable result; never mint a successful ACTIVE model or fake evaluation counts. Qualified and skipped case lessons remain traceable.
- [ ] Test model missing/corrupt/incompatible, order-only invariants, template validation/fallback, supported versus rejected/inconclusive lesson eligibility and parent preservation. Mark actual trained-model/ONNX parity and real retraining evaluation as deferred until genuine artifacts exist.

**Acceptance:** backend service behavior is implemented/tested within its declared available states; trained-model performance and successful retraining remain unclaimed until measured. Any required model-specific contract extension is presented separately.

## Task 9: Field/core integration and system recovery

**Files:** narrowly scoped existing owned backend adapters if a real mismatch is demonstrated; integration and recovery tests. Preserve previously verified Field/science behavior unless an actual bug requires a separately reviewed fix.

- [ ] Verify real approvals, expiry, reset, lease/autopilot, capture completion and human QC work together with newly implemented Ops/Cases handlers.
- [ ] Test pause/reset/end/restart during each workflow stage and recovery of saved cases/exports. Interrupted work must not be reported as completed.
- [ ] Verify stale and retried requests, same-command idempotency, concurrent revision conflicts, cross-run references and rollback of related writes.
- [ ] Verify live/read APIs recover from disconnect/cursor boundaries without duplicating commands or replaying vehicle input. Reuse existing core/field tests when they already establish the behavior.
- [ ] Retain wrong-role/automation refusal for all five human decisions, truth/phone privacy and append-only evidence checks.

**Acceptance:** X02–X09 and relevant existing core/field regressions pass, with no authority bypass or data loss in tested recovery paths.

## Task 10: Expanded scenario coverage and real end-to-end evidence

**Files:** separate integrated workflow test harnesses and coverage/evidence JSON under `docs/research/`; raw reports archived outside target under the execution directory.

- [ ] Run SC1/SC2/SC3 with automation off/on through real Operations, Field and Cases workflows. Use genuine analyst/supervisor approval and QC roles, not test-only approved mission insertion.
- [ ] Use independent scheduled surveys where appropriate; do not alter a no-useful planner result to justify a flight. Record actual before/after analyses, invalid channels and no-action results.
- [ ] Exercise all five analysis outcomes and all three finding outcomes with appropriately labelled controlled workflow fixtures where the natural simulation does not produce them.
- [ ] Complete trace O01–O14/C01–C16/X01–X10 and selected combined-fault variants: stale+retry, flow-fault+reset, broken-custody+tamper, model-missing+no-useful-action. Add targeted cases when a real defect/gap is found.
- [ ] Keep generator seeds, source versions, test commands, elapsed times, receipts, failures and denominators. Distinguish real simulation from fixture tests and future physical tests.
- [ ] Future wind/height/weak/multiple/unknown-source/background/fault science challenges are separately scoped supported-domain work, not automatic new V4 qualification. Do not convert the desired scenario count into a statistical accuracy claim.

**Acceptance:** every coverage family has actual evidence or an explicitly unresolved limitation; no cherry-picked successful-only report, fixed outcome script or claim of exhaustive combinations.

## Task 11: Full verification, immutable evidence and independent review

**Deliverables:** new workflow verification log/XML, separate provenance-extension manifest, coverage trace, independent review and honest phase report. Original V4 freeze/evaluation/closeout report stays unchanged.

- [ ] Run focused suites as tasks complete, then one full tools/test-all.sh after integration; include appropriate existing Python and fake-server checks and report actual counts/skips/errors/warnings.
- [ ] Verify immutable original calibration/evaluation/source/class/resource/dependency hashes, documented post-evaluation deltas and separately identified added workflow sources/classes. If an old production class changed, assess its attribution/numerical effect before reusing any qualification claim.
- [ ] Save git diff --check, preserved untracked/modified state, unchanged main/HEAD checkpoint and exact source/test/artifact identities. Never reset inherited work to achieve a clean status.
- [ ] Independent reviewer audits real authority, event/transaction/revision behavior, catalogue/route/dependency semantics, custody/lab/byte-verifier behavior, model-unavailable states, privacy and coverage claims. Fix material findings and rerun the affected checks.
- [ ] If a statistical change was genuinely necessary, do not label old V4 evidence as qualifying it; obtain the required new design/approval/qualification before a source-performance claim.

**Acceptance:** verified backend code and an independently reviewed evidence report with all scope limits visible; no unsupported readiness claim.

## Task 12: Backend handoff, acceptance gates and remaining-work inventory

- [ ] Produce a concise final implementation/evidence report, module status table, exact file list and remaining/deferred inventory.
- [ ] Treat original SC1/SC3 narrative mismatch as an explicit unresolved acceptance gate until specifically settled. A broad instruction to finish code does not silently approve replacing the narratives or statistical policy.
- [ ] Separate tested backend completion from model-training completion, trained inference qualification, new screens/3D, physical/device/Windows rehearsal and later gameplay.
- [ ] Record the owner decision needed for any merge/freeze; no commit/merge/push is authorized by this plan.
- [ ] Update continuation/run state so the next session resumes completed tasks rather than repeating research, calibration, wind measurements or unchanged tests.

**Acceptance:** Aditya can see exactly what is implemented, what was measured, what remains deferred and which specific acceptance/integration decisions remain. Only actually satisfied phases are marked complete.

## Upside and downside

**Upside:** one coordinated backend with broader testing; **downside:** we take on all implementation, integration and maintenance, so it takes more work before the 3D demonstration.
