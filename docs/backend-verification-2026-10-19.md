# FuelPrint backend verification — 19 October 2026

**Not frozen or declared backend-ready. Eight phases complete, two partial, none unstarted.** These are phase counts, not a percentage of effort. Work remains uncommitted on `science/join` at base `8db5244`; main's verified checkpoint remains `8db5244`. All inherited changes were preserved. No commit, merge, push, deployment, new screens, 3D implementation or model training.

## Phase report

| Phase | Status | Evidence and remaining limit |
|---|---|---|
| 1 Backup and baseline | Complete | Existing checkpoint and backups preserved; continuation snapshot retained outside repo. |
| 2 Inherited science integration | Complete | Current clean Java suite and real-profile wiring pass. |
| 3 Measurement windows and sensor UUIDs | Complete | Fixed windows, run-scoped UUID lookup, required background and wrong-run/missing-background regressions pass. |
| 4 Capture averaging and human QC | Complete in worktree | Approved CaptureRules.windows 2.2; measured contrasts saved atomically only for automatic VALID + human VALID, with revision/idempotency/role/fault checks. |
| 5 Real SC1/SC2/SC3, automation off/on | Partial | Six real core/science/field lifecycle cases pass, but SC1 and SC3 disagree with scripted narratives; ops/cases mission approval and subsequent workflows remain test-only/unbuilt. |
| 6 Measurement/prediction/planner consistency | Complete in worktree | Finite-age puff/response/temporal-contrast model and actual flight forecast verified; joint prospective gas whitening fixed. Owner explicitly approved ProspectiveInlets; contract/API version 2.3 documents it. Focused post-approval verification passed. |
| 7 Covariance and pair semantics | Complete as implemented | Actual electrical/common averaging operators, joint gas GLS and independent oracles pass. Approved NOT_NEEDED policy retained; its poor final exact-support retention remains explicit. |
| 8 Current-version calibration/evaluation | Complete evidence | Fresh V4 calibration and untouched final evaluation measured and independently audited. This does not establish physical accuracy or final-support coverage. |
| 9 Wind sensitivity and latency | Complete measurement | All 12 predeclared fixed-cutoff cells have 60 eligible episodes; six actual scenario timing records archived. Small synthetic samples and declared route/domain limits apply. |
| 10 Final verification/review/merge/freeze | Partial | Saved full suite and focused replay cleanup/forecasting checks passed. Independent final audit passed within the stated scope; scenario, ownership and freeze gates remain open. No commit, merge or freeze. |

## Fresh V4 qualification

Algorithm `FP-COMPAT-3/4` corrects fixed-detector uncertainty using actual background/collection sample-time covariance. Historical V3 files remain explicitly labelled historical; their cutoff/results do not qualify V4.

- Calibration: 227 attempts, 60 eligible, 167 NO_SIGNAL/ineligible, zero unknown eligibility or eligible-fit failures. Rank 58 cutoff **3.0466921686441504**. Sorted IEEE 754 binary64 big-endian SHA256 `ac2dc68c1db085d5e8ef04260f31fc0e52674baa47fb8e29ae9fd69b400f5bf2`. Current clean suite independently recomputed the identical cutoff/hash.
- Original untouched evaluation: FPEVAL4 indices 10000–10215, 216 attempts, 60 eligible, 156 ineligible; **57/60 raw true-support compatible; 8/60 final exact supports survive**. No generation, eligible-fit or whole-ranker failures. Individual nuisance-member failures are retained and may occur; zero here refers to episode-level failures.
- All 49 raw-fitting true pairs removed by NOT_NEEDED had a constituent single inside the cutoff. None of 51 true pairs survived final filtering. Eight of nine true singles survived; only three of 19 PROVISIONAL_SINGLE outputs retained exact true support. Do not describe raw 57/60 as final-support retention or a physical accuracy guarantee.
- The matched SD 0.5/τ 200 wind stress cell is separately measured below; preserve its result alongside the original held-out set. Cell differences include finite-sample variation.
- Calibration uses constant emissions from time zero, six singles/fifteen pairs, ratio 500–2000 and a straight-line route to two 35 m targets. Actual field geometry/body offsets differ; SC1 ratio 25 and SC3's high survey target are outside that simplified design. Real scenario regressions have no formal coverage guarantee from this calibration.
- Window uncertainty states the electrical allocation; joint fitting also adds filtered shared ambient covariance. A target background may contain plume. Initial response/emission history and constant wind remain declared assumptions.

