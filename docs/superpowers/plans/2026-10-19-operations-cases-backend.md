# Operations and Cases Backend Implementation Plan

> Superseded for whole-backend coordination by `2026-10-19-complete-backend-plan.md`. This earlier draft retains detailed Operations/Cases task notes; no implementation was started from it before the user's planning-only steering.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Checkboxes track execution, not approval of outcomes.

**Goal:** Implement the missing Operations/Cases backend promises and verify the real investigation-to-evidence workflow across the 40 coverage families.

**Architecture:** Keep the existing CommandBus, RecordStore, BlobStore, public science/field ports and after-commit events. Handlers read StoreView and return RecordChange/EventDraft results; listeners submit ordinary AutomationBus commands. Cases never imports Operations internals and Operations never imports Cases/Field/Science internals. Reference public saved records and API beans instead.

**Tech Stack:** Java 17, Spring Boot 3.5, Jackson, existing SQLite/Flyway infrastructure, JUnit 5/AssertJ and Maven wrapper. No new dependency or external service.

## Global Constraints

- Existing isolated worktree: /Users/aditya/dev/fp-wt/science2, branch science/join, main/base 8db5244. Preserve all tracked/untracked work. No commits, merge, push, reset, clean, deployment or external messages.
- User authorized this backend execution after the Desktop documentation handoff. AGENTS.md records the bounded Operations/Cases backend exception; screen assignments remain unchanged.
- At most two active actors/jobs. One implementer at a time; no coordinator Java/Maven while an implementer tests. Heavy Maven/Java always serial. Reviewer performs read-only source/offline evidence checks, not duplicate heavy tests.
- Active contracts/AGREEMENT.md 2.3 wins over guides and historical R3 material. Read the complete agreement and applicable guide before code. No new command, network schema, hidden-truth access or public API amendment by implication.
- Five human-only commands always refuse automation. Role, idempotency, expected revision and transaction/event ordering come from the existing bus. Real sessions/cookies in workflow tests; no identity from payloads.
- New code/tests only in approved ops/cases folders and matching tests; coordinator owns cross-module integration/tests/docs. No raw SQL/migrations unless an actual required store gap is demonstrated: the generic records/events infrastructure already persists these record types.
- Write and run meaningful failing tests before behavior implementation. Literal independent lab/byte expectations; test utilities hold seeding/cleanup. Retain red/green logs outside target in execution-directory/workflow-backend/. Use focused tests until the final full suite.
- Never tune physics/cutoff/selection to a story. V4 original evaluation/freeze and old reports immutable: 57/60 raw compatible, 8/60 final exact survivors. New workflow code gets separate provenance; observed evaluation seeds stay observed.
- Deferred: trained model implementation/training/evaluation, new screens, 3D, physical/Windows qualification and later gameplay. Operations must truthfully expose absent learned models through existing MODEL_MISSING/NOT_APPLICABLE/FAILED statuses.
- The 40-family matrix is docs/contract-proposals/2026-10-19-operations-cases-coverage.md. Trace each family to actual tests or explicitly state a remaining gap. Fixture branch tests and real-simulation tests are different evidence.
- Scenario wording/freeze approval remains unanswered. Completion of implementation/tests must not be called acceptance of the old SC1/SC3 stories or whole-product readiness.

## Task 1: Cases intake, specimens, custody, lab and human findings

**Files:** Create focused classes under server/src/main/java/fuelprint/cases/ for five command handlers, shared package-private record validation and pure lab calculations; tests under server/src/test/java/fuelprint/cases/ (CasesWorkflowTest and LabMathTest). Update package-info.java only to describe actual implementation.

**Consumes:** CommandHandler.commandType/allowedRoles/revisionSubject/handle(Command,Session,StoreView); RecordChange.create/next; HandlerResult.builder().save(...); EventLog.forRecord; ClockControl; ObjectMapper. Generic records already provide append-only storage. Read docs/plan/03-cases-guide.md N1–N4 and the active method profile.

**Produces:** Registered create_case, create_specimen, append_custody, request_lab, revise_finding handlers and case/specimen/custody_event/lab_result/finding records with exact contract fields. All records use same-run references; only required related records advance state. No Ops-private dependency.

- [ ] Write CasesWorkflowTest using real bus/sessions and a test-only fixture handler for input investigation/mission/screening records. Assert the absent create_case initially returns a rejected/unknown command instead of a saved case; run and preserve the expected RED.

```java
var receipt = send(caseReviewer, "create_case", runId,
        json().put("purpose", "Synthetic specimen investigation")
              .set("screening_record_ids", jsonArray(validScreeningId)), null);
assertThat(receipt.status()).isEqualTo(Receipt.Status.APPLIED);
assertThat(store.list("case", runId)).hasSize(1);
```

