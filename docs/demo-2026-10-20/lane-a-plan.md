# Lane A: AI prototype, minimal demo and integration implementation plan

> **For agentic workers:** Use executing-plans task by task. Coordinate with the separate Cases chat; do not spawn additional workers automatically. This is a plan, not implemented source.

**Goal:** Build the4–8h rules-based prototype, then the minimal server-connected3D demo, integrate reviewed Cases/Operations changes and qualify the whole system.

**Architecture:** The real backend remains authoritative. A pure server helper orders already-surviving registered candidates for presentation; it neither replaces scientific fitting nor pretends to be LearnedRanker. Shared live hooks feed the dashboard and Three.js scene. The same candidate later accepts the reviewed B delta and compatible Operations UI.

**Tech stack:** Existing Java17/Spring backend and React18/TypeScript/shared live client; pinned Three.js version selected/recorded at dependency setup. No GPU, training or ONNX dependency.

## Required context and boundaries

Read TWO-DAY-DEMO-PLAN-20261020.md first; all its Global Constraints and coordination rules apply. User-selected prototype is explicitly rules-based. Read repository AGENTS.md/contracts/AGREEMENT.md and server/src/main/java/fuelprint/api/{Analysis,Candidate,LearnedOrder,LearnedStatus}.java. Current learned_order must remain empty when MODEL_MISSING. The browser cannot calculate a new ranking from fit values.

Existing public candidate order is not documented as a ranking guarantee. The proposed server adapter makes it deterministic for this prototype and the UI says "Physics fit order — rules-based demo; trained ranker unavailable". If current contract checks require a new explicit heuristic-order field instead, prepare the exact proposal and wait for that decision; do not misuse learned_status OK. Independent Field/UI shell work may continue.

## A0: Preserve and coordinate before implementation

