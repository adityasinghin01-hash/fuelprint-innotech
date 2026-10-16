# 09 — Work packages and complete traceability

All paths are future repository outputs. No application code exists in this plan. Work orders are authoring/test instructions; their hours are shares inside each task, not extra effort. Gate reviews consume the30%gross reserve. Legacy T01–T12/A–E allocations are provenance only.

## W01 — Frozen contracts and clean environment

Owner O1,reviewer C1;10–14focused hours;dependencies none;gate M1. [Complete work package](work-packages/W01.md).

Interface: ContractRegistry -> generated TS / strict Python validation

Future outputs: packages/contracts/schemas/; packages/contracts/fixtures/; package.json; package-lock.json; pyproject.toml; uv.lock; tests/contracts/test_stack.py; tests/contracts/test_stack.py; tests/integration/test_review_regressions.py

## W02 — Relational records and durable blob journal

Owner C1,reviewer O1;10–14focused hours;dependencies none;gate M1. [Complete work package](work-packages/W02.md).

Interface: append_revision(ref,payload) -> immutable Ref; put_blob(bytes) -> BlobRef

Future outputs: backend/storage/migrations/001_identity.sql; backend/storage/migrations/002_observations.sql; backend/storage/migrations/003_missions.sql; backend/storage/migrations/004_cases.sql; backend/storage/migrations/005_evidence.sql; backend/storage/migrations/006_analysis.sql; backend/storage/db.py; backend/storage/blobs.py; tests/authority/test_storage.py; tests/authority/test_transactions.py; tests/authority/test_ingest_recovery.py

## W03 — Authenticated command authority and durable jobs

Owner O1,reviewer C2;12–16focused hours;dependencies W01,W02;gate M1. [Complete work package](work-packages/W03.md).

Interface: submit(Command,ActorContext) -> Receipt; run_job(JobSnapshot) -> JobResult

Future outputs: backend/authority/commands.py; backend/authority/auth.py; backend/authority/jobs.py; backend/api/commands.py; tests/authority/test_transactions.py; tests/authority/test_concurrent_commands.py; tests/integration/test_review_regressions.py

## W04 — Single clock, committed projection and restart

Owner C1,reviewer O1;12–18focused hours;dependencies W02;gate M1. [Complete work package](work-packages/W04.md).

Interface: advance_tick() -> TickBatch; snapshot(scope) -> Snapshot; recover() -> RecoveryReport

Future outputs: backend/authority/clock.py; backend/authority/projections.py; backend/authority/recovery.py; backend/api/events.py; tests/authority/test_snapshot.py; tests/authority/test_snapshot.py; tests/integration/test_first_milestone.py; tests/authority/test_ingest_recovery.py; tests/browser/causal-scenes.spec.ts; tests/security/test_truth_leakage.py; tests/browser/phone-control.spec.ts; tests/authority/test_compound_recovery.py; tests/integration/test_compound_faults.py

## W05 — Three-outcome fixture producer and online capture

Owner O2,reviewer C2;8–12focused hours;dependencies W01;gate M1. [Complete work package](work-packages/W05.md).

Interface: ObservationAdapter.read_interval() -> RawFrame[]; review(AttemptRef,use) -> ScreeningRecord

Future outputs: backend/observations/fixture_adapter.py; backend/measurements/capture.py; backend/measurements/qc.py; profiles/teach-m1-online-1.json; tests/integration/test_capture.py; tests/integration/test_first_milestone.py

## W06 — Operations first investigation workflow

Owner O2,reviewer C1;6–8focused hours;dependencies W05;gate M1. [Complete work package](work-packages/W06.md).

Interface: committed Projection -> observation / approval / outcome views

Future outputs: apps/operations/src/routes/Health.tsx; apps/operations/src/routes/Investigation.tsx; apps/operations/src/routes/Approval.tsx; apps/operations/src/routes/Board.tsx; tests/browser/m1-operations.spec.ts; tests/integration/test_first_milestone.py

## W07 — Field first capture and QC workflow

Owner A,reviewer O2;6–8focused hours;dependencies W01;gate M1. [Complete work package](work-packages/W07.md).

Interface: MissionProjection -> capture intents; committed inlet/path -> DOM + minimal scene

Future outputs: apps/field/src/routes/Mission.tsx; apps/field/src/routes/Capture.tsx; apps/field/src/scene/InletMarker.ts; tests/browser/m1-field.spec.ts

## W08 — Cases first intake, finding and vault

Owner C2,reviewer C1;5–7focused hours;dependencies W02;gate M1. [Complete work package](work-packages/W08.md).

Interface: ScreeningRefs -> CaseRef -> FindingRef -> SealRequest

Future outputs: apps/cases/src/routes/Intake.tsx; apps/cases/src/routes/Finding.tsx; apps/cases/src/routes/Vault.tsx; backend/cases/findings.py; tests/browser/m1-cases.spec.ts

## W09 — Minimal exact-byte pack and separate verifier

Owner C1,reviewer O2;5–7focused hours;dependencies W03,W04,W05,W08;gate M1. [Complete work package](work-packages/W09.md).

Interface: seal(CaseRef) -> ExportRef; verify(file,TrustPolicy) -> VerificationReport

Future outputs: backend/evidence/pack.py; verifier/__main__.py; verifier/archive.py; verifier/trust.py; tests/security/test_evidence_minimal.py; tests/security/test_evidence.py

## W10 — Integrated three outcomes, restart and replay gate

Owner C2,reviewer O1;2–4focused hours;dependencies W03,W04,W06,W07,W08,W09;gate M1. [Complete work package](work-packages/W10.md).

Interface: three real API/browser journeys + killed authority -> reproducible public record

Future outputs: tests/integration/test_first_milestone.py; tests/browser/first-milestone.spec.ts; release/m1-record.json; tests/integration/test_review_regressions.py

## W11 — Geometry, wind and persistent puff history

Owner O2,reviewer A;18–26focused hours;dependencies W10;gate M2. [Complete work package](work-packages/W11.md).

Interface: step_world(WorldState,PublicInputs,PrivateGenerator,dt=.05) -> private state + allowed observations

Future outputs: backend/science/geometry.py; backend/science/world.py; backend/science/transport.py; profiles/live-exhibition-1.json; profiles/offline-eval-1.json; tests/science/test_history.py; tests/science; tests/science/test_clock.py; tests/contracts/test_inventory_provenance.py; tests/integration/test_coverage_placement.py; tests/science/test_units_mass_history.py; tests/integration/test_compound_faults.py; tests/evaluation/test_compound_sources.py; tests/science/test_overgas_recovery.py; tests/integration/test_review_regressions.py

## W12 — Branch transport, response, noise and covariance

Owner O2,reviewer C1;18–26focused hours;dependencies W11;gate M2. [Complete work package](work-packages/W12.md).

Interface: sample(InletHistory,MethodProfile) -> per-channel intervals; covariance(WindowRefs) -> CovarianceRef

Future outputs: backend/science/inlet.py; backend/science/response.py; backend/science/uncertainty.py; backend/measurements/windows.py; tests/science/test_channel_uncertainty.py; tests/science; tests/science/test_clock.py; tests/science/test_channel_uncertainty.py; tests/science/test_flow_paths.py; tests/integration/test_media_lab.py; tests/science/test_units_mass_history.py; tests/integration/test_compound_faults.py; tests/evaluation/test_compound_sources.py; tests/science/test_overgas_recovery.py; tests/integration/test_calibration_path.py; tests/integration/test_review_regressions.py

## W13 — Conservative compatibility model and solver

Owner O2,reviewer C1;18–28focused hours;dependencies W12;gate M3. [Complete work package](work-packages/W13.md).

Interface: analyze(PublicAnalysisSnapshot) -> Analysis; no private imports

