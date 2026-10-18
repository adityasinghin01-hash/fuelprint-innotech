# Backend integration checkpoint — 18 Oct 2026

This checkpoint completes the independent integration repairs. It does not complete the approved ten-step backend plan or establish the final real SC1–SC3 outcomes.

## Changes

- Detector returns complete, non-overlapping 30-second analysis averages independently of the unchanged 15-second alarm. Background and collection spans now agree with ranking. Server UUIDv4 IDs and duplicate suppression are covered.
- Ranking resolves fixed-sensor UUIDs from public run-scoped sensor records, and drone positions through the existing field history API. Missing history stays excluded. Response prediction asks for 180seconds of prehistory and uses the gas-specific line/chamber/sensor chain.
- Science reads the canonical bundled contract world profile. Capacity completion uses the shared API event. Planner budget expiry returns PENDING without a recommendation.
- Field and science reject missing, invalid or nonfinite flow. Missing seconds break the field collection run; invalid gas values cannot pass.
- The 15x clock repeats normal 0.05 s science steps so readings remain on exact 1 Hz timestamps. Releasing speed-up or starting movement stops extra steps immediately.
- Analysis text acknowledges optional mixtures removed by the existing simpler-source rule. Candidate status semantics stay unchanged.
- Runtime cutoff metadata identifies 0.5 degrees as a synthetic teaching assumption. The rejected station-data-to-1Hz derivation is preserved in tools/science/rejected-wind-derivation.json and is not used as empirical calibration.
- The e2e launcher execs Java so its cleanup terminates and reaps the actual replay server. Old orphaned test servers were stopped; the new regression tests both success and failure cleanup.

## Verification

Clean build followed by tools/test-all.sh: PASS.

- 473 Java tests, 0 failures, 0 errors, 0 skipped.
- Screen typechecks: PASS; 19 screen unit tests passed (two e2e cases intentionally skipped by the unit command).
- Both Operations and Cases fake-server e2e tests: PASS when run against the launched server.
- Python launcher cleanup regression: PASS (success and failure paths).
- Four historical wind arithmetic tests: PASS; these verify arithmetic only, not the physical validity of the rejected derivation.
- git diff --check: PASS.
- Independent code review found a mid-batch clock transition bug; two regressions reproduced it and the fix was reviewed again. No further blocking review finding remained.

Test log: /Users/aditya/dev/fuelprint-backend-execution-2026-10-18/final-suite-r2.log.
The initial clean run caught an additional public-history bean ambiguity; replacing it with a science-private adapter removed the ambiguity, verified by the final run.

## Pending work and owner decision

The exact proposed capture-window amendment is docs/contract-proposals/2026-10-18-capture-windows.md. It is awaiting approval; contracts/ and AGENTS.md are unchanged.

A probe through the real SC1 puff world and sensors measured a polluted target BACKGROUND (one run: 2716.444879 ppb SO2) and a collection mean 3378.454094 ppb. Their difference 662.009215 ppb must be modelled as an actual temporal contrast; it is not the full enhancement above clean air. The proposal keeps the flight procedure and requires matching collection-minus-background predictions and human QC before usable window persistence.

Still required: drone-window integration; complete real-backend SC1–SC3 scenario paths with automation on/off; full measurement/covariance consistency; source-count decision and separate raw/final survival reporting; full-chain calibration and held-out evaluation; wind sensitivity and latency measurements. Screen-to-screen integration additionally requires actual Operations and Cases implementations. Windows execution and physical-phone SMS remain unverified.

No screens, 3D graphics or model training were started. No scientific coverage guarantee or physical validation is claimed.

## Files changed in this checkpoint

- `docs/contract-proposals/2026-10-18-capture-windows.md`
- `server/src/main/java/fuelprint/core/world/WorldLoop.java`
- `server/src/main/java/fuelprint/field/world/CaptureRun.java`
- `server/src/main/java/fuelprint/science/detect/TeachDetector.java`
- `server/src/main/java/fuelprint/science/plan/IntervalPlanner.java`
- `server/src/main/java/fuelprint/science/plan/SciencePlanner.java`
- `server/src/main/java/fuelprint/science/rank/CalibrationRunner.java`
- `server/src/main/java/fuelprint/science/rank/CompatRanker.java`
- `server/src/main/java/fuelprint/science/rank/FitEngine.java`
- `server/src/main/java/fuelprint/science/rank/FixedSensorInlets.java`
- `server/src/main/java/fuelprint/science/rank/InletTrack.java`
- `server/src/main/java/fuelprint/science/rank/ResponseModel.java`
- `server/src/main/java/fuelprint/science/rank/ScienceRanker.java`
- `server/src/main/java/fuelprint/science/rank/WorldProfile.java`
- `server/src/main/java/fuelprint/science/sensor/GasSettings.java`
- `server/src/main/java/fuelprint/science/sensor/Inlet.java`
- `server/src/main/java/fuelprint/science/sensor/SensorField.java`
- `server/src/main/java/fuelprint/science/sensor/TeachCaptureRules.java`
- `server/src/main/java/fuelprint/science/world/ScienceWorld.java`
- `server/src/main/java/fuelprint/science/world/WorldCapacityReached.java`
- `server/src/main/java/fuelprint/science/world/WorldProfile.java`
- `server/src/main/resources/science/rank/cutoff.json`
- `server/src/main/resources/science/rank/world-profile.json`
- `server/src/test/java/fuelprint/core/world/WorldLoopTest.java`
- `server/src/test/java/fuelprint/field/world/CaptureContinuityTest.java`
- `server/src/test/java/fuelprint/science/ScienceWiringTest.java`
- `server/src/test/java/fuelprint/science/detect/DetectorRankingWindowsTest.java`
- `server/src/test/java/fuelprint/science/detect/TeachDetectorTest.java`
- `server/src/test/java/fuelprint/science/plan/IntervalPlannerTest.java`
- `server/src/test/java/fuelprint/science/rank/CompatRankerTest.java`
- `server/src/test/java/fuelprint/science/rank/CutoffCalibrationTest.java`
- `server/src/test/java/fuelprint/science/rank/ResponseModelTest.java`
- `server/src/test/java/fuelprint/science/rank/ScienceRankerHistoryTest.java`
- `server/src/test/java/fuelprint/science/scenario/ScenarioRunsTest.java`
- `server/src/test/java/fuelprint/science/sensor/TeachCaptureRulesTest.java`
- `tools/science/rejected-wind-derivation.json`
- `tools/science/test_wind_drift.py`
- `tools/science/wind_drift.py`
- `tools/test-e2e.sh`
- `tools/tests/test_e2e_cleanup.py`
- `docs/backend-checkpoint-2026-10-18.md`
