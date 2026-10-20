# Offline full-chain cutoff qualification

`FullChainCalibrationRunner` generates independent teaching-model episodes through actual `PuffWorld` physics at20Hz, `SensorField` transport/chamber/sensor response, `TeachDetector` fixed windows and `TeachCaptureRules.windows` gas/flow averaging. It does not read scenario truth, use scenario seeds, silently remove plume from background or write the runtime cutoff resource.

The calibration snapshot contains all fixed detector windows saved through sim300 and one subsequent two-gas capture. Sources emit constant random rates from run0. This is the inverse model's explicit source hypothesis. The recorded inlet takes a straight line from depot(0,0,2) at at most3m/s to (0,100,35) or (120,100,35), with whole-second arrival. It then records30s SETUP,30s BACKGROUND,30s PURGE,120s SETTLE,30s COLLECT. This route is a declared calibration approximation: it does not reproduce the real field autopilot acceleration, obstacle routing or body-to-inlet offset. Every20Hz inlet pose and every1Hz raw gas/flow reading is available through `Generated` for independent checking and whole-ranker evaluation.

Supports are uniform over six singles and fifteen pairs. Reported wind is U(240,300)degrees and U(2,5)m/s. Per-source SO2 is log-uniform0.25..50g/s, and CO2 is SO2 times1000 times log-uniform0.5..2. Wind direction drifts as stationary AR1 around the reported wind, baseline SD0.5degrees/time scale200s. Speed has independent1% relative stationary drift at the same time scale. Sensor noise is the shared actual synthetic profile. The measured BACKGROUND can contain plume.

Use one heavy science/Maven job at a time. From repository root:

```bash
tools/full-chain-calibration.sh benchmark 2 /tmp/full-chain-development.json
tools/full-chain-calibration.sh calibrate 60 /tmp/full-chain-calibration.json 0.5 200
# Supply a reviewed finite candidate cutoff explicitly; this does not install it.
tools/full-chain-calibration.sh evaluate 60 /tmp/full-chain-evaluation-v4.json 0.5 200 REVIEWED_CUTOFF FPEVAL4 10000
```

The runner rejects output paths inside `src/main/resources`. Its default is a development benchmark under `target/`. FPCAL, FPEVAL4 and FPDEV use disjoint explicit seed namespaces. FPEVAL is historical V3 evaluation and cannot qualify V4. The evaluation CLI defaults to FPEVAL4 and accepts only that namespace; the optional eighth argument sets the starting attempt index. Regression probes use indices0..5 and20..23, while final V4 qualification predeclares10000..11999 before measurement. Reports retain the start index. Output checkpoints after every attempt, retaining all ineligible episodes, failed eligible fits and generation exceptions. Eligible failures rank as infinity; they are not dropped from the conformal denominator. At least40 eligible episodes are required; requested production qualification uses60 and rank58, ceil(0.95*(60+1)). Reports state whether the requested count was reached.

Coverage is conditional on the same NO_SIGNAL gate used by the ranker. Evaluation reports the eligible denominator and every attempted episode, including generation failures whose eligibility is unknown. Raw true-support fit within the cutoff and final survivors after NOT_NEEDED filtering are reported separately. Retain both; a nested pair may fit raw while the retained single removes it from the final display. All fit/generation failures remain visible. Record SD/time scale sensitivity independently over direction SD0.5/1/3/5degrees and time scales30/200/600s; do not select the model domain by desired SC1 outcomes.

Before running60+60 episodes, inspect the two-episode development benchmark: wall time, per-episode simulation/fit time, stage/gas quality, excluded windows, eligibility and any failure. Runtime resource installation requires parent/owner review of the measurements and separate provenance, and is outside this tool's behavior.

V4 corrects the fixed-detector electrical uncertainty to use the actual background/collection sample-time covariance. All V3 calibration and evaluation results remain historical. Recompute calibration and independently evaluate V4 with frozen source/class provenance. Runtime loading requires exact algorithm/world/response identity, episode count, rank, sorted IEEE754 statistic hash and equality of the installed cutoff to its declared order statistic. These checks establish artifact consistency, not physical accuracy or final-survivor coverage.

The completed V4 evaluation has now observed FPEVAL4 indices10000..10215. Re-running those seeds reproduces the recorded result; it is not another untouched evaluation. Any future change to attribution statistics needs a new predeclared untouched seed range, fresh calibration and independent evaluation. Preserve the original freeze and distinguish planner/disclosure/boundary-only changes explicitly in provenance.

The baseline emission-ratio domain is500..2000gCO2/gSO2. SC1's current scenario ratio25 lies outside that declared domain. Even when independent per-gas amplitude fitting accepts such a scenario, held-out coverage measured in this domain is not a formal SC1 coverage guarantee. Retain scenario regression outcomes separately. Broader physical domains require an explicit predeclared design and independent calibration/evaluation; do not widen the domain after inspecting desired scenario outcomes.

For the predeclared wind sensitivity grid, keep the reviewed baseline cutoff fixed in all twelve cells. Do not recalibrate each cell:

```bash
tools/full-chain-wind-sweep.sh /tmp/full-chain-wind REVIEWED_BASELINE_CUTOFF 60
```

Each SD/time-scale cell uses a disjoint `FPWIND/cell` namespace, indexed SD-major over0.5/1/3/5degrees and time-scale-minor over30/200/600s. Every cell retains all attempted episodes until60 eligible observations and runs the whole ranker to report raw true-support coverage separately from final survivors. The script reuses a completed output only if its mode, requested count, fixed cutoff and wind domain match. Baseline calibration/evaluation, development probes and wind cells have disjoint seed ranges. Whole-ranker development evaluation must be timed before starting the full sweep, and the sweep must be serialized with other heavy science work.