- [ ] Implement intake and provenance validation, allowing rejected screening evidence but preserving its limits. Derive investigation from mission linkage, refuse unknown/mixed-run references. Human role is case_reviewer. State INTAKE; protect inappropriate transitions and stale revisions.
- [ ] Add/run RED tests, then implement specimen and custody behavior: minimum 50 mg, valid site/material/method, separate specimen identity, INSPECTOR_DEMO authority; first intact custody event; current holder or explicit permitted exception; corrections append and reference originals. Retain raw events and latest holder safely.
- [ ] Add literal math/QC tests before lab code. Implement null-safe finite arithmetic from the method profile; missing inputs PENDING, undefined ratios unavailable, failed reasons accumulated, BELOW_LOQ distinct from zero. Use the specimen's measured mass. Compute seal-to-analysis duration from recorded simulation event timing for the same run, never a guessed wall/simulation conversion.

```java
// Guide inputs: gross=2010 ug, blank=10 ug, specimen=100 mg,
// duplicates=2.0/2.2 %, spike added/recovered=100/90 ug.
assertThat(result.netUg()).isEqualTo(2000.0);
assertThat(result.percentWw()).isEqualTo(2.0);
assertThat(result.rpdPct()).isCloseTo(9.523809523809524, within(1e-10));
assertThat(result.recoveryPct()).isEqualTo(90.0);
```

- [ ] Add/run RED findings tests, then enforce supported/rejected/inconclusive citation rules, nonempty limitations, alternatives when applicable, forbidden overclaim language and immutable legal_suitability NOT_ASSESSED. revise_finding is human-only; old findings remain.
- [ ] Run ./mvnw -q -Dtest=CasesWorkflowTest,LabMathTest test; fix real failures, preserve logs/counts. Cover C01–C13 plus relevant X02/X03/X04. Report exact files/tests/evidence and unresolved contract issues without inventing fields to hide a gap. No commit.

## Task 2: Exact-byte evidence vault and sealing/verification automation

**Files:** Create cases/EvidenceCanonicalJson.java, cases/EvidenceVault.java, cases/ExportHandlers.java, cases/CasesAutomation.java, and matching EvidenceVaultTest/ExportWorkflowTest. Use prior Cases record validation as appropriate; keep files focused.

**Consumes:** Saved cases/findings/specimens/custody/lab and cited record revisions from Task 1, BlobStore.put/get/sha256, AutomationBus, RecordSaved, AutomationSettings, ServerMode. Reads records through core public services; no filesystem path from untrusted member text.

**Produces:** seal_export, verify_export, tamper_copy handlers; export/verification_report records; exact stored canonical member and manifest bytes. Cases reaches SEALED only after successful valid sealing; automation is allowed only for seal_export/verify_export under existing rules.

- [ ] Write failing exact-byte tests. Canonical JSON sorts object keys recursively, preserves array order, uses UTF-8 with no pretty whitespace and stores the actual bytes once. Expected literal bytes below must not be produced by calling the serializer under test.

```java
assertThat(canonical(json().put("b", 2).put("a", 1)))
        .isEqualTo("{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8));
```

- [ ] Implement manifest schema fp.evidence/1, exact case_revision/cited type-id-revision members and length/SHA-256. Collect case/specimen/custody/lab and transitive cited public evidence needed for lineage. Refuse missing/foreign/forbidden private records. Never include alert_subscription/notification or hidden-truth data. Do not reserialize old records during verification.
- [ ] Write/run RED corruption/lineage tests before verifier code; implement separate seven axes. Integrity PASS must not imply key trust, trusted time, measurement accuracy or legal suitability. Missing/corrupt manifests/members produce explicit failed verification reasons, not partial success or overwritten originals.
- [ ] Write/run RED tamper-copy tests, then implement independent copied bytes with one changed member and unchanged expected original manifest. Copy must fail integrity on that member, original must still verify and its blob hashes must remain unchanged. Validate member paths and membership; never resolve arbitrary user paths.
- [ ] Write/run RED real-bus auto_seal on/off tests; implement after-commit finding→seal and export→verify listeners with exact committed revisions, settings, idempotence and no loops on tamper/verification records. Never save records directly from listeners.
- [ ] Run focused EvidenceVaultTest,ExportWorkflowTest and CasesWorkflowTest/LabMathTest regressions serially. Cover C14–C16 and X06/X08/X09. Report and independently review before Task 3. No commit.

## Task 3: Operations investigations, surveys, analyses and public action catalogue

