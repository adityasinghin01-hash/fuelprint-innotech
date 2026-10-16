# 05 — Evaluation, meaningful tests and release gates

Nothing in this section has executed. All acceptance rows remain PLANNED_NOT_EXECUTED. Documentary consistency verification of the plan is separate from these future product tests. Existing narrow29Python/7JavaScript checks and R3's769checks cannot establish application, device or physical acceptance.

## Test evidence and fixture policy

The92 unchanged inherited fixtures are at references/inherited/fixtures-92.json. Their exact given/when/oracle/tolerance/forbidden outcomes survive. registers/questions-92-trace.json maps each to new tasks, outputs, owner/reviewer, actual future executable path, R3 override, cost and physical gates; CSV is the flat view.137 inherited acceptance rows (92question+25finding+20cross-system) are individually reconciled in registers/inherited-acceptance-137-reconciled.json. registers/unsupported-33.json names every unsupported inherited claim. Preserve those flags historically even after later execution; attach a new actual result and scope, never rewrite history to imply the inherited claim was supported.

Exact legacy numeric scenarios (old9.0834/16.0256W power, old flows/dwell/LOQ or working-capital ledgers) remain labeled legacy regressions. Add separate production-profile assertions for18/24W, selected±5%flow, full method stages, teaching method-specific limits, D₹160k and new evaluation. Do not silently replace fixture inputs. Compare old and new with named fixture/profile hashes. Legacy enum names map to R3 serialized states via IP18.

Each meaningful implementation test uses actual public inputs/records and an independently derived oracle or behavioral invariant. Run a failing assertion before implementing a new critical behavior; confirm the intended failure, implement, then retain the pass output. UI tests assert backend run/mission/attempt/case IDs and effects, alongside screenshots. Invariant cases challenge hidden-label independence, no evidence deletion, one authority/lease and idempotent effects. Avoid tests that merely restate a generated configuration.

Completion evidence per test: ID/fixture hash, actual executable/command, code+lock+model/profile/input hashes, raw stdout/stderr/record/export hashes, actual device/OS/browser when relevant, pass/fail/NOT_TESTED, dated actual reviewer and limits. A skip or synthetic stub is not a product pass. Tests run only after build authorization.

## Additional implementation tests beyond inherited137

| ID | Concrete setup and assertion | Outputs / task |
|---|---|---|
| NT-CONTRACT | Same valid/invalid corpus through Ajv2020/Python: duplicate key/nonfinite/lone surrogate/decimal overflow/unknown field/wrong unit/frame. Both reject identically; generated TS builds;41records/20original commands plus IP extensions present. | W01, conformance output |
| NT-STORAGE | Clean and prior-version DB with known data; FK/uniqueness/immutability apply. Inject upgrade failure: no partial schema. Blob crash before/after rename/commit returns no acknowledged missing reference. | W02, migration/crash matrix |
| NT-JOBS | Same input twice -> one job/effect. Crash worker: same pure recompute or failure. Initial attempt+3I/O retries at1/2/4s then FAILED_REQUIRES_REVIEW; numeric failure never relaxes settings. | W03, job/receipt records |
| NT-CAPACITY | Live900s/8outlets and offline1020s+prehistory; checkpoint/journal recovery. Limit+one tick pauses without deleting mass; exhausted episode retained. Measure20Hzscience/10Hzcommit. | W11/W04, capacity report |
| NT-SOLVER | Analytical single/pair/collinear/zero-column, invalid covariance and duplicate uncertainty-component cases. Check independent gas amplitudes, rank/nonconvergence retention and hidden-label invariance. | W12/W13, numeric records |
| NT-ZERO-UTILITY | Nested pair contains single, all action boxes overlap -> score0/no arbitrary winner. D=I,z=(4,3),n2,cutoff.5 gives intervals[3,5],[2,4]. Denied/unreachable(280,140,3) excluded. Enlarging compatible sets cannot increase lower-bound separation. | W14, proposal/rejection records |
| NT-ADAPTER | Same boot/seq/hash retry accepted once; changed hash quarantined. Receive-time change leaves capture unchanged. Optical credential cannot ingest gas; physical device cannot select synthetic method. No interpolated valid gaps. | W16, ingest journal |
| NT-ACCESSIBILITY | Keyboard three outcomes, screen-reader/error focus,200%zoom,44pxcontrols, color-independent reasons, reduced motion/WebGL kill. Same backend IDs/QC through DOM and3D. | W21, browser/device record |
| NT-4500-FRAMES | Actual4500frame pack fits measured caps/≤500members, raw bytes recover exactly. Duplicate IDs/base64/index/decodedbudget+1 fail. Oversize full case explicitly refuses seal. | W24, pack/verifier report |
| NT-CALIBRATION | Eligible m<40 or missing family -> no cutoff. m40 uses sorted rank39=ceil(.95×41); historical cutoff cannot be imported. Evaluator-only eligibility. | W26, calibration manifest |
| NT-STATISTICS | Correct single/false-exclusive/invalid/missed -> total4,issued2,error1/2,coverage1/2; zero assertions -> NOT_ESTIMABLE. No pooled binomial guarantee for stratified rates. | W27, metric audit |
| NT-IID | Families/subcategories IID with replacement, not fixed30/family. No post-label retune/extension. Zero errors needs≥72issued to make one-sided97.5%upper<.05; fewer withholds conditional safety claim. | W28, separate report |