Future outputs: backend/science/candidate_kernel.py; backend/science/compatibility.py; backend/science/qualification.py; tests/evaluation/test_reasoning.py; tests/evaluation/test_reasoning.py; tests/security/test_truth_leakage.py; tests/evaluation/test_identifiability.py; tests/integration/test_outcomes.py; tests/science/test_channel_uncertainty.py; tests/science/test_flow_paths.py; tests/evaluation/test_protocol_metrics.py; tests/integration/test_compound_faults.py; tests/evaluation/test_compound_sources.py; tests/science/test_overgas_recovery.py; tests/integration/test_review_regressions.py

## W14 — Feasible action catalog and conservative utility

Owner O1,reviewer O2;14–20focused hours;dependencies W12,W13;gate M3. [Complete work package](work-packages/W14.md).

Interface: plan(AnalysisRef,AccessRef,MethodRef,VehicleRef) -> Proposal[] | NoUsefulMeasurement

Future outputs: backend/planning/routes.py; backend/planning/procedures.py; backend/planning/interval_bound.py; tests/integration/test_plans.py; tests/integration/test_plans.py; tests/integration/test_ground_missions.py; tests/integration/test_aerial_limits.py; tests/integration/test_review_regressions.py

## W15 — Complete Operations reasoning, placement and board

Owner O1,reviewer C2;14–20focused hours;dependencies W14;gate M3. [Complete work package](work-packages/W15.md).

Interface: public detector and proposal citations -> explicit observations, alternatives, approval and board

Future outputs: apps/operations/src/routes/Placement.tsx; apps/operations/src/routes/Scenarios.tsx; apps/operations/src/components/Explanation.tsx; backend/operations/detector.py; backend/operations/explanations.py; tests/browser/operations.spec.ts; tests/integration/test_plans.py; tests/browser/operations.spec.ts

## W16 — Provisioned adapter and conditional hardware profiles

Owner C2,reviewer O1;8–12focused hours;dependencies W10;gate M2. [Complete work package](work-packages/W16.md).

Interface: ingest(DeviceCredential,RawFrameBatch) -> durable receipt + gaps / quarantine

Future outputs: backend/adapters/ingest.py; backend/adapters/clock_mapping.py; profiles/hardware/; tests/integration/test_ingestion.py; qualification/packet-template.md; tests/science/test_hardware_profiles.py; tests/science/test_energy_recovery.py; tests/integration/test_cost_capacity_claims.py; tests/contracts/test_bench_separation.py; tests/integration/test_review_regressions.py

## W17 — Causal 3D world, routes, vehicles and honest overlays

Owner A,reviewer O2;22–32focused hours;dependencies W11,W12;gate M4. [Complete work package](work-packages/W17.md).

Interface: committed public scene projection -> render; UI intent -> authorized command

Future outputs: apps/field/src/scene/WorldScene.ts; apps/field/src/scene/ContextLayer.ts; apps/field/src/scene/VehicleLayer.ts; apps/field/src/scene/PredictionLayer.ts; apps/field/src/scene/GeometryTransform.ts; tests/browser/causal-scenes.spec.ts; tests/browser/causal-scenes.spec.ts

## W18 — Instrument cutaways, sampling and lab workbench

Owner A,reviewer C2;10–16focused hours;dependencies W17,W22,W23;gate M5. [Complete work package](work-packages/W18.md).

Interface: per-branch state and actual LabResultRef -> visible quality/mass/flow/holding

Future outputs: apps/field/src/scene/InstrumentCutaway.ts; apps/field/src/scene/LabWorkbench.ts; apps/field/src/routes/Instrument.tsx; tests/browser/workbench.spec.ts; tests/browser/causal-scenes.spec.ts; tests/integration/test_specimen_lab.py

## W19 — Pairing, queue and control backend

Owner C2,reviewer O1;8–12focused hours;dependencies W10;gate M4. [Complete work package](work-packages/W19.md).

Interface: confirm challenge -> scoped session; acquire/release lease; control_input -> committed motion

Future outputs: backend/control/pairing.py; backend/control/leases.py; backend/control/deadman.py; tests/authority/test_control.py; tests/browser/phone-control.spec.ts

## W20 — Phone and keyboard interaction

Owner A,reviewer C2;8–12focused hours;dependencies W17,W19;gate M4. [Complete work package](work-packages/W20.md).

Interface: pointer/keyboard intent at10Hz -> lease channel; neutral on lost focus/pointer/session

Future outputs: apps/field/src/routes/Controller.tsx; apps/field/src/routes/Queue.tsx; apps/field/src/control/InputController.ts; tests/browser/phone-control.spec.ts; tests/browser/causal-scenes.spec.ts

## W21 — Accessibility and actual-device acceptance

Owner C2,reviewer A;4–6focused hours;dependencies W15,W20,W25,W18;gate M7. [Complete work package](work-packages/W21.md).

Interface: supported devices + DOM/2D fallback -> measured capability matrix

Future outputs: tests/browser/accessibility.spec.ts; release/device-inventory.json; release/device-measurements.csv; tests/browser/phone-control.spec.ts

## W22 — Separate specimen identity and custody

Owner C2,reviewer C1;12–18focused hours;dependencies W10;gate M5. [Complete work package](work-packages/W22.md).

Interface: create_specimen(CaseRef,MaterialRequest) -> SpecimenRef; append_transfer -> CustodyRef

Future outputs: backend/cases/specimens.py; backend/cases/custody.py; tests/integration/test_specimen_lab.py; tests/integration/test_specimen_lab.py; tests/integration/test_review_regressions.py

## W23 — Material and retained-media laboratory QC

Owner C2,reviewer O2;12–18focused hours;dependencies W22,W12;gate M5. [Complete work package](work-packages/W23.md).

Interface: LabSubmission(subject:SpecimenRef|MediaRef,MethodRef,QCInput) -> LabResult

Future outputs: backend/lab/material_adapter.py; backend/lab/calculations.py; backend/lab/qc.py; profiles/teach-aerosol-s-1.json; profiles/teach-fuel-s-1.json; tests/science/test_laboratory_qc.py; tests/integration/test_specimen_lab.py; tests/integration/test_review_regressions.py

## W24 — Full archive, trust, lineage and adversarial verifier

Owner C1,reviewer O1;14–20focused hours;dependencies W09,W16,W23;gate M5. [Complete work package](work-packages/W24.md).

Interface: bounded raw-preserving archive -> independent seven-axis report

Future outputs: backend/evidence/chunks.py; backend/evidence/lineage.py; verifier/limits.py; verifier/lineage.py; tests/security/test_evidence.py; tests/security/test_evidence.py; tests/authority/test_ingest_recovery.py; tests/browser/causal-scenes.spec.ts; tests/security/test_truth_leakage.py; tests/browser/phone-control.spec.ts; tests/authority/test_compound_recovery.py

## W25 — Complete Cases UI and read-only replay

Owner C1,reviewer C2;8–12focused hours;dependencies W22,W23,W24;gate M5. [Complete work package](work-packages/W25.md).

Interface: case/specimen/media/lab/evidence refs -> limited finding + immutable replay

Future outputs: apps/cases/src/routes/Specimen.tsx; apps/cases/src/routes/Custody.tsx; apps/cases/src/routes/Laboratory.tsx; packages/projections/src/replay.ts; tests/browser/cases.spec.ts; tests/security/test_evidence.py; tests/integration/test_backup_restore.py

## W26 — Development/calibration generator and protocol freeze

Owner C1,reviewer O2;10–14focused hours;dependencies W13,W14,W16;gate M6. [Complete work package](work-packages/W26.md).

Interface: public80dev + separate80cal -> frozen config/cutoff; private finals disabled until freeze

Future outputs: evaluation/generator.py; evaluation/policies.py; evaluation/calibration.py; evaluation/protocol.json; tests/evaluation/test_protocol_metrics.py; tests/evaluation/test_protocol_metrics.py; tests/integration/test_review_regressions.py

