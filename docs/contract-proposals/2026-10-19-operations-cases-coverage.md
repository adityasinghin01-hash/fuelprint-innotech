# Operations/Cases implementation and broader scenario coverage — proposal

Status: Aditya authorized planning and implementation on 19 October 2026 after requesting a Desktop copy for team coordination. This document explains the bounded backend execution assignment and proposed test coverage. It does not approve measured scenario wording or authorize a commit, merge or freeze. No implementation or coverage result is claimed by this document.

## Two lines to share

1. **Change:** Propose having Aditya Singh/Codex implement and integrate the Operations/Cases backends against the existing API contract, with a broader scenario test suite; teammates retain their screen assignments and review the integration.
2. **Upside:** We can test the complete investigation-to-evidence workflow across normal, uncertain, faulty and interrupted situations, rather than relying only on the three demonstration scenarios.

## Relation to the five existing guides

This is an addendum to the five guides, not a replacement. Read it before starting backend edits so the team does not duplicate implementation and knows which extra situations will be tested.

| Existing guide | Relation to this update |
|---|---|
| 01 Team plan | Updates who executes the Operations/Cases backend work and how integration/test coverage is tracked; the common system and human roles remain as specified |
| 02 Operations guide | Its backend requirements become the implementation baseline for Aditya Singh/Codex; existing screen assignments remain with the Operations team |
| 03 Cases guide | Its backend requirements become the implementation baseline for Aditya Singh/Codex; Cases screen assignments remain unchanged and the original backend owner reviews integration |
| 04 Field plan | Existing vehicle/capture work connects to real mission approval and case intake instead of test-only orchestration; no 3D or screen scope is reassigned |
| 05 Study pack | The explanation of the product, hardware and evidence limits remains relevant; broader tests must preserve those limits and actual outcomes |

## Does the contract need to change?

The existing agreement already defines the Operations/Cases commands, roles, records and workflow. Implementing those promises and adding tests does not itself require new API messages or a contract version bump. The existing approved forecasting amendment is version 2.3 and is separate from this proposal.

The authorized backend execution assignment is recorded in `AGENTS.md`, the ownership notes in the Operations/Cases guides, and the execution ledger. Preserve screen assignments unless separately agreed. The original backend owners review the handoff so concurrent edits do not conflict. No teammate notification is sent by this task; Aditya will share this document.

Any missing command, payload field, record meaning or genuinely new behavior discovered during implementation needs a separate concrete contract amendment before it is introduced. More tests must not silently redefine existing rules. The active `contracts/AGREEMENT.md` wins over historical R3 notes and older guide examples.

## Implementation boundaries if approved

- Operations: real investigations, surveys, analysis/proposal persistence, mission proposal and human approval/rejection, expiry, and board decisions. Call the science/AI public interfaces; never rewrite their results.
- Cases: intake, separate specimens, append-only custody, lab arithmetic/QC, human findings, exact-byte export and independent verification axes, plus tamper-copy demonstration.
- Integration: replace test-only APPROVED mission creation in end-to-end tests with the real analyst/supervisor workflow. Preserve field motion/capture behavior, the existing command transaction and event mechanisms, and all five human-only gates.
- Tests: use real session cookies and command handlers for workflow tests, independent arithmetic/byte checks for lab/export tests, and real core/science/field/ops/cases behavior for end-to-end scenarios. Controlled public-service fixtures may exercise rare outcome branches; identify these separately from real-world simulations and qualification evidence.
- AI model training, new screens, the 3D environment, physical hardware/SMS, Windows qualification and later gameplay remain separate workstreams. Missing models must be reported honestly; fixture output is not trained-model evidence.

## Proposed scenario coverage matrix

These are **40 test families**, not 40 completed tests, an exhaustive case count, or an accuracy claim. Each family expands into the relevant success, failure and boundary variants. Existing tests may supply part of the coverage; inspect them before adding duplicates.