Use node:test for pure committed reducer/control logic, pytest for authority/science/QC/parser/security/evaluation, Playwright for three-app integration. Actual phone/host/lab evidence is separate; headless emulation never certifies a phone or physical method. Deterministic inherited numeric oracles use native-unit atol1e−8 unless stricter stated; matched full-state schedule rtol1e−8/atol1e−10; exact IDs/enums/counts/bytes are exact. Freeze quadrature domain/grid/tail bound before observing mass residual: target combined relative error≤1e−4 using independent refined-grid convergence/tail bound. Failure requires refinement/versioned report, never post-hoc tolerance widening.

## Frozen evaluation FP-EVAL-3

W26/C1 owns generator/protocol; O2 reviews numerical/domain rules. W27/C1 reports, C2 reviews denominator/fairness. W28 is separately budgeted optional IID work. If the same human/AI authors and evaluates, label author evaluation. Private labels prevent client/inference leakage but do not become professional independent review.

Development80=10/family. Calibration80=30nominal valid singles+10valid pairs+10weak supported+10bounded met/height(5each)+10bounded constant background offsets+5unknown+5invalid. Actual eligible supported m≤70, require≥40/domain coverage; unknown/invalid have no fabricated true support. Freeze code/model/schema/algorithm/covariance/cutoff/protocol hashes. Privately generate fresh final configurations/seeds and commit manifest hash; assign opaque shuffled FT0001–FT0240. Historical240episodes are development/regression only. Public observed replay contains no final truth/seed/config sufficient to regenerate labels.

Primary final is8families×30. Full generator draw rules are binding in copied R3 chapter10 and registers/evaluation-protocol.json; abbreviated labels cannot replace them.

| Family | Fixed composition |
|---|---|
| Nominal |10no-event,10brief10s spike,10sustained single; diagnostic source IDs rotated before private amplitudes |
| Wind/local met |10direction bias5–10°,10speed20–40%,10direction20–40°, balanced signs; no hidden mismatch flags |
| Height/inventory |15height error15–30m balanced signs,15xy error15–40m random direction; public inventory unchanged |
| Weak |15SO₂.02–.1g/s,15CO₂5–50g/s; other gas nominal |
| Unknown/external |10unregistered,10registered+unregistered,10three registered |
| Mixture |10close S01/S02,10uniform other14pairs,10imbalance1:10 with independent gases |
| Invalid |10mobile SO₂flow fail collection second10;10reboot/link/clock faults(4/3/3);10overgas5s from collection second5+recovery |
| Background |10SO₂drift0→15ppb/180s,10common step±15ppb at120s,10negative interferent10–30ppb from120s |

Use pinned NumPy PCG64/SHA256-derived independent family/geometry/schedule/noise/fault/policy streams; preserve draw order/generator/hash/rejections privately. Nominal gases independent SO₂.1–1g/s,CO₂50–1000g/s; met3m/s270° with development1–5m/s/±10°. Sustained sources active prehistory→end, spikes uniform start120–160s,duration10; no-event all0. Unknown xy uniform[-450,450]² rejecting20mregistered disks/occupied volumes,H15–55m. Pairs/triples without replacement; weaker member coin-flipped, independent nominal gas draws×.1. Effective H≤0 resampled/logged, never clipped.≤180sprivate history counts toward state cap; stationary noise initialized. Final drift holds after180s; calibration offsets±5ppb are declared supported systematics, not the final unmodeled ramp.

All policies get180s observed initialization and the same840s follow-up/common end1020s. Fixed instruments continue to that end for every policy. Same environmental/common-noise trajectories, differing actual mobile paths. One approved follow-up maximum, no shortened method to fit. Active time/energy/review/cancellation/infeasible/capacity/numeric failure remains in denominator. Fixed-only has zero active cost, not equal realized effort.