The original evaluation freeze includes 360 classes, 265 source files, 42 resources and 79 dependency JAR hashes. Subsequent 5 Java/6 class changes address planner joint whitening, initialization disclosure placement and an identical empty-plan world-loader delegation. They change no attribution statistic, RNG, selection or emission semantics; scoped independent review and exact cutoff/hash recomputation confirmed that boundary. Other 354 classes remain identical. The original evaluation is not relabelled as a run of later classes. Runtime cutoff installation has separate provenance. Evaluated seeds are now observed and cannot be reused as untouched data for future statistical changes.

## Fixed-cutoff wind stress grid

All cells use **3.0466921686441504**, 60 eligible episodes and disjoint FPWIND namespaces. No cell was recalibrated or tuned to scenario outcomes. Reported failure columns are generation/eligible-fit/whole-ranker counts. Baseline qualification is SD 0.5 degrees/τ 200 seconds; other domains are stress measurements, not nominal coverage guarantees. Wind SD/tau are teaching assumptions, not inferred 1 Hz facts from 15-minute station data.

| Direction SD° | τ seconds | All attempts | Raw compatible | Final exact support | Failures G/F/A |
|---|---|---|---|---|---|
| 0.5 | 30 | 190 | 52/60 | 13/60 | 0/0/0 |
| 0.5 | 200 | 211 | 53/60 | 12/60 | 0/0/0 |
| 0.5 | 600 | 177 | 56/60 | 9/60 | 0/0/0 |
| 1 | 30 | 219 | 46/60 | 4/60 | 0/0/0 |
| 1 | 200 | 228 | 51/60 | 10/60 | 0/0/0 |
| 1 | 600 | 223 | 56/60 | 14/60 | 0/0/0 |
| 3 | 30 | 206 | 38/60 | 12/60 | 0/0/0 |
| 3 | 200 | 179 | 45/60 | 8/60 | 0/0/0 |
| 3 | 600 | 194 | 44/60 | 7/60 | 0/0/0 |
| 5 | 30 | 192 | 33/60 | 12/60 | 0/0/0 |
| 5 | 200 | 184 | 36/60 | 9/60 | 0/0/0 |
| 5 | 600 | 174 | 37/60 | 6/60 | 0/0/0 |

Total workload: 2377 attempted episodes, 720 eligible; all attempts/failures retained. Raw full reports and frozen copies are outside target in the execution directory; compact per-cell counts, seed ranges and report hashes are [here](research/full-chain-v4-wind-sensitivity-2026-10-19.json).

## Actual scenarios and latency

Both automation modes measured:

| Scenario | Initial sim300 | After human QC | Initial planner |
|---|---|---|---|
| SC1 | PROVISIONAL_SINGLE S02 | PROVISIONAL_SINGLE S02 | NO_USEFUL_MEASUREMENT |
| SC2 | PROVISIONAL_SINGLE S02 | PROVISIONAL_SINGLE S02; SO2 REJECTED after 10 valid seconds, CO2 usable | NO_USEFUL_MEASUREMENT |
| SC3 | NO_SIGNAL | PROVISIONAL_SINGLE S03 after high survey | NO_USEFUL_MEASUREMENT |

UNKNOWN remains present. SC2 CO2 accumulates 60 valid seconds; its analytical window uses the first 30 consecutive valid samples. The same sim300 fixed snapshot is retained after QC. A test-only analyst command creates an APPROVED mission because teammates' ops/cases paths are unbuilt. This does not verify actual supervisor proposal/approval, cases/sealing, physical SMS, retraining or full-product automation.

Six measured wall-time records on this Mac under the full-suite workload; these are min/max observations, not production percentiles or guarantees. Initial range includes the fast NO_SIGNAL branch. Planner has separate deterministic expiration/cancellation regressions.

| Operation | Minimum seconds | Maximum seconds |
|---|---|---|
| Initial analysis, including NO_SIGNAL | 0.017370 | 2.143592 |
| Post-QC analysis | 3.745979 | 9.406988 |
| Initial planner | 0.002052 | 0.308951 |

## Verification and review