**Files:** E/two-day-demo-20261020/baseline/**, coordination.json, laneA-status.json and logs; chosen isolated candidate paths recorded there. Never edit original science2.

- [ ] Adopt any running Java job and inspect source changes since the accepted manifest; preserve all new work. Publish actual first-start/deadline once.
- [ ] Create one exact source snapshot and two isolated worktrees with the same dirty/untracked overlay; verify their source hashes. Publish BASE_READY with paths and hashes. B uses this base.
- [ ] Establish the mutex/writer-registration/Java-reservation protocol from the master plan; install only project-required dependencies in the candidate when actually needed.
- [ ] Read exact current analysis and tick producers/consumers, Cases skeleton and pinned fork disposition; record interface assumptions without restarting completed backend/science research.

**Acceptance:** Both lanes can identify the same inherited source; original worktree unchanged; no competing Java/source writer reservation. Package installs are preparation for this scope, not proof of functionality.

## A1: Honest rules-based ranking,4–8h prototype milestone

**Create:** server/src/main/java/fuelprint/ai/DemoRanking.java; server/src/test/java/fuelprint/ai/DemoRankingTest.java. A server helper only; no learned model registration.

**Modify:** server/src/main/java/fuelprint/ops/AnalysisHandler.java at the single point that saves the complete analysis, only to apply the pure presentation adapter; apps/operations/src/components/PhysicsRanking.tsx and its component test; integrate component into current App.tsx or the subsequently adapted analysis page. Preserve the handler's revision/idempotency/transaction machinery.

**Proposed internal interface (not a new public endpoint):**

```java
public final class DemoRanking {
    private DemoRanking() {}
    public static Analysis orderForDemo(Analysis input);
}
```

Consumes public immutable Analysis and returns a new Analysis with only candidates' presentation sequence changed. Every candidate object/value remains present exactly once. Preserve inputWindowIds,outcome,qualification,exclusions,limitations,explanationText,algorithmVersion,learnedOrder,learnedStatus,learnedModelVersionId,historyCounts. Do not modify the original instance.

Ordering rule: for applicable analyses, registered SURVIVES rows (nonempty sources, not UNKNOWN) appear first in ascending finite fit, missing/nonfinite fits last, explanationId as deterministic tie breaker. Other rows preserve their input relative order. UNKNOWN remains visible and unchanged, outside the ordered registered group. INVALID/NO_SIGNAL returns the original analysis unchanged. A display helper does not certify the ordering as scientific evidence or probabilities.

**Interfaces to UI:** Existing AnalysisRecord.candidates and existing unavailable learned fields only. Props: `PhysicsRanking({analysis}: {analysis: AnalysisRecord})`. The component displays server sequence; it filters for display sections without sorting or computing fit/order. Render rejected/background/UNKNOWN visibly alongside their status/reason and show explicit trained-model unavailable text.

- [ ] Write meaningful failing tests for ordering, stable ties/missing fits, permutation invariance among registered rows, original-input immutability, preservation of every candidate field and learned metadata, and invalid/no-signal behavior.
- [ ] Use an explicit test fixture like the following, then assert complete output equality except the sequence. Constructors/types are existing project APIs.

```java
var rows = List.of(
    new Candidate("S02", List.of("S02"), 2.0, CandidateStatus.SURVIVES, null),
    new Candidate("UNKNOWN", List.of(), null, CandidateStatus.SURVIVES, "Unknown source remains possible"),
    new Candidate("S03", List.of("S03"), 0.1, CandidateStatus.REJECTED, "Rejected by physics"),
    new Candidate("S01", List.of("S01"), 1.0, CandidateStatus.SURVIVES, null));
var before = Analysis.physics(List.of("window-1"), Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE,
    Qualification.SYNTHETIC_SCOPE_ONLY, rows, List.of(), List.of("Synthetic scope only"),
    "Several explanations fit", "FP-COMPAT-3/4")
    .withLearned(LearnedOrder.none(LearnedStatus.MODEL_MISSING, null, null));
var after = DemoRanking.orderForDemo(before);
assertEquals(List.of("S01", "S02", "UNKNOWN", "S03"),
    after.candidates().stream().map(Candidate::explanationId).toList());
assertEquals(before.outcome(), after.outcome());
assertEquals(LearnedStatus.MODEL_MISSING, after.learnedStatus());
assertTrue(after.learnedOrder().isEmpty());
assertNull(after.learnedModelVersionId());
assertEquals(rows, before.candidates());
assertEquals(new HashSet<>(rows), new HashSet<>(after.candidates()));
```

- [ ] Acquire global Java ownership; run `./mvnw -q -Dtest=DemoRankingTest test` from candidate/server, archive the genuine failing result; release ownership before edits.
- [ ] Implement the helper and single saved-analysis adapter. No fit calculation, changed numeric values, source truth reads, model activation or science threshold changes. Unknown/malformed public input gets existing safe rejection/unavailable behavior rather than guessed values.
- [ ] Add component regressions: synthetic label, rules-based label, MODEL_MISSING, UNKNOWN visible, rejected candidate not promoted, no probabilities/score percentages, empty/no-signal states and no client-side sorting.
- [ ] Run targeted Java and app tests under coordination, then typecheck. Verify saved real-mode analysis contains expected original facts and honest model fields. Record elapsed hours and actual scope at prototype checkpoint; do not claim the3D/Cases milestone complete here.

## A2: Minimal connected3D Field demonstration

**Create:** apps/field/package.json, tsconfig.json, vite.config.ts, index.html, src/main.tsx, src/App.tsx, src/scene/FieldScene.tsx, src/scene/coordinates.ts, src/components/ReadingPanel.tsx, src/App.test.tsx, src/scene/coordinates.test.ts, e2e/live.e2e.test.tsx, following existing Operations/Cases package conventions. Modify apps/package*.json once, with exact pinned dependency versions. Add no unrelated visualization framework or art-generation dependency.

**Consumes:** useLive/useSnapshot/useSession from @fp/shared; tick.payload.vehicles, their pose/inlet/energy/stage and tick readings/wind/run/time. Map public coordinates using exactly one function `(east,north,up) -> (east,up,-north)`; geometry comes from current public world-profile/reference data. Do not import science.truth or training data, even in frontend tooling.

**Produces:** Live3D estate with six fictional source markers, sensor markers and one moving drone, plus a reading panel with channel/unit/QC/origin/time and the same run identity. Server poses drive drone motion; any interpolation is visual only and clamped between observed poses. It cannot generate sensor data or mission progress.

```typescript
export function toScene(p: {east: number; north: number; up: number}): [number, number, number] {
  return [p.east, p.up, -p.north];
}
// Regression: expect(toScene({east: 10, north: 20, up: 3})).toEqual([10, 3, -20]);
```

- [ ] First implement shared live state and a2D textual readings/pose view with component tests; missing channels display unknown+reason, never zero. Mock ticks are test fixtures only.
- [ ] Add the lightweight3D renderer: estate ground, source/sensor labels and drone mesh. Dispose renderer/materials/listeners on unmount. Render error/WebGL unavailability shows the truthful textual live view; do not crash the dashboard.
- [ ] Add unit tests for axis/sign mapping and component tests proving that new ticks update visible pose/readings while run mismatch, pause/stale/disconnect cannot display fabricated updates. Test null readings and empty vehicle list.
- [ ] Use server-approved autopilot and the existing survey/mission command flow for flight. No new phone joystick/controller, game or invented path. Human supervisor approval stays mandatory; accepted independent surveys provide a route when the planner has NO_USEFUL_MEASUREMENT.
- [ ] Keep session/role/revision-aware minimal start/pause/resume controls, using public commands/shared sendCommand. Show rejected reasons. Do not authorize actions because the3D scene looks ready.
- [ ] Typecheck/test/build apps and exercise the view against a real-profile server; archive matching tick/pose/reading evidence and screenshots. Fake-server skeleton e2e alone does not satisfy this task.

## A3: Integrate Operations/Cases and the B deliverable

Follow the master plan's functional integration task. Source files and acceptance IDs are specified there. A alone owns shared types, package lock, thin Cases UI and final candidate composition. B provides cases code, CLI and protocol/test manifests; coordinate any adapter required in core/Field/pom through A.

- [ ] Inspect exact Singhal pinned files; adapt useful UI/tests, keeping accepted backend handlers/mission hashes and contract. Explicitly cover rejected action target:null and independent surveys.
- [ ] Build the thin Cases controls/results flow over accepted records/commands. Wire B's new signed/offline/aerosol behavior only after required format/API decisions are accepted. Test ordinary legacy unsigned paths as well.
- [ ] Receive B READY manifest, review exact delta and targeted results, verify original-base hashes, apply only reviewed changes to A candidate and record overlaps/resolutions. No Git merge/commit is part of this step.
- [ ] Run integration-targeted command/UI tests, then publish INTEGRATION_READY with candidate hashes and known limitations. Do not claim unavailable teammate panels or devices are included.

## A4: Whole-system qualification and final handoff

Execute the master plan final-verification checklist, preserving one Java job and no server source edits while tests run. Full test-all fake e2e and the genuine real-mode browser/system rehearsal are distinct required evidence. Include offline verifier clean-process test, signing/trust/tamper, aerosol/fuel identity separation, human gates, stale/reconnect, abort/completion, privacy and LOG_ONLY.

Use a free actor slot for independent review after one implementation lane releases/pauses. Preserve all failures and review corrections. Finish with actual delivered A1–V1 matrix, run/build instructions, file manifest, checks/counts/skips/warnings, physical tests performed versus pending, measured elapsed time and remaining owner gates. Heavy AI training stays deferred even if time remains.