## W27 — Stratified final evaluation and claim report

Owner C1,reviewer C2;8–12focused hours;dependencies W26;gate M6. [Complete work package](work-packages/W27.md).

Interface: private240final across5policies -> all outcomes, descriptive rates and bounded comparisons

Future outputs: evaluation/compare.py; evaluation/metrics.py; evaluation/claims.py; release/evaluation-report.md; tests/evaluation/test_protocol_metrics.py; tests/integration/test_compound_faults.py; tests/integration/test_review_regressions.py

## W28 — Optional separate IID confirmation pool

Owner C1,reviewer O2;4–8focused hours;dependencies W27;gate M6. [Complete work package](work-packages/W28.md).

Interface: independently sampled240IID -> separately gated safety claim or withheld

Future outputs: evaluation/iid_confirmation.py; release/iid-confirmation-report.md; tests/evaluation/test_protocol_metrics.py; tests/integration/test_review_regressions.py

## W29 — Backup, restore, offline launch and recovery rehearsal

Owner C2,reviewer O1;10–16focused hours;dependencies W18,W20,W21,W25,W27;gate M7. [Complete work package](work-packages/W29.md).

Interface: verified snapshot+blobs on stopped-host takeover -> supported release rehearsal

Future outputs: backend/launch.py; backend/storage/backup.py; tests/integration/test_backup_restore.py; tests/browser/rehearsal.spec.ts; release/rehearsal-report.md; tests/browser/rehearsal.spec.ts

## W30 — Traceability, budgets, claims and release handoff

Owner O1,reviewer C1;4–6focused hours;dependencies W29,W28;gate M7. [Complete work package](work-packages/W30.md).

Interface: actual artifact/fixture/device hashes + explicit exclusions -> honest release decision

Future outputs: release/feature-matrix.json; release/claims.md; release/costs.csv; release/build-manifest.json; tests/integration/test_ground_missions.py; tests/integration/test_aerial_limits.py; tests/integration/test_review_regressions.py


## All92questions — selected implementation map

Full row originals, test setup/assertion, exact oracle, cost consequence and remaining physical experiment are preserved in registers/questions-92-trace.json/CSV. The full work packages inline assigned acceptance. Nothing here is an executed result.