**Files:** Create ops/OpsRecords.java, ops/InvestigationHandlers.java, ops/AnalysisHandler.java, ops/SurveyCatalog.java, ops/OpsAutomation.java (investigation/analysis events only at this step), and tests OperationsWorkflowTest/SurveyCatalogTest. Class names can be split into smaller responsibilities within this folder without changing public contracts.

**Consumes:** Ranker.analyze(AnalysisInput), optional LearnedRanker.order(...), Analysis.withLearned, Planner.plan(Analysis,PlanContext), AlertAreas.downwindOf, WorldClock, VehicleSource, ClockControl, public recorded windows/sensors/missions, StoreView and AutomationBus. Read Operations guide S1. No import of science/field/cases-private classes.

**Produces:** open_investigation, schedule_survey, run_analysis handlers; investigation, survey, analysis and proposal records. Public survey action catalogue is available by action_id without a new payload field. Survey is an analyst-directed teaching/measurement action, not a fabricated beneficial planner recommendation.

- [ ] Start with failing real-bus tests for the three missing commands, one-open-investigation/later-trigger attachment and no-alarm surveys. Test-only controlled Ranker/Planner beans exercise rare outcome branches; label fixture tests separately from real-world integration.
- [ ] Implement trigger/run validation and warning areas; survey warnings remain empty. Choose/reuse the active investigation consistently to avoid duplicate open investigations. Survey reason must be present and supplied references must belong to the run.
- [ ] Define documented public catalogue entries SURVEY-DRONE-0-100-35 and SURVEY-DRONE-280-140-45 for all presets, using public target coordinates (0,100,35) and (280,140,45). Do not choose targets from hidden truth or switch on preset IDs. Record catalogue target/method/channels in the survey; method TEACH-NODE-ONLINE-2 with SO2_ppb/CO2_ppm, target as the declared route waypoint. Reject unknown actions and unavailable/invalid public vehicles/geometry. This supplies an independent survey route even if the planner correctly returns NO_USEFUL_MEASUREMENT.
- [ ] Implement analysis input from same-run saved windows and public clock/wind, preserve IDs required by the real ranker snapshot, then store physics plus learned fields and planner result exactly. If no LearnedRanker bean exists, use the existing honest status contract:

```java
var none = physics.outcome() == Outcome.INVALID || physics.outcome() == Outcome.NO_SIGNAL;
var learned = LearnedOrder.none(none ? LearnedStatus.NOT_APPLICABLE : LearnedStatus.MODEL_MISSING,
        null, null);
var combined = physics.withLearned(learned);
```

- [ ] On committed trigger, submit open_investigation; after that, submit run_analysis. On same-run/mission screening records, mark EVIDENCE_BACK through a normal existing command transaction and rerun analysis without duplicate side effects for the two channels. No new internal command by implication: use run_analysis to update that linked state while computing the next analysis, and retain its history.
- [ ] Test all five outcomes, three proposal statuses, run isolation, exact returned candidates/UNKNOWN, model absent/failure, wrong roles and retries. Run focused OperationsWorkflowTest,SurveyCatalogTest plus relevant architecture guards; cover O01–O06 and O13. No commit.

## Task 4: Real mission authority, expiry and board decisions

**Files:** Create ops/MissionHandlers.java, ops/MissionDependencies.java, ops/BoardDecisionHandler.java; extend ops/OpsAutomation.java for proposal/expiry events and public world-step checks; tests MissionWorkflowTest/BoardDecisionTest.

**Consumes:** Task 3's saved proposal/survey/investigation and public vehicle/wind/clock, core settings/events; saved case/finding records from Tasks 1–2. Uses only public records/API and existing bus. Field already consumes approved mission records with target/route/method/channel_ids/pilot.

**Produces:** propose_mission, approve_mission, reject_mission, expire_mission, record_board_decision handlers. Mission authority is public recorded state, never a test-only APPROVED record.

- [ ] Write/run RED manual/automatic proposing and human approval tests before handlers. Proposal basis requires latest feasible selected action and useful planner result. Automation requires auto_propose and best_action_id. Independent survey basis uses Task 3 catalogue with an analyst reason, not a fabricated planner score. Reject mixed/unknown/stale bases and duplicate open mission proposals.
- [ ] Implement mission fields: server UUID, target, declared route waypoint(s), method/channels, pilot NONE, expires_s = current simulation time + 300. Use available public vehicle identity, never a hard-coded Field drone ID. Dependency digest deterministically binds the basis ID/revision, current analysis/proposal identity when applicable, wind buckets and full recorded route list. Independently test each changed input and rounding boundary; preserve literal hash vectors.

```java
double directionBucket = Math.round(wind.fromDeg() / 10.0) * 10.0;
double speedBucket = Math.round(wind.speedMS() / 0.5) * 0.5;
// SHA-256 of a deterministic UTF-8 JSON object containing all bound fields;
// compare against current recorded authority, not just the payload hash.
```