| ID | Situation | Required observation |
|---|---|---|
| O01 | First trigger, later trigger, repeated delivery | Open one investigation per run; attach later triggers; no duplicate automatic side effects |
| O02 | Survey without alarm, with/without an anomaly reference | Analyst can initiate the survey path; no warning areas invented for a survey |
| O03 | Analysis and learned ordering returned by public services | Persist the returned outcomes, candidates, exclusions and limitations; ordering cannot restore rejected candidates or remove UNKNOWN |
| O04 | Learned model absent, not applicable or failed | Report the appropriate unavailable/status result; no fabricated trained-model output |
| O05 | INVALID, NO_SIGNAL, UNKNOWN_OR_MISMATCH, ambiguous and provisional-single analyses | Preserve all five as legitimate distinct outcomes; no forced successful attribution |
| O06 | BEST_ACTION, NO_USEFUL_MEASUREMENT and PENDING planner results | Only the usable recommendation can lead to a proposal; PENDING has no recommended action |
| O07 | Feasible, infeasible, stale or foreign action in a manual proposal | Only an action allowed by the latest proposal/survey can create the mission |
| O08 | Automatic proposing enabled/disabled; best versus non-best action | Automation respects its setting and proposes only best_action_id |
| O09 | Current versus expired approval; correct versus changed dependency hash | Human supervisor approval checks state, time and current dependencies |
| O10 | Human rejection, missing reason and wrong mission state | Apply only a valid rejection; preserve its reason and prior history |
| O11 | New analysis or changed wind after approval, before departure | Expire the unstarted approval when required; never silently reuse obsolete authority |
| O12 | Expiry and wind-bucket boundaries | Test immediately before/at/after declared time and rounding boundaries without changing the defined policy |
| O13 | Valid/rejected capture evidence returned from the same versus another mission/run | Link only the correct evidence and rerun analysis through the command path |
| O14 | Board decision with/without a finding; invalid area or factory-naming notice | Require the human board decision and valid finding; enforce allowed notices and areas |
| C01 | Case intake from VALID, LIMITED and REJECTED screening records | Keep rejected evidence and its limitations visible; do not silently promote it |
| C02 | Unknown screening IDs or mixed-run/investigation references | Reject invalid linkage instead of mixing evidence from different investigations |
| C03 | Specimen mass below, at and above 50 mg; invalid/missing material or method | Enforce the declared minimum and material/method compatibility |
| C04 | Air screening record versus inspector specimen | Distinct identities and authority; an air reading never becomes a material specimen |
| C05 | Custody transfer by the current versus another holder | Require current-holder authority or a recorded permitted exception |
| C06 | Intact, broken and missing seals; absent/refusing recipient | Retain explicit custody exceptions; a matching file hash cannot erase them |
| C07 | Custody correction after a previous transfer | Append the correction and preserve the original bytes/revision/history |
| C08 | Reference lab arithmetic | Independently reproduce 2.0% w/w, RPD approximately 9.5238%, and 90% recovery from the guide inputs |
| C09 | Blank, RPD and recovery limits | Exercise below/at/above each limit from the method profile; retain every applicable failed reason |
| C10 | Missing QC inputs, zero duplicate mean or invalid arithmetic inputs | Missing input stays pending; undefined values stay unavailable with reasons, never fabricated zero or PASS |
| C11 | Hold-time and reporting-limit boundaries | Exercise the declared 300-second hold limit and 0.01% w/w reporting limit; BELOW_LOQ is not zero |
| C12 | Supported, rejected-for-use and inconclusive findings | Enforce evidence-dependent outcome rules and keep all three normal outcomes |
| C13 | Missing limitations/alternatives, banned claims and attempted legal-status override | Apply the finding rules; legal suitability remains NOT_ASSESSED |
| C14 | Manual/automatic sealing, exact revisions and missing cited bytes | Seal only eligible cases and exact stored evidence; honor auto_seal and fail explicitly when required bytes are missing |
| C15 | Verify intact bytes versus altered manifest/member/lineage | Report separate integrity, trust, lineage, time, custody, measurement and legal axes; integrity is not a claim of truth or legal suitability |
| C16 | Tamper a copy, then reverify both copy and original | The changed copy fails the relevant integrity check; original records and blobs stay byte-identical |
| X01 | SC1, SC2 and SC3 through the real integrated backend, automation off/on | Exercise six real lifecycle cases using real mission approval; record actual outcomes without forcing the old narrative |
| X02 | Wrong roles, forged actor payloads and automation attempting each human-only decision | Reject unauthorized authority through the real command bus/session path |
| X03 | Retried command, stale revision and concurrent submissions | Preserve idempotency/check order and append-only revisions; no duplicated records or overwrites |
| X04 | Pause/reset/end while approval, flight, capture or evidence work is active | Preserve run isolation and existing histories; interrupt/revoke the affected live work under the defined lifecycle |
| X05 | Server restart at investigation, approval, capture, finding and export stages | Verify persisted history and recovery; never invent successful completion of interrupted work |
| X06 | Handler/transaction failure between related record changes | Roll back the transaction; no partial saved workflow or event broadcast before commit |
| X07 | Client disconnect/reconnect and delayed duplicate commands | Reconnect from saved public state/cursors; do not repeat a side effect or replay vehicle input |
| X08 | Missing/corrupt blobs and invalid export member paths | Fail safely with precise reasons; preserve original evidence and filesystem boundaries |
| X09 | Hidden truth, contact numbers and private fields at integration/export boundaries | No truth leakage or phone-number exposure; exports retain their allowed evidence scope |
| X10 | Combined faults: stale approval + retry, flow fault + reset, custody exception + tamper, model missing + no useful action | Exercise selected interacting failures; each guard retains its meaning and reports its own limit |