|Question|Selected R3 decision|Tasks / owner / reviewer|Acceptance|Cash stages / physical gates|
|---|---|---|---|---|
|FP01 Can the intended source/fuel decision be identified from the selected channels at all?|Separate fuel identifiability from source compatibility; independent gas amplitudes and hidden-label invariance.|W13,W14,W23,W27 / O2 / C1|AT-FP01;unsupported=YES|A;C;D / P3;P4|
|FP02 Can allowed fuel plus process emissions mimic the suspected anomaly?|Allowed-fuel/process alternatives remain admissible; a compatible plume is not fuel proof.|W13,W14,W23,W27 / O2 / C1|AT-FP02;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|
|FP03 Can scrubbing, ash retention or mixed fuels hide the expected signature?|Independent emissions and scrubber/process uncertainty; absence of SO2 never establishes compliance.|W13,W14,W23,W27 / O2 / C1|AT-FP03;unsupported=YES|A;C;D / P3;P4|
|FP04 Can a legitimate unknown outcome still help the investigator decide what to do?|Save an inconclusive finding and uncertainty-reduction/scheduled-survey action without promising discrimination.|W13,W14,W23,W27 / O2 / C1|AT-FP04;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P3;P4|
|FP05 What exact conditions and uncertainty make the first physical product useful?|First ground objective is a reference-backed SO2 enhancement with a predeclared differential uncertainty target; no custom-node substitution.|W13,W14,W23,W27 / O2 / C1|AT-FP05;unsupported=YES|A;C;D / P0;P3;P4|
|FP06 What if a stack is missing, misplaced or at an unknown height?|Fictional nominal xy uncertainty is zero; known nonzero/unknown physical source-position uncertainty withholds quantitative discrimination; mismatch challenges remain blind.|W11,W16,W26,W30 / O2 / A|AT-FP06;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P3;P4|
|FP07 What if exhaust exits through a bypass, roof vent or intermittent process?|Unregistered exits/source schedules are explicit model-mismatch families; no exhaustive-inventory claim.|W11,W16,W26,W30 / O2 / A|AT-FP07;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P3;P4|
|FP08 What source/activity/fuel records are reliable enough for blinded evaluation?|Separate private evaluation truth from independently documented physical source/activity records.|W11,W16,W26,W30 / O2 / A|AT-FP08;unsupported=YES|A;C;D / P0;P3;P4|
|FP09 Are coordinates, ground/roof/inlet elevations and reference datums consistent?|One ENU frame; ground, roof, vehicle and inlet heights stored separately; Three east/up/minus-north mapping.|W11,W16,W26,W30 / O2 / A|AT-FP09;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P3;P4|
|FP10 Do real source identifiers or activity records create access/privacy constraints?|Restrict actual site/source records and require HTTPS physical profile; fictional exhibition inventory is public.|W11,W16,W26,W30 / O2 / A|AT-FP10;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P3;P4|
|FP11 What if wind carries the plume away from every box?|Finite-history world retains older mass; no event at nodes is a limited observation, not a compliance conclusion.|W11,W14,W15,W17,W27 / O2 / A|AT-FP11;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP12 What if wind is calm or turns while older pollution remains downwind?|Puff history persists through wind changes; steady candidate model is explicitly mismatched and can abstain/fail.|W11,W14,W15,W17,W27 / O2 / A|AT-FP12;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP13 What if roof, street and stack-height winds differ?|Local meteorology is a selected measurement interface, not proof one wind vector represents roof/stack flow.|W11,W14,W15,W17,W27 / O2 / A|AT-FP13;unsupported=YES|A;C;D;E / P0;P3;P4|
|FP14 What if a hot plume passes over all accessible inlet heights?|Different inlet heights and effective H are explicit; aerial sampling requires actual qualified mission benefit.|W11,W14,W15,W17,W27 / O2 / A|AT-FP14;unsupported=YES|A;C;D;E / P0;P3;P4|
|FP15 What if a building wake, inversion or external plume breaks the assumed model?|Off-family wakes/inversions/external plumes remain unknown/model-domain limitations; Gaussian teaching is not CFD qualification.|W11,W14,W15,W17,W27 / O2 / A|AT-FP15;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP16 Can every proposed mount be accessed, permitted, anchored and serviced?|Ground mount permissions/service/anchor details are a physical release gate; fictional allowed mounts cannot grant real access.|W11,W14,W15,W17,W27 / O2 / A|AT-FP16;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP17 Does another box add discrimination or duplicate the same blind sector?|Node placement changes observations; action bound can be zero for redundant or nested explanations.|W11,W14,W15,W17,W27 / O2 / A|AT-FP17;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP18 What if the best theoretical position is on private land or unsafe infrastructure?|Planner filters access, route, method and return before utility; inaccessible high-value sites remain rejected alternatives.|W11,W14,W15,W17,W27 / O2 / A|AT-FP18;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP19 Can a lower-cost layout still meet a defined seasonal coverage objective?|Affordable seasonal coverage is a physical survey outcome, not inferred from four fictional nodes.|W11,W14,W15,W17,W27 / O2 / A|AT-FP19;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP20 What happens when no affordable accessible layout is adequate?|Return NO_USEFUL_MEASUREMENT or insufficient coverage and stop expansion; no arbitrary spend guarantees coverage.|W11,W14,W15,W17,W27 / O2 / A|AT-FP20;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P0;P3;P4|
|FP21 Can the exact SO2/CO2 instruments resolve the intended enhancement above background?|GMP252 low-range absolute accuracy and Mini2 full-scale repeatability cannot be relabeled enhancement resolution; reference access first.|W12,W13,W16,W17 / O2 / C1|AT-FP21;unsupported=YES|A;C;D;E / P2;P3|
|FP22 What if an interferent depresses, reverses or saturates a gas response?|Retain negative SO2 cross-interference and overrange flags; low reading cannot be repaired by truth labels.|W12,W13,W16,W17 / O2 / C1|AT-FP22;unsupported=YES|A;C;D;E / P2;P3|
|FP23 What if the event is shorter than instrument response or recovery?|Versioned line/chamber/sensor cascade, purge, settling and recovery; no collection solely on a universal 40s timer.|W12,W13,W16,W17 / O2 / C1|AT-FP23;unsupported=YES|A;C;D;E / P2;P3|
|FP24 How do drift, pressure and temperature affect differential measurements?|Temperature/pressure/calibration provenance and differential uncertainty; catalog typical accuracy is not the assembled error budget.|W12,W13,W16,W17 / O2 / C1|AT-FP24;unsupported=YES|A;C;D;E / P2;P3|
|FP25 Which accuracy terms cancel in background subtraction and which remain correlated?|Signal/background cross-covariance is explicit and not counted again as bounded bias; unquantified covariance disables quantitative claims.|W12,W13,W16,W17 / O2 / C1|AT-FP25;unsupported=YES|A;C;D;E / P2;P3|
|FP26 Does the inlet/filter/tubing remove, delay or contaminate the gas of interest?|Actual gas-path materials, blank and step recovery must qualify the selected sulfur chain; bag retention remains disabled.|W12,W13,W16,W17 / O2 / C1|AT-FP26;unsupported=YES|A;C;D;E / P1;P2;P3|
|FP27 Can a pump maintain each branch's required flow against loaded restrictions?|Two NMP015 12V brushless pumps, constant-voltage on/off and downstream restriction; pressure-flow performance requires assembled testing.|W12,W13,W16,W17 / O2 / C1|AT-FP27;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P2;P3|
|FP28 Can chosen flow meters resolve the tolerance at actual pressure/temperature?|Two SFM5500-2slm SHDLC links; actual gas/pressure/temperature conversion and low-flow uncertainty, not nominal FS alone.|W12,W13,W16,W17 / O2 / C1|AT-FP28;unsupported=YES|A;C;D;E / P1;P2;P3|
|FP29 What if a leak, kink, blocked filter or valve diverts the sample?|Versioned flow/diagnostic flags invalidate affected intervals and retain failure records; no silent interpolation through blockage.|W12,W13,W16,W17 / O2 / C1|AT-FP29;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P2;P3|
|FP30 How is transport time tracked during changing flow and stop/restart?|Volume-displacement transport tracking and bounded clock mapping; preserve flush/restart history and per-conversion time.|W12,W13,W16,W17 / O2 / C1|AT-FP30;unsupported=YES|A;C;D;E / P1;P2;P3|
|FP31 Does the inlet actually provide the intended PM size selection?|Unclassified teaching aerosol stays unclassified; PM2.5 requires an actual qualified aerodynamic selector.|W12,W23,W24 / O2 / C1|AT-FP31;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P2;P3;P4|
|FP32 Do aspiration, bends, electrostatic loss or humidity alter the collected aerosol?|Aspiration, deposition, humidity and material recovery are actual chain measurements; generic filter efficiency is a teaching oracle only.|W12,W23,W24 / O2 / C1|AT-FP32;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P2;P3;P4|
|FP33 Is sufficient total and analyte-specific mass collected in the feasible mission time?|Analyte mass/LOQ separate from total aerosol mass; full method time and below-LOQ events remain counted.|W12,W23,W24 / O2 / C1|AT-FP33;unsupported=YES|A;C;D;E / P2;P3;P4|
|FP34 Are blanks, duplicates, weighing/analysis LOQs and contamination controls adequate?|Distinct blank, duplicate, spike/recovery and reporting thresholds; material-specific raw/corrected laboratory values persist.|W12,W23,W24 / O2 / C1|AT-FP34;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P2;P3;P4|
|FP35 Does each gas bag/filter/container preserve the target until its actual analysis?|Physical reactive-gas retention is disabled until recovery/holding conditions qualify; teaching 300s hold is not laboratory authority.|W12,W23,W24 / O2 / C1|AT-FP35;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P2;P3;P4|
|FP36 Are all rail voltages, logic levels, current peaks and pin interfaces compatible?|Exact S3 N8R8 pin map excludes GPIO22/flash/PSRAM/strap; selected UART/I2C/SPI and voltage domains replace generic ESP assumptions.|W16,W04,W30 / C2 / O1|AT-FP36;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;B;C;E / P1;P2|
|FP37 Is input-referred ADC/AFE noise adequate after real gain, bandwidth and drift?|ADS1220 gain1 PGA bypass, REF5040 4.096V, defined analog rails/filter; actual ISB gain/common-mode/noise still a fabrication release artifact.|W16,W04,W30 / C2 / O1|AT-FP37;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;B;C;E / P1;P2|
|FP38 Are grounding, isolation, EMI, lightning/surge and power protection appropriate?|Each field instrument needs an isolated 24V domain as well as isolated transceiver/5V; common sensor returns defeat isolation.|W16,W04,W30 / C2 / O1|AT-FP38;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;B;C;E / P1;P2|
|FP39 Can rain, dust, insects, heat, corrosion or condensation defeat the enclosure/inlet?|Enclosure/thermal/condensation/EMI/service design remains measured qualification; no indoor component rating becomes outdoor IP qualification.|W16,W04,W30 / C2 / O1|AT-FP39;unsupported=YES|A;B;C;E / P1;P2|
|FP40 Can parts be assembled, inspected, replaced and calibrated without contaminating paths?|Separate calibration/serviceable wetted paths and contamination control; supplier gain/material configuration requires documented inspection.|W16,W04,W30 / C2 / O1|AT-FP40;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;B;C;E / P1;P2|
|FP41 What are startup, continuous and worst-case simultaneous loads?|Selected custom node uses 18W continuous and 24W peak allocation including field power and conversion; reference/gateway power separate.|W16,W04,W30 / C2 / O1|AT-FP41;unsupported=YES|A;C;D;E / P1;P2;P3|
|FP42 How long does the aged cold battery actually support measurement and communications?|512Wh derating yields 331.776Wh:18.432h at18W or13.824h at24W before separately powered gateway.|W16,W04,W30 / C2 / O1|AT-FP42;unsupported=YES|A;C;D;E / P1;P2;P3|
|FP43 What if several foggy days cause daily solar deficit?|432Wh/day exceeds207Wh/day illustrative fog harvest; starting system uses mains-backed power, no solar autonomy promise.|W16,W04,W30 / C2 / O1|AT-FP43;unsupported=YES|A;C;D;E / P1;P2;P3|
|FP44 How long does the battery take to recover while the node continues working?|Recharge surplus must be positive after simultaneous load; zero/negative surplus cannot yield a finite recovery time.|W16,W04,W30 / C2 / O1|AT-FP44;unsupported=YES|A;C;D;E / P1;P2;P3|
|FP45 What is lost or disabled during brownout, load shedding and overdue battery service?|Protected regulation and brownout interruption preserve acknowledged records, revoke control and require readiness/reapproval.|W16,W04,W30 / C2 / O1|AT-FP45;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P2;P3|
|FP46 Can the link carry required traffic at the actual site, including outage backlog?|Bounded frame batches, raw retention and explicit backlog/gaps; actual site throughput/storage checks are release tests.|W02,W04,W16,W24,W29 / C1 / O1|AT-FP46;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P3|
|FP47 Are gaps, retries, duplicate frames and reboots unambiguously represented?|Device+boot+sequence identity, byte-preserving duplicate/conflict handling and immutable gap events.|W02,W04,W16,W24,W29 / C1 / O1|AT-FP47;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P3|
|FP48 What happens when device clock or position uncertainty corrupts alignment?|MCU monotonic to gateway bounded mapping; capture/receive/UTC uncertainty and pose uncertainty remain independent fields.|W02,W04,W16,W24,W29 / C1 / O1|AT-FP48;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P3|
|FP49 Can the node preserve raw records through storage wear or abrupt power loss?|WAL FULL and blob flush/rename/parent sync ordering; restart detects missing or corrupt referenced bytes.|W02,W04,W16,W24,W29 / C1 / O1|AT-FP49;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P3|
|FP50 Are measurement time, upload time, simulated time and physical wall time separated?|Clock epochs distinguish simulation/monotonic/UTC; no restart catch-up or acceleration of real physical capture.|W02,W04,W16,W24,W29 / C1 / O1|AT-FP50;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E / P1;P3|
|FP51 Is a compact portable/rented-reference kit sufficient before a dedicated van?|Portable stationary rented reference is the initial physical route; no purchased van in the starting budget.|W14,W16,W17,W30 / O1 / O2|AT-FP51;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P3;P4|
|FP52 Can the operator reach and safely remain at the proposed measurement location?|Operator safety, setup and actual permitted dwell are full action constraints, not geometric waypoint permission.|W14,W16,W17,W30 / O1 / O2|AT-FP52;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P3;P4|
|FP53 Does the vehicle's own exhaust or engine state contaminate the inlet?|Engine exhaust/inlet contamination belongs to background and method QC; no vehicle-proximity result is assumed ambient.|W14,W16,W17,W30 / O1 / O2|AT-FP53;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P3;P4|
|FP54 Are setup, background collection, purge, travel, capture and review all counted?|All method stages consume time/energy; offline1020s observed fits1200s envelope, live limit remains900s.|W14,W16,W17,W30 / O1 / O2|AT-FP54;unsupported=YES|A;C;D / P0;P2;P3;P4|
|FP55 Can another feasible measurement help when the preferred route/site is unavailable?|Alternative feasible actions or quality diagnostics remain explicit; zero conservative discrimination gain is permitted.|W14,W16,W17,W30 / O1 / O2|AT-FP55;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P3;P4|
|FP56 Is the selected payload compatible with the actual aircraft, mount and CG envelope?|Conditional M350/Mini2 profile and ground fallback; remaining mount/CG/specific module compatibility cannot be proved from MTOW subtraction.|W14,W16,W17,W30 / O1 / O2|AT-FP56;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;F / P0;P6|
|FP57 Can the aircraft complete travel, settling, collection and return with reserve in wind?|Catalog55min is no-payload to0%; actual loaded route, settling, collection, return and reserve qualify separately.|W14,W16,W17,W30 / O1 / O2|AT-FP57;unsupported=YES|A;F / P0;P6|
|FP58 Does the actual inlet position avoid an unacceptable rotor-induced measurement bias?|No universally qualified0.8m boom; measured inlet recovery/rotor-bias comparison required for exact loadout.|W14,W16,W17,W30 / O1 / O2|AT-FP58;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;F / P0;P6|
|FP59 Are site airspace, permissions, operator competence and weather envelope satisfied?|Current India platform/permission/operator/site prerequisites remain P0/P6; phone controls virtual vehicles only.|W14,W16,W17,W30 / O1 / O2|AT-FP59;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;F / P0;P6|
|FP60 What measured benefit justifies aerial sensing over permitted ground alternatives?|Aerial benefit must beat feasible ground measurement on a defined task and cost; published neighboring applications are supporting precedent only.|W14,W16,W17,W30 / O1 / O2|AT-FP60;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;F / P0;P6|
|FP61 Do numerical units, wind conventions, coordinates and height transforms agree?|Explicit SI/gas units, ENU, wind-from convention and world/renderer transform; finite numbers and schema checks.|W11,W12,W17 / O2 / A|AT-FP61;unsupported=YES|A;C;D / P3;P4|
|FP62 Does the model conserve emitted mass and preserve wind/source/sensor history?|20Hz generator conserves emitted mass in reflected z>=0 halfspace; LOD is view-only and prehistory counts toward offline state cap.|W11,W12,W17 / O2 / A|AT-FP62;unsupported=YES|A;C;D / P3;P4|
|FP63 Does a judge's interaction change authoritative measurements and downstream findings?|Commands change authoritative vehicle/inlet/time/quality and downstream evidence; never camera-dependent measurements.|W04,W17,W19,W20,W21,W24,W29 / C1 / O1|AT-FP63;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|
|FP64 Can hidden truth leak through graphics, API fields, derived scores or demonstration shortcuts?|Private truth/store/assets/IDs/scores stay outside public projection and evidence; changed hidden labels cannot alter public reasoning.|W04,W17,W19,W20,W21,W24,W29 / C1 / O1|AT-FP64;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P4|
|FP65 Do graphics/phone failures preserve the investigation and accessible controls?|DOM/keyboard/2D fallback is specified, not tested; stale phone input revokes movement and saved investigation persists.|W04,W17,W19,W20,W21,W24,W29 / C1 / O1|AT-FP65;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A / P1;P3|
|FP66 Are background drift and mixed sources allowed rather than fitted away?|Independent gas amplitudes, full covariance and bounded supported offsets; no arbitrary drift polynomial fits away alternatives.|W13,W14,W15,W27 / O2 / C1|AT-FP66;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|
|FP67 Can ties, weak signal, unregistered sources and invalid samples remain unresolved?|Persist invalid/no-signal/unknown/ambiguous/provisional-single outcomes with separate qualification; candidate single is counted as an assertion.|W13,W14,W15,W27 / O2 / C1|AT-FP67;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|
|FP68 Is the planner's objective mathematically implemented and useful under equal budgets?|Exact conservative interval objective plus complete cost; equal continuing fixed observations; negative usefulness is an allowed final result.|W13,W14,W15,W27 / O2 / C1|AT-FP68;unsupported=YES|A;C;D / P3;P4|
|FP69 Are development, calibration and final test scenarios genuinely separated?|80dev/80cal/240stratified final split; private fresh manifest after freeze; optional independent240IID pool for stronger rate claims.|W26,W27,W28,W30 / C1 / O2|AT-FP69;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|
|FP70 Are missed events, false certainty, invalid samples and user effort counted end to end?|End-to-end misses, false exclusion, every provisional single, invalid/capacity failures, abstention and effort count; heterogeneous pooled rates descriptive.|W26,W27,W28,W30 / C1 / O2|AT-FP70;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|
|FP71 Is an inspector specimen actually separate from an ambient screening sample?|Inspector specimen separate from ambient online/media screening, with distinct purpose, material, authority and IDs.|W22,W23,W24,W25 / C2 / C1|AT-FP71;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P4|
|FP72 Are specimen identity, custody exceptions, raw data and report lineage complete?|Append-only custody and cited immutable revisions; preserve raw lab values and exceptions instead of erasing unfavorable lineage.|W22,W23,W24,W25 / C2 / C1|AT-FP72;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P4|
|FP73 Are the exact authority, forms, method and laboratory suitable for this site/use?|Method/material/accreditation scope and current statutory procedure are release prerequisites; public ASTM scope is not purchased standard text.|W22,W23,W24,W25 / C2 / C1|AT-FP73;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P4|
|FP74 Does the verifier trust a key/time independently of the uploaded bundle?|External key/fingerprint and revocation policy; unanchored time UNVERIFIED; signing proves byte integrity only.|W09,W24,W29 / C1 / O2|AT-FP74;unsupported=YES|A;C;D / P0;P4|
|FP75 What happens under archive attacks, missing records or compromised signing keys?|Bounded member/size/ratio/path parsing with raw-preserving chunks and no extraction; missing/changed bytes or revoked key fail separate axes.|W09,W24,W29 / C1 / O2|AT-FP75;unsupported=YES|A;C;D / P0;P4|
|FP76 Who is the actual first buyer, user and payer, and what procurement path applies?|Software workflow/integration service is a buyer hypothesis; no paying customer or procurement commitment is invented.|W16,W30 / C2 / O1|AT-FP76;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D;E;F / P0;P5|
|FP77 Can we start with partner instruments and software rather than owned fleet CAPEX?|Asoftware first; C borrowed/rented reference access; optional Bbench; owned E and aerial F remain quote-gated.|W16,W30 / C2 / O1|AT-FP77;unsupported=YES|A;C;D;E;F / P0;P5|
|FP78 Do prices cover staffing, calibration, maintenance, reference/lab access and replacements?|Revised C50k and D160k include method/QC/data work; software300–442focused hours; owned/aerial totals unknown, not zero.|W16,W30 / C2 / O1|AT-FP78;unsupported=YES|A;C;D;E;F / P0;P5|
|FP79 Is assumed crew sharing possible given travel, simultaneous events and service duties?|2–2–1 capacity and Ops bottleneck explicit; crew sharing cannot erase simultaneous setup/travel/operator time.|W16,W30 / C2 / O1|AT-FP79;unsupported=YES|A;C;D;E;F / P0;P5|
|FP80 How much working capital is needed if no grant arrives and customer payments are delayed?|Preserve dated old commercial stress ledgers and negative481kcost/150krevenue scenario; research allowance is not assured working capital.|W16,W30 / C2 / O1|AT-FP80;unsupported=YES|A;C;D;E;F / P0;P5|
|FP81 What happens under a simultaneous wind reversal plus a partially blocked SO2 inlet branch?|Compound wind/history and branch-flow failure invalidates affected online intervals; other channel evidence remains limited.|W11,W12,W13,W17,W27 / O2 / A|AT-FP81;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P2;P3;P4|
|FP82 What happens when a weak diluted plume coincides with a regional background drift and NO2 cross-interference?|Weak plume plus drift and negative cross-interference challenge stays blind; unavailable quantitative uncertainty cannot become a confident source.|W11,W12,W13,W17,W27 / O2 / A|AT-FP82;unsupported=YES|A;C;D / P2;P3;P4|
|FP83 What happens under simultaneous Wi-Fi/cellular packet loss, RTC clock skew, and battery brownout?|Combined link loss, clock uncertainty and brownout uses durable gap/interruption records; no inferred continuous sample.|W04,W16,W19,W24,W29 / C1 / O1|AT-FP83;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P1;P3|
|FP84 What happens when wind shifts mid-flight while the drone is in 15x accelerated dwell and filter loading is below LOQ?|15x wait advances every world/method/energy state; live900s capacity stops count; below-analyte-LOQ filter stays below LOQ.|W04,W11,W12,W14,W23,W27 / C1 / O1|AT-FP84;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P2;P3;P4|
|FP85 What if a power outage in the industrial estate causes multiple factories to start captive diesel generators (DG sets) simultaneously?|Multi-source DG schedules/three-source mismatch are explicit challenge families, not forced single-source labels.|W11,W12,W13,W23,W27 / O2 / A|AT-FP85;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|
|FP86 What if an active wet scrubber cools a boiler's flue gas to 45–55°C, destroying thermal buoyancy while leaving residual SO2 and high water mist?|Wet exhaust does not imply zero buoyancy; mist/sample response and effective height remain uncertain, independent gas agreement is no guarantee.|W11,W12,W13,W23,W27 / O2 / A|AT-FP86;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P2;P3;P4|
|FP87 What happens if a mobile inlet passes through an undiluted near-stack puff that saturates the ADC or exceeds the sensor's overgas limit?|Saturation/overgas creates a failed attempt plus recovery qualification; no clamped plausible raw value or instant recovery.|W11,W12,W13,W23,W27 / O2 / A|AT-FP87;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;E / P1;P2;P3|
|FP88 What if a mobile transect crosses an overlapping plume zone where a high-CO2 / zero-SO2 PNG boiler plume mixes with a small process SO2 leak from a neighboring plot?|Independent SO2/CO2 amplitudes across pair supports prevent imposed co-emission identity; mixtures can remain unresolved.|W11,W12,W13,W23,W27 / O2 / A|AT-FP88;unsupported=YES|A;C;D / P3;P4|
|FP89 What if a stall visitor or team member blows cigarette/vape/incense smoke at the <=INR 2,000 bench node and claims it demonstrates fuel or SO2 detection?|Optional optical node shows relative optical response only; never fuel/SO2/PM2.5 identification or intentional smoke-generation requirement.|W16,W30 / C2 / O1|AT-FP89;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;B / P1;P2|
|FP90 What if Laptop 1 (Operator), Laptop 5 (Board Reviewer), and two visitor phones issue conflicting commands on the same case/mission within the same second?|Auth before idempotency before revision; one lease, per-owner sequence and committed effective tick resolve simultaneous commands.|W03,W04,W19,W20,W29 / O1 / C2|AT-FP90;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A / P1;P3|
|FP91 What if calibration span gas (e.g., 500 ppb SO2 in N2) degrades in the cylinder or adsorbs inside a standard brass pressure regulator during calibration?|Qualified span cylinder, suitable regulator and delivery recovery precede calibration; nominal cylinder label alone cannot qualify chain.|W12,W16,W23 / O2 / C1|AT-FP91;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P0;P2;P3|
|FP92 What if moderate errors across wind (+12°), stack height (-10 m), sensor lag (unsettled 40 s), and NO2 interference (-25%) combine to point at an innocent neighboring factory?|Joint moderate-error challenge and false assertion/exclusion metrics remain; no truth-only domain flag rescues innocent-neighbor misidentification.|W12,W13,W22,W23,W27 / O2 / C1|AT-FP92;unsupported=NO_CLAIM_IN_SUPPLIED_ROW|A;C;D / P3;P4|