- [ ] Human supervisor approve/reject guards: current state, supplied hash, current dependencies, not expired, run identity, expected revision; never automation. Expire APPROVED unstarted missions on changed analysis/wind or timeout through AutomationBus using current expected revision; do not interrupt started flight via the unstarted-approval rule. Reconcile reset/end with existing Field lifecycle.
- [ ] Board decisions require a same-run case finding, allowed decision enum, human board role, valid notice area IDs and no S01–S06 labels in any public notice field. Store notice for existing Alerts integration; do not send SMS directly. Invalid/malformed references must not throw partial writes.
- [ ] Run focused MissionWorkflowTest,BoardDecisionTest and Operations tests. Cover O07–O14 plus X02/X03/X04. Independent task review. No commit.

## Task 5: Real integrated scenarios, adversarial transitions and provenance extension

**Files:** Add server/src/test/java/fuelprint/ops/IntegratedInvestigationTest.java, cases/RecoveryAndEvidenceTest.java, and a traced coverage record under docs/research/. Preserve the existing six-case archived real-backend reports and original test as historical scoped regression; add a separate full-workflow harness using real production commands. Create a workflow provenance checker under the external execution directory.

**Consumes:** Production commands from Tasks 1–4, existing real core/science/field/alerts, existing settings, cookies, live/read APIs and persistence. Existing no-useful planner outcomes remain correct; use the explicitly recorded independent survey path for a teaching flight.

**Produces:** Actual workflow evidence showing analyst survey/proposal → human approval → real field/autopilot/capture → human QC → case/specimen/custody/lab → human finding → seal/verify → human board decision. Real backend data and all unavailable/deferred states remain labelled.

- [ ] Write/run RED full-path tests while still missing the last integration behavior. For SC1/SC2/SC3 and automation off/on, record actual initial/post-QC outcomes, mission authority and receipts. Do not force SC3 finding INCONCLUSIVE if evidence does not justify that expectation; separately exercise allowed finding branches with controlled valid records.
- [ ] Add focused wrong-role/automation guards for all five human commands, stale/repeated/cross-run references, transaction rollback, reset and restart persistence, malformed export path/corrupt blob, custody exception+tamper and model-missing+no-useful combinations. Use meaningful deterministic tests for X01–X10 and reuse existing core/field checks rather than duplicate framework tests.
- [ ] Trace O01–O14/C01–C16/X01–X10 to actual tests and retained results. Record any honestly unsupported challenge as a gap, not a pass. Keep physical/device tests and new science stress domains outside this matrix's implemented claims.
- [ ] Verify original frozen files and original evaluation hashes unchanged, current original-class names retain their documented expected hashes, and explicitly record newly added workflow sources/classes/dependencies separately. Do not rewrite the original strict 360-class freeze as if it contained these new classes. If an existing production class must change for integration, document and independently assess numerical scope before any qualification claim.
- [ ] Run focused integrated/regression checks serially. Archive fresh reports outside target under workflow-backend/ with source/test hashes and command exit codes. No commit.

## Task 6: Full verification, independent review and honest final backend report

**Files:** New docs/backend-workflow-verification-2026-10-19.md and docs/backend-remaining-work-2026-10-19.md update; execution CONTINUATION.md, RUN-STATE.json and final independent workflow review outside repo. Do not overwrite the reviewed V4 closeout report/evaluation.

- [ ] Run tools/test-all.sh once after focused fixes pass; archive output/XML and exact aggregate. Run relevant existing Python checks if not included. Report actual Java/screen/type/e2e counts and any skipped checks or teardown warnings.
- [ ] Run final frozen-evidence/workflow-extension audit and git diff --check, verify head/main remain 8db5244 and all inherited content is preserved. Save exact new/changed files, source and evidence hashes.
- [ ] Independent final read-only reviewer examines the full uncommitted backend against active contracts, the 40-family trace, role/transaction/revision/privacy/truth/expiry/evidence boundaries and new verification evidence. Fix real findings; repeat only affected verification, broadening when justified.
- [ ] Update the phase ledger honestly. Implementation of real Ops/Cases removes the test-only orchestration gap only after actual integrated tests pass. Original narrative/freeze gate, trained models, 3D/screens, physical/Windows and any unimplemented agreed item remain explicit. No all-ten-complete/readiness/freeze claim unless every required gate is satisfied; no commit/merge/push authorized.
- [ ] Report the concrete backend result, evidence, changed files, material limitations and remaining/deferred work to Aditya. Desktop handoff message stays two lines; no teammate messages are sent by tools.
