# Backend integration decisions — local implementation handoff draft

This records concrete local implementation decisions for Singhal's six integration questions. It is **not a published contract/API revision, commit, merge approval or claim that his branch has these changes**. Reconcile this with current AGREEMENT/CORE-API/public interfaces and both implementations before sharing an integration checkpoint. Preserve `ops/singhal`; its handlers overlap the locally implemented command names and cannot simply be concatenated.

## 1. Evidence return uses the existing analysis command

Local reviewed implementation uses `run_analysis`, with required `investigation_id` and an optional internal automatic provenance `cause: {type,id,revision}`. Roles remain analyst or automation; subject revision is the investigation. A human analysis uses the displayed investigation revision. An automatic analysis consumes the latest investigation atomically with null expected_revision and records the exact investigation revision it actually consumed plus the exact committed cause revision.

After a committed screening record, the listener resolves the same-run graph:

- screening → attempt and mission, with attempt.mission_id equal to that mission;
- mission → survey → investigation, or mission → proposal → analysis → investigation.

The command atomically saves analysis, proposal and the investigation's next revision. With correctly linked returned screening evidence, it appends screening IDs and saves state/event EVIDENCE_BACK; without evidence it saves ANALYSED. VALID, LIMITED and REJECTED screening records remain traceable; only independently usable measurement windows reach scientific selection. Listeners never save directly. There is no invented standalone evidence-return command.

In-memory pending coalescing and persisted public-input basis suppress repeated two-channel/replayed work. Exact window/screening revisions, survey IDs and wind enter that basis. Consumed trigger membership is checked across all same-run investigations, including CLOSED and older primary-only trigger records. Automatic refusal is recorded/logged and is not retried in a loop.

This is the **implementation's intended contract clarification**, not a statement that old published `run_analysis` wording already spells it out. Include it explicitly in the shared contract handoff.

## 2. Reviewed measurement windows are already owner-approved locally

The CaptureRules.windows amendment was approved and incorporated locally into AGREEMENT/CORE-API; the current local contract version is 2.3, including separately approved ProspectiveInlets forecasting. `CaptureRules.windows(CaptureInput)` returns measured background and complete collection windows with uncertainty through the existing Window type. It makes no human decision and performs no persistence.

Field saves usable windows only when both automatic rules and human review are VALID, in the same transaction as the successful review. Rejected/limited records retain their limitations and never become invented zero measurements. Collection enhancement is the measured collection minus measured background; prediction models the same temporal contrast and instrument memory. Do not calculate uncertainties in Operations or assume target background is clean air.

These changes are local/uncommitted and were not available to Singhal's old-main checkout. His request for a published version remains valid.

## 3. Investigation and survey storage

Use the existing generic append-only RecordStore. Investigation retains first `trigger_id`/`survey_id` plus durable `trigger_ids[]`/`survey_ids[]`, `analysis_ids[]`, `screening_record_ids[]`, warning areas and exact intake/basis provenance. At most one non-CLOSED investigation is active per run. New triggers attach rather than create another active investigation; previously consumed triggers never move into a later investigation.

The server creates a separate `survey` record with investigation_id, action_id, analyst reason, optional anomaly_id, public vehicle ID/kind, target, method and channels. The anomaly must exist in that run. Surveys without an alarm create no warning areas. The catalogue is public and uniform across presets; it does not inspect truth or claim planner benefit.

## 4. Mission fields, dependency encoding and departure authority

Planner basis requires a feasible selected action from the current BEST_ACTION proposal/analysis. Automatic proposing additionally requires auto_propose and best_action_id. A separately analyst-scheduled survey is an independent basis and may be used when the planner reports NO_USEFUL_MEASUREMENT. PENDING never supplies a recommendation.

Use actual public vehicle identity; save target, complete declared route, method/channels, PROPOSED, pilot NONE, proposed_s and expires_s = proposed_s + 300. Public planner actions currently have target but no full route/method/channel fields. Local implementation uses an explicitly recorded route when present, otherwise declares `[target]` as waypoints for Field's existing safe-path planning; it uses the declared teaching gas method/channels, without fabricating planner route cost or usefulness.

Dependency object version `FP-MISSION-DEPS-1` binds run/investigation identity, exact basis ref, current analysis/proposal refs, available exact input-window/screening refs, action, vehicle, target, ordered route, method, channels and wind buckets. Object keys sort lexically; numbers use plain minimal decimal representation; arrays retain order; JSON is compact UTF-8; SHA-256 is lowercase hexadecimal. This is a specified application encoding, not an asserted RFC8785 implementation.

Wind direction is normalized to [0,360), then bucket index `floor((direction + 5)/10) % 36`. Wind speed bucket index is `floor(speed*2 + 0.5)`. Nonfinite/missing inputs fail. Bucket indices represent 10° and 0.5m/s bins respectively, with midpoint rounding upward and circular wrap. The retained literal independent digest vector is in external `workflow-backend/task-3-digest-vector.json`; share the exact vector with any independent implementation rather than hashing informal prose.

Supervisor approval checks saved state, run/time/vehicle, supplied hash and recomputed current authority. An internal read-only `MissionAuthority` port now lets Field recheck the same authority on the serialized command thread before initial acquire_lease, start_autopilot or take_over. Missing/error/null validation refuses departure. This closes the race where departure was already queued ahead of automatic expiry. Legitimately started flights and safety return retain existing rules. The original V4 runtime did not contain this workflow repair; exact reviewed deltas are recorded separately.

## 5. V200 is not required for generic records

The current local implementation uses existing RecordChange/RecordStore transactions, so no duplicate Operations state table or V200 migration is needed. The existing core key is UNIQUE(type,id,revision). Singhal's preserved disabled migration references only (id,revision), which is invalid against that key. Keep it disabled; never alter core uniqueness to accommodate it.

If a projection is later justified, it needs a local record-type discriminator constrained to investigation and a full composite foreign key. Such a projection requires its own reviewed design/migration, not activation of the broken file. Neither branch's history should be deleted.

## 6. Board metric definitions — proposed, not yet implemented

The current backend has no verified time-spent/repeated-mission calculation. Keep unavailable values unknown until the agreed implementation and tests exist. Proposed operational definitions for the shared handoff:

- **Investigation duration:** elapsed simulation seconds from the first investigation event to its first human finding event. Open investigations have an unfinished duration, not a completed zero. Paused simulation contributes no simulated time; wall-clock effort and time scaling are separate concepts. Corrected findings do not reset the start or erase the first finding.
- **Repeated missions:** additional actually started mission IDs after the first start for one investigation, counted once per mission at its first APPROVED→LEASED transition. Replayed events, repeated commands, proposals rejected before start, pilot handovers and resumed leases do not create another mission count.

These are explicit proposed display/report semantics. Do not silently relabel them as human labor time, accuracy, efficiency improvement or a measured product result. Final shared-contract publication must identify the exact accepted definitions and unavailable cases.

## Verification and integration limit

Local Task2 Operations and Task3 mission authority passed independent spec/quality review after documented fixes. Latest affected Task3 slice: 182 tests, zero failures/errors/skips, including six real empty-data survey→human approval→Field lease integrations. These are not Singhal's branch tests or the later six full-flight/capture/Cases S7 scenarios. Do not sum overlapping test slices or claim the whole backend is complete.

No external message, branch merge, contract publication, commit, push, deployment or freeze was performed by preparing this draft.