## All25findings

|ID / title|Selected production decision|Tasks / owner / reviewer|Acceptance / physical gate|
|---|---|---|---|
|AR01 Chronology|Release claims include 25 false singles/240 and do not treat earlier audit as later-product validation. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W26,W27,W30 / C1 / O2|AT-AR01 / P4|
|AR02 Unsupported 33 test claims|No app result marked passed without executable fixture/output/hash; all 33 flags retained and tests pending until run. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W10,W27,W30 / C2 / O1|AT-AR02 / P1;P3;P4|
|AR03 Uncalibrated figure and waypoint|No calibrated-probability graphic/0.75 threshold inherited; geometric bounds check and utility/access test on any replacement. R3 constraint: Uncalibrated illustration remains excluded; action points use declared synthetic access and model feasibility, not old figure performance.|W14,W17,W27 / O1 / O2|AT-AR03 / P3;P4|
|AR04 Conflicting evaluation splits|80/80/240stratified; separate optional240IID; no pooled exact-binomial diagnostic claim.|W26,W27,W28 / C1 / O2|AT-AR04 / P4|
|AR05 False-exclusivity metric|S1-only assertion when truth S1+S3 increments unsupported exclusivity even though S1 contributes; no calibrated posterior from residual label. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W13,W27 / O2 / C1|AT-AR05 / P4|
|AR06 Stack/route/rate/bounds conflict|Environment locks/builds and schema/rate/bounds tests use one selected revision; old /ops only redirects and no R3F simulator. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W01,W04,W19,W24 / O1 / C1|AT-AR06 / P1;P3|
|AR07 Labor/98-hour scope|Full tasks sum 300–442 focused; reserve30% of gross; new per-person dependencies determine capacity. No inherited98h or old A–E schedule.|W30 / O1 / C1|AT-AR07 / P5|
|AR08 Invalid ESP32-S3 GPIO22|Profile with GPIO22 rejected; no unreviewed schematic fabrication release; exact board flash/PSRAM/USB/boot pins checked. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W16 / C2 / O1|AT-AR08 / P1|
|AR09 Unregulated battery overvoltage|Direct29.2V-to26.4V-max profile fails; TVS/fuse alone cannot satisfy regulation; physical extremes/inrush test pending. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W16 / C2 / O1|AT-AR09 / P1|
|AR10 Pump/topology/power inconsistency|Selected node allocation18Wcontinuous/24Wpeak; original9.0834/16.0256 values remain legacy regression only.|W16,W30 / C2 / O1|AT-AR10 / P1;P2|
|AR11 Protocol/isolation confusion|Reject Modbus assumption for SHDLC device and missing isolation-power domain; packet fixture/actual protocol capture required. R3 constraint: Isolation now includes each instrument24Vreturn domain; separate SHDLC meters and GMP252 Modbus links.|W16 / C2 / O1|AT-AR11 / P1;P2|
|AR12 ADC/filter/heater ambiguity|Profile validation flags input/headroom/clipping and unknown recovery; no24-bit-precision or universal+5C claim. R3 constraint: Gain1/PGA bypass/external4.096V and RC10k/4.7uF selected; supplier ISB configuration remains a physical fabrication prerequisite.|W12,W16 / O2 / C1|AT-AR12 / P1;P2|
|AR13 Cascade/settling conflation|Independent step solver checks each chain and readiness error; universal78/96s timer cannot mark all chains settled. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W12,W14 / O2 / C1|AT-AR13 / P2;P3|
|AR14 Solar/battery chronology|Fog harvest below running load decreases SOC; old450.432Wh/69.84Wh/day=6.45day averaged case not70.82h shutdown; do not mix configurations. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W16,W30 / C2 / O1|AT-AR14 / P1;P3|
|AR15 Reflected Gaussian domain|Half-space numerical integral equals emitted mass within frozen quadrature error; full signed-z integral is not mass conservation criterion. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W11,W27 / O2 / A|AT-AR15 / P3;P4|
|AR16 Hold/LOQ contradictions|112.5ug total and0.225ug hypothetical analyte remain distinct; timer and generic4h hold cannot qualify; per-analyte rules frozen. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W12,W23 / O2 / C1|AT-AR16 / P2;P3;P4|
|AR17 Arbitrary mission/quality constants|Unachievable return rejected, reserve energy explicit; fixed60s purge/22-25% battery/CG values cannot qualify unknown hardware. R3 constraint: Live/offline envelopes distinct; full procedure and energy remain counted; no literal M350 energy proof from generic teaching model.|W14,W16 / O1 / O2|AT-AR17 / P2;P3;P6|
|AR18 Wet plume/coherence overclaim|FP86 not forced to sink; FP88 correlated mixture cannot become common-source fuel proof. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W11,W13,W27 / O2 / A|AT-AR18 / P3;P4|
|AR19 Scripted wrong-site clean result|Change selected suspect without changing specimen inputs: lab result unchanged; wrong-site specimen is not auto-clean/guilt. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W22,W23 / C2 / C1|AT-AR19 / P0;P2;P4|
|AR20 Stage C double-counting and rental reuse|A0;B2000;C50000;D160000 incremental, baseC+D210000;withB212000;withCO2increments297000; rental credit0.|W30 / O1 / C1|AT-AR20 / P0;P5|
|AR21 Overhead and crew omissions|Legacy201000 less omitted88000 is113000 under legacy assumptions; new scenario every staff/reference/lab/support cost counted; queue capacity enforced. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W30 / O1 / C1|AT-AR21 / P5|
|AR22 Working capital mislabeled|Three-month lag peak370000 and six-month no-receipt530000 under chosen assumptions; loss-making one-estate case does not recover from timing alone. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W30 / O1 / C1|AT-AR22 / P5|
|AR23 Citation drift|Every plan reference resolves; citations support actual claim/domain; inherited manufacturer review not represented as fresh measurement. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W16,W30 / C2 / O1|AT-AR23 / P0;P5|
|AR24 Registration/current external facts|No claimed receipt/partner/purchase threshold; date evidence supports calendar; physical action blocks without actual current scope/access/funding. R3 constraint: Retain original correction; operationalize through the named R3 chapter and its strict limitations.|W16,W30 / C2 / O1|AT-AR24 / P0;P5;P6|
|AR25 Clock/snapshot/origin/revocation|Backlog cannot rewrite capture time; consistent-cursor resync; public physical-origin injection denied; self-backdated revoked key fails. R3 constraint: Select JCS command hashing, raw-byte chunks, strict catalogue and explicit recovery/trust axes.|W03,W04,W16,W24,W29 / O1 / C2|AT-AR25 / P1;P3;P4|