Policies: FIXED-ONLY(no mobile); FIXED-SURVEY(predeclared ground120,100,3,no truth replacement); RANDOM-FEASIBLE(uniform public feasible catalog, independent policy stream); LEGACY-NOMINAL-PORT(observed-only SO₂nominal single fit, background−sigma/0/+sigma, fitted emissions, own frozen cutoff; original source hashes); ROBUST-INTERVAL(full singles/pairs/grid/covariance/conservative bound, zero/budget stops). Adapted legacy rates cannot be relabeled historical25/240. No policy receives hidden emissions.

Evaluator-only event eligibility is sustained25ppb SO₂enhancement≥15s at predeclared accessible reference sites; never creates dispatch. Source-active truth is reported separately, including all-box misses/ineligible activity. Relevant contributors for useful improvement are≥1ppb SO₂or≥1ppm CO₂for15valid analyzed seconds; sensitivity.5×/2×. Improvement requires a new valid window, relevant contributors retained and strict reduction in candidate union. Unsupported exclusivity uses all active contributors in asserted scope, including weak sources. Every provisional single is an issued assertion, even with qualification withheld.

Retain wrong source, unsupported alone assertion, false exclusion, missed/false detection, no-signal/unknown/ambiguous/invalid, capacity/numeric/no-action, qualified coverage, time/energy/crew/review/repeats/cancellations. Zero issued -> conditional error NOT_ESTIMABLE. Hidden challenge may fool model; no scripted exoneration. FP81–92 and calm/wet/wake regressions are additional unsupported-domain cases, outside240rate denominator.

Stratified pooled/family rates are descriptive. Individual family unconditional independent bounded-Bernoulli bounds use rate±sqrt(ln40/(2×30)),clamped[0,1], individual95%only; fixed subcategories are not automatically IID. Conditional-on-issued family rates descriptive. Two predeclared robust-vs-fixed-survey/random comparisons use D∈[-1,1], one-sided97.5%lower meanD−sqrt(2ln40/240)≈meanD−.1753; both must exceed0 for stronger usefulness claim. Also10,000paired within-stratum episode resamples as approximate/descriptive intervals. No joint95%guarantee across every metric/family.

Optional IID confirmation is separate240episodes after the same freeze: families uniform1/8/subcategory weights/IDs/signs independently drawn; no fixed balanced counts/rotations. Both all-episode false-assertion and conditional error among issued assertions require one-sided97.5%exact-binomial upper<5%, coverage disclosed; zero errors needs at least72issued. No sampling extension until it passes. Total final may480, with two pools kept distinct. Failure preserves report/withholds claim; algorithm changes require new version/new untouched data. Gaussian-family tests never qualify physical performance.

## Gate hierarchy and stop rules

| Gate | Required output | Failure action |
|---|---|---|
| M1 workflow | Three real app outcomes + persistence/restart/public replay/minimal separate verification | Repair; decorative expansion cannot replace this gate |
| M2 science | Independent units/history/flow/response/covariance/capacity/provenance assertions | Named fixture mode only; no full causal-world claim |
| M3 reasoning | Solver/action oracles, stale-input guards, zero/unknown outcomes | MODEL_NOT_QUALIFIED; withhold unsupported recommendations |
| M4 interaction | Actual vehicle/control/lease/deadman/keyboard behavior | Verified host-control/DOM reduced release or no movement |
| M5 Cases/evidence | Distinct media/specimen/lab/custody, full adversarial verifier/4500frame test | Restricted M1 online-only archive if passed; no full lab claim |
| M6 evaluation | Frozen dev/cal/final report with all failures/intervals/coverage | Workflow may release with no attribution/superiority claim |
| M7 exhibition | Supported-device60min rehearsal, offline launch, restart/backup/restore, exclusions | Smaller tested envelope or no exhibition-ready claim |
| P0–P6 physical | Actual permission/competence/serial/method/raw measurements/uncertainty/funded scope | Restrict affected physical use, reference-only/ground fallback or stop |

Numerical conformance alone never upgrades model qualification. SYNTHETIC_SCOPE_ONLY for source-candidate claims requires named frozen model/domain and actual prespecified safety/coverage evidence; without IID safety results there is no low-false-certainty claim. Source-discrimination usefulness additionally requires the comparison gates. Even if both claims fail, the investigation/evidence workflow can release within its demonstrated scope. Do not change final labels/cutoff to make a positive result.