- Saved clean `tools/test-all.sh` exited 0: 528 Java tests, zero failures/errors/skips; existing screen type checks and 19 screen unit tests passed. Two default-skipped e2e tests were separately exercised by two passing fake-server e2e checks. Original full-suite XML was archived before focused reruns; its aggregate and the current aggregate both remain 528/0/0/0. This closeout did not rerun the full suite or implement new screens.
- Five saved Python checks passed: four wind arithmetic checks and one launcher cleanup test. They do not validate a real wind-domain estimate or Windows execution.
- The passing full-suite run logged test-only SQLite errors at JVM teardown. Independent review traced them to cached replay work racing temporary-directory deletion; production has neither that deletion hook nor test listener. ReplayModeTest now closes its context AFTER_CLASS; a focused rerun passes without SQLITE_CANTOPEN. Production source and numerical evidence are unchanged by this test cleanup. The first focused attempt was blocked by sandbox local-port restrictions; its log is preserved separately from the passing escalated rerun. Do not call the original full-suite log pristine.
- Focused post-approval forecasting verification passed 29 tests, zero failures/errors/skips: flight geometry/history/cancellation, response and joint covariance, planner deadlines, real Spring wiring and truth-boundary checks. The implementation was already in the saved full suite; this closeout records its separate owner approval and documentation. No production Java changes were made in this closeout.
- Prior independent reviews found and re-reviewed electrical covariance, planner deadline, background/initialization disclosure, exact cutoff provenance, prospective joint whitening and calibration truth-boundary fixes. Candidate/held-out data, seed separation and accounting were independently checked. Independent final audit passed within the stated scope; scenario, ownership and freeze gates remain open. Final review path: `/Users/aditya/dev/fuelprint-backend-execution-2026-10-18/FINAL-V4-CLOSEOUT-REVIEW.md`.
- `verify_evidence.py --require-wind` checks original freeze plus documented deltas, installed resource, statistic hash/order rank, all 12 completed cells, seed disjointness, denominators and raw/final candidate bookkeeping. Raw reports are retained rather than rewritten to a story.

Evidence: [calibration](research/full-chain-calibration-v4-2026-10-19.json), [independent evaluation](research/full-chain-evaluation-v4-2026-10-19.json), [provenance](research/full-chain-v4-provenance-2026-10-19.json), [six scenarios/timings](research/real-backend-v4-scenarios-2026-10-19.json). Full raw data/logs/frozen classes are under `/Users/aditya/dev/fuelprint-backend-execution-2026-10-18/v4-qualification/`.

## Owner decisions and deferred work

1. **Forecasting approved:** Aditya explicitly approved the [ProspectiveInlets interface](contract-proposals/2026-10-19-prospective-inlets.md). Contract/API version 2.3 documents the existing implementation; 29 focused tests passed. This approval does not authorize a commit, main merge or freeze.
2. Decide the [measured scenario/freeze wording](contract-proposals/2026-10-19-measured-backend-scenarios.md). SC1 ambiguity-to-drone and SC3 inconclusive endings are unmet. Preserve measured outcomes and the visible final-support limitation; never tune physics/cutoff to force those stories.

The first product direction is a 3D hardware-and-physics demonstration, with gameplay later. This does not silently amend the scenario/freeze gate. New screens, 3D, model implementation/training/evaluation, physical/Windows gates and teammates' ops/cases integration remain deferred/unverified. SMS remains LOG_ONLY. Main remains `8db5244`; no backend-ready, full-product or physical-accuracy claim. See the [remaining-work inventory](backend-remaining-work-2026-10-19.md).

## Files changed or added in the full uncommitted continuation

Includes inherited work preserved from the handoff as well as this session's changes:

- `contracts/AGREEMENT.md`
- `docs/CORE-API.md`
- `docs/backend-remaining-work-2026-10-19.md`
- `docs/backend-verification-2026-10-19.md`
- `docs/contract-proposals/2026-10-18-capture-windows.md`
- `docs/contract-proposals/2026-10-19-measured-backend-scenarios.md`
- `docs/contract-proposals/2026-10-19-prospective-inlets.md`
- `docs/full-chain-calibration.md`
- `docs/research/full-chain-calibration-2026-10-19.json`
- `docs/research/full-chain-calibration-v4-2026-10-19.json`
- `docs/research/full-chain-evaluation-2026-10-19.json`
- `docs/research/full-chain-evaluation-v4-2026-10-19.json`
- `docs/research/full-chain-v4-provenance-2026-10-19.json`
- `docs/research/full-chain-v4-wind-sensitivity-2026-10-19.json`
- `docs/research/real-backend-v4-scenarios-2026-10-19.json`
- `server/src/main/java/fuelprint/api/CaptureRules.java`
- `server/src/main/java/fuelprint/api/ProspectiveInlets.java`
- `server/src/main/java/fuelprint/field/FieldPorts.java`
- `server/src/main/java/fuelprint/field/command/CaptureHandlers.java`
- `server/src/main/java/fuelprint/field/vehicle/Drone.java`
- `server/src/main/java/fuelprint/field/world/FieldWorld.java`
- `server/src/main/java/fuelprint/field/world/ProspectiveFlight.java`
- `server/src/main/java/fuelprint/science/calibration/FullChainCalibrationRunner.java`
- `server/src/main/java/fuelprint/science/detect/TeachDetector.java`
- `server/src/main/java/fuelprint/science/plan/IntervalPlanner.java`
- `server/src/main/java/fuelprint/science/plan/IntervalScorer.java`
- `server/src/main/java/fuelprint/science/plan/ProspectiveMeasurement.java`
- `server/src/main/java/fuelprint/science/plan/SciencePlanner.java`
- `server/src/main/java/fuelprint/science/rank/CalibrationRunner.java`
- `server/src/main/java/fuelprint/science/rank/CompatRanker.java`
- `server/src/main/java/fuelprint/science/rank/Cutoff.java`
- `server/src/main/java/fuelprint/science/rank/FitEngine.java`
- `server/src/main/java/fuelprint/science/rank/FitSnapshot.java`
- `server/src/main/java/fuelprint/science/rank/FixedSensorInlets.java`
- `server/src/main/java/fuelprint/science/rank/InletTrack.java`
- `server/src/main/java/fuelprint/science/rank/PuffPrediction.java`
- `server/src/main/java/fuelprint/science/rank/ResponseModel.java`
- `server/src/main/java/fuelprint/science/rank/WindowCovariance.java`
- `server/src/main/java/fuelprint/science/rank/WindowSelection.java`
- `server/src/main/java/fuelprint/science/sensor/CaptureAveraging.java`
- `server/src/main/java/fuelprint/science/sensor/CaptureCollectionListener.java`
- `server/src/main/java/fuelprint/science/sensor/SensorField.java`
- `server/src/main/java/fuelprint/science/sensor/SyntheticNoiseProfile.java`
- `server/src/main/java/fuelprint/science/sensor/TeachCaptureRules.java`
- `server/src/main/java/fuelprint/science/world/ScienceWorld.java`
- `server/src/main/resources/science/rank/cutoff.json`
- `server/src/test/java/fuelprint/core/replay/ReplayModeTest.java`
- `server/src/test/java/fuelprint/field/CaptureTest.java`
- `server/src/test/java/fuelprint/field/FieldTestConfig.java`
- `server/src/test/java/fuelprint/field/InletHistoryTest.java`
- `server/src/test/java/fuelprint/field/ProspectiveFlightTest.java`
- `server/src/test/java/fuelprint/science/ScienceWiringTest.java`
- `server/src/test/java/fuelprint/science/calibration/FullChainCalibrationRunnerTest.java`
- `server/src/test/java/fuelprint/science/detect/DetectorRankingWindowsTest.java`
- `server/src/test/java/fuelprint/science/integration/RealBackendScenariosTest.java`
- `server/src/test/java/fuelprint/science/plan/IntervalPlannerTest.java`
- `server/src/test/java/fuelprint/science/plan/IntervalScorerOracleTest.java`
- `server/src/test/java/fuelprint/science/plan/ProspectiveMeasurementTest.java`
- `server/src/test/java/fuelprint/science/rank/CompatRankerTest.java`
- `server/src/test/java/fuelprint/science/rank/CutoffProvenanceTest.java`
- `server/src/test/java/fuelprint/science/rank/JointCovarianceFitTest.java`
- `server/src/test/java/fuelprint/science/rank/PuffPredictionTest.java`
- `server/src/test/java/fuelprint/science/rank/ResponseModelTest.java`
- `server/src/test/java/fuelprint/science/rank/ScienceRankerHistoryTest.java`
- `server/src/test/java/fuelprint/science/rank/VehicleContrastTest.java`
- `server/src/test/java/fuelprint/science/rank/WindowCovarianceTest.java`
- `server/src/test/java/fuelprint/science/rank/WindowSelectionIntegrityTest.java`
- `server/src/test/java/fuelprint/science/sensor/CaptureCollectionListenerTest.java`
- `server/src/test/java/fuelprint/science/sensor/CaptureWindowsTest.java`
- `server/src/test/java/fuelprint/science/sensor/SensorFieldTest.java`
- `server/src/test/java/fuelprint/science/sensor/TeachCaptureRulesTest.java`
- `tools/full-chain-calibration.sh`
- `tools/full-chain-wind-sweep.sh`