## All28features

|Feature|Actual acceptance required|Tasks / feature owner / reviewer|Full gate / R-E0scope|
|---|---|---|---|
|F01 Geographic 3D context — Field|Cropped Indian footprints; assumed heights and fictional stacks labelled; 2D fallback uses the same state|W11,W17,W21 / A / O2|M7 / EXCLUDED|
|F02 Sustained-event detection — Engine|Quality-gated baseline/excess event; a brief spike or a failed channel cannot produce a valid dispatch|W12,W15 / O2 / C1|M3 / EXCLUDED|
|F03 Sensor/measurement health — Operations/Engine|Raw value, units, provenance, freshness, calibration and exclusion reason visible; missing never becomes zero|W05,W06,W12,W15 / O2 / C2|M3 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F04 Placement/observability planner — Operations/Engine|Choose mount/site/height; compare modeled coverage and distinguishability; show inaccessible/uncovered conditions|W11,W14,W15,W17 / O2 / A|M4 / EXCLUDED|
|F05 Source ranking — Operations/Engine|Candidate fits, contributing observations, background/unknown/tied explanations; no invented probability|W13,W15 / O2 / C1|M3 / EXCLUDED|
|F06 Next useful measurement — Operations/Engine|Ranked reachable plans with signal/dwell/uncertainty and travel cost; rejected points have reasons|W14,W15 / O1 / O2|M3 / EXCLUDED|
|F07 Investigation assistant — Operations|Deterministic explanation always works; optional grounded LLM cannot change scores, readings or permissions|W15 / O1 / C2|M3 / EXCLUDED|
|F08 Human approval — Operations/Backend|Approve/reject one versioned plan; wind/quality invalidation requires reapproval|W03,W06,W14 / O1 / C2|M3 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F09 Pilot queue and lease — Field/Backend|Only a granted pilot controls one mission; waiting visitors can watch; expiry/release creates a clean handover|W19,W20 / A / C2|M4 / EXCLUDED|
|F10 Phone/keyboard drone control — Field|Server-applied movement within fictional flight bounds; stale inputs hold; keyboard fallback|W17,W19,W20 / A / C2|M4 / EXCLUDED|
|F11 Van sampling mode — Field/Engine|Vehicle follows approved accessible roads; inlet height limited by declared range; cannot reach every elevated plume|W11,W14,W17 / A / O2|M4 / EXCLUDED|
|F12 Investigative measurement capture — Field/Engine|Actual inlet path/time, paired background and dwell generate observations; no success button creates data|W05,W07,W12,W17 / A / O2|M4 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F13 Guided mobile-lab QC — Field/Backend|Receive ID, verify virtual instrument state, check alignment/flow/dwell, accept/limit/reject; failure recorded|W05,W07,W18,W23 / A / C2|M5 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F14 Analysis update — Engine/Operations|Only accepted relevant channels update the model; show before/after and residual/uncertainty|W13,W15 / O2 / C1|M3 / EXCLUDED|
|F15 Shared investigation timeline — All|Durable actions and results appear across apps after commit; duplicate requests do not duplicate effects|W03,W04,W10 / O1 / C2|M1 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F16 Inspector case intake — Cases|Review evidence; accept for simulated inspection, request more measurement or mark inconclusive|W08,W25 / C2 / C1|M5 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F17 Official-style specimen/custody — Cases|Distinct authorized-demo specimen, seal/custody exceptions retained; failed custody limits use without deleting analytical result.|W22,W25 / C2 / C1|M5 / EXCLUDED|
|F18 Simulated lab outcome — Cases/Backend|Lab outcome derives actual simulated specimen material/method/blank/mass/QC; user cannot choose answer; wrong site never scripted clean.|W23,W25 / C2 / O2|M5 / EXCLUDED|
|F19 Evidence vault — Cases/Backend|Original observations, QC, approvals, versions and provenance saved as a versioned pack|W09,W24,W25 / C1 / O2|M5 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F20 Export/signature/tamper verification — Cases/Backend|Original export verifies; changed/missing bytes or substituted trusted key fail; independent CLI verifies on another laptop|W09,W24,W29 / C1 / O2|M7 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F21 Board decision and metrics — Operations|Outcome rationale, supported/uncertain cases, elapsed times, valid samples and device health; no fake enforcement|W06,W15,W27 / O2 / C1|M6 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F22 Resident preview — Operations|Area/time/status and demo message; no real suspect names or external sending|W15 / O1 / C2|M3 / EXCLUDED|
|F23 Observation replay/history — Operations/Cases|Read-only recorded events and traces; a replay cannot issue live commands or overwrite the original case|W04,W25,W29 / C1 / O1|M7 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F24 Benchmark/baseline comparison — Engine/Operations|Held-out simulator scenarios and transparent failures; no transferred paper accuracy|W26,W27,W28 / C1 / O2|M6 / EXCLUDED|
|F25 Presenter scenarios/fault console — Operations|Logged presenter controls only in teaching mode; investigation truth inaccessible; reveal clones new labeled teaching run after freeze, excluded from blind evaluation.|W11,W15,W17 / O2 / A|M4 / EXCLUDED|
|F26 Data provenance — All|Every value/geometry/attachment has real-context, modeled, synthetic, derived or physical status|W01,W16,W24,W30 / O1 / C1|M7 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F27 Shared-state recovery — Backend/All|Stale display, snapshot reconnect, interrupted-run handling, persistent cases and verified backup|W02,W04,W10,W19,W29 / C1 / O1|M7 / PARTIAL_ONLY_WHEN_ACTUALLY_TESTED|
|F28 Optional physical node — Operations/Backend|Real raw/relative optical response and optional temperature/RH; never injected as SO2 or used as factory evidence|W16,W30 / C2 / O1|M7 / EXCLUDED|