## Broader science stress coverage, kept separate

The matrix above tests workflow behavior. Existing V4 qualification and the completed twelve-cell wind sweep remain immutable evidence with their original domains. They are not replaced or expanded by counting workflow tests.

For future scientific extensions, maintain separate scenario families for nominal/no-event/spike/sustained emissions; wind variation; height/location mismatch; weak emissions; unknown or multiple sources; sensor/flow/clock/link faults; changing backgrounds; and combined disturbances. Select supported domains and predeclare generators/seeds, expected invariants, denominators and evidence boundaries before measuring. Some of these challenges currently require additional implementation; they are not declared supported by this proposal.

For mixtures, preserve **57/60 raw compatible versus 8/60 final exact-support survivors** from the original V4 evaluation. More workflow tests do not repair pair filtering, and learned ordering cannot restore filtered-out explanations. Any statistical/pair-policy change requires its own justified approval and fresh qualification; never tune the world, cutoff or outcomes to make a demonstration succeed.

## How coverage will be measured

- Trace every implemented command, allowed role, state transition and declared boundary to a test and retained result.
- Run relevant automation combinations and selected pairwise fault combinations, plus targeted higher-order combinations for known interactions. Record the combinations actually run; do not claim every possible combination was covered.
- Keep real-simulation, controlled-fixture, numerical-oracle and physical/device results separately labelled. A synthetic workflow pass is not field accuracy or physical validation.
- Keep deterministic development seeds distinct from calibration and any future untouched evaluation. Retain failures, invalid/no-action/inconclusive results and timing; report denominators honestly.
- Run heavy Java/Maven jobs serially, keep at most two active actors/jobs, and obtain independent review before any readiness claim. No commit, merge or push is implied by this proposal.

## Execution and review

Aditya requested the Desktop copy and then planning/completion of the remaining backend. Execute the written backend plan in the existing worktree, preserving inherited changes, with serial heavy verification and independent review. Keep the original backend owners informed through Aditya's sharing of this document; do not send external messages automatically. Scenario/freeze wording and any later API amendments remain specific separate decisions.