## All32R3coding decisions

|ID/topic|Selected decision|Tasks / owner / reviewer|
|---|---|---|
|CD01 App boundary|One modular authority; three React browser routes; no app-local scientific state.|W01,W03 / O1 / C1|
|CD02 IDs and wire types|UUIDv4 and bounded decimal-string sequences; explicit units/origin/versions; reject ambiguous JSON.|W01 / O1 / C1|
|CD03 Contract source|JSONSchema2020-12 -> generated TS + Python validation; one conformance fixture set.|W01 / O1 / C1|
|CD04 DB and migration|sqlite3 explicit SQL;STRICT/WAL/FULL/FK;immutable revision links;ordered migrations.|W02 / C1 / O1|
|CD05 Write ordering|Authorize then idempotency lookup then revision/guard; one durable command receipt/effective tick.|W03 / O1 / C2|
|CD06 Simulation clock|20Hz integration/10Hzdurable;no wall-time catchup;real physical time cannot accelerate.|W04,W11 / C1 / O1|
|CD07 Blob crash order|Stage/flush/hash/rename/parent sync before DB reference;orphans safe;missing references block export.|W02 / C1 / O1|
|CD08 Read synchronization|Consistent snapshot+cursor;bounded SSE;resnapshot on expiry;1Hzreadfallback.|W04 / C1 / O1|
|CD09 Authentication|Opaque hashed sessions;Argon2id64MiB/t3/p1 initial;CSRF/HostOrigin;scoped visitor pairing.|W03 / O1 / C2|
|CD10 Phone controls|Single lease;10Hzsequence;400msstale/500msstop target;virtual only;host keyboard fallback.|W19,W20 / C2 / O1|
|CD11 Worker jobs|Immutable input hash;three transient I/O retries1/2/4s;never scientific retuning.|W03 / O1 / C2|
|CD12 Hardware adapter|Provisioneddevice+boot+seq;rawbytes and clockmap;no vendor API invented.|W16 / C2 / O1|
|CD13 Scientific geometry|Declared fictional parcel/ENU/source heights separate from real map context.|W11,W17 / O2 / A|
|CD14 Puff model|Reflected finite-history20Hzmass and oldwind/source states independent of cameraLOD.|W11 / O2 / A|
|CD15 Inlet response|Volume transport + chamber + sensor exact exponential;ready/recovery perprofile.|W12 / O2 / C1|
|CD16 Noise and covariance|AR1innovation labeled;signal/backgroundcrosscov once;unknown uncertainty disables quantitativeuse.|W12 / O2 / C1|
|CD17 Inverse solver|Steadykernel,27nuisance,21supports,independentgasamplitudes;no sparsity/ridge.|W13 / O2 / C1|
|CD18 Action utility|ConservativeSVDouterboxes;full time/energy/access;zero bound permitted.|W14 / O1 / O2|
|CD19 Model outcomes|Provisionalsinglealwayscountsassertion;separatequalification;unknown/invalid/ambiguous preserved.|W13,W27 / O2 / C1|
|CD20 Sampling methods|Explicit syntheticonline/aerosol/material catalogs;realretainedgasdisabled.|W05,W23 / O2 / C2|
|CD21 Specimen and lab|Separate inspector specimen;actualmaterialmethod/QC drives result,not selectedsite.|W22,W23 / C2 / C1|
|CD22 Custody|Append-onlyholder/seal/time exceptions;failedcustodylimitsuse but keeps analyticaldata.|W22 / C2 / C1|
|CD23 Evidence format|Exactmanifestbytes domainprefixsignature;rawframeNDJSONchunks;out-of-bandtrust.|W09,W24 / C1 / O2|
|CD24 Archive defenses|No extraction;size/member/ratio/path/index/hash/decodedrawlimits;distinctverificationaxes.|W24 / C1 / O1|
|CD25 Blind evaluation|Privatefreeze/seedcommit;80/80/240stratified+optional240IID;allfailurescount.|W26,W27,W28 / C1 / O2|
|CD26 Comparator fairness|Samefixedobservations/endtime;observedamplitudesonly;oneapprovedaction/fullcost.|W26,W27 / C1 / O2|
|CD27 3D assets|Originalproceduralgeometry;contextlicense;no truth-codedtexture/puffscore in investigation.|W17 / A / O2|
|CD28 Accessible views|DOM/keyboard/2D,reducedmotion,44pxtargets,200%zoom;actualdevicetests.|W21 / C2 / A|
|CD29 Toolchain|Exactregistrycandidates;npmworkspaces/uvhashlocklater;no installperformed.|W01 / O1 / C1|
|CD30 Offline launch/restore|NoCDN;boundedLANsyntheticprofile;diskstops;consistentblobbackup;oneauthoritytakeover.|W29 / C2 / O1|
|CD31 Team integration|Opspairsharedauthority/science;Casespairevidence/evaluation;AdityaField;regenerateschedule.|W30 / O1 / C1|
|CD32 First milestone|Threeoutcomes throughallthreeapps,savedrecords,restartandindependentreproducibleevidence before decorativeexpansion.|W10 / C2 / O1|
