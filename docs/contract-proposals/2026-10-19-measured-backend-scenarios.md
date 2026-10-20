# Measured backend scenario wording — owner decision pending

Prepared after six fresh V4 real-backend lifecycle cases, automation off/on. No contract amendment is applied by this proposal. Source mathematics, emissions, cutoff, pair policy and actual results are not changed to fit a story.

## Measured behavior

| Scenario | Initial sim300 fixed snapshot | After human QC | Planner at initial snapshot |
|---|---|---|---|
| SC1-NIGHT-PLUME | PROVISIONAL_SINGLE, S02 | PROVISIONAL_SINGLE, S02 | NO_USEFUL_MEASUREMENT |
| SC2-BLOCKED-INLET | PROVISIONAL_SINGLE, S02 | PROVISIONAL_SINGLE, S02; SO2 rejected after10validseconds, CO2 usable | NO_USEFUL_MEASUREMENT |
| SC3-ABOVE-THE-BOXES | NO_SIGNAL | PROVISIONAL_SINGLE, S03 after high survey | NO_USEFUL_MEASUREMENT |

Both automation settings produced these outcomes. UNKNOWN remains present throughout. SC2 CO2 accumulated60validseconds; its analytical window uses the first30consecutive valid samples. SC1/SC3 both gases accumulated30validseconds. The harness retains the same sim300 fixed snapshot after QC.

## Concrete wording proposed for the measured backend demonstration

- SC1: fixed observations already retain S02. A separately approved teaching survey demonstrates autopilot, capture stages and human QC, and the subsequent measurement still retains S02. Do not say the planner recommends a useful drone mission or that ambiguity was resolved by the flight.
- SC2: demonstrate the branch fault at collection second10, refusal to validate SO2, continued usable CO2 and human-only QC. Preserve the rejected raw channel and its reason. No claim of a completed Cases finding or retraining gate is established by this harness.
- SC3: no initial signal/alarm is present. A separately approved high survey obtains usable windows and subsequently retains S03. Do not describe the measured ending as inconclusive.

The present agreement's SC1 ambiguity-to-drone progression and SC3 inconclusive ending are unmet. The owner can approve this measured backend wording, or retain the existing narrative as an unmet freeze gate. Any future scientific redesign needs its own justified design and independent qualification; desired scenario answers must not choose physics or cutoff.

## Scientific limitation that must remain visible

Fresh V4 independent evaluation used216attempts to obtain60eligible episodes. Raw true-support compatibility was57/60; final exact-support survival was8/60 under the retained NOT_NEEDED policy. None of51true pairs survived final filtering:49raw-fitting pairs were removed because a constituent single fit, and2failed the cutoff. Only3of19PROVISIONAL_SINGLE outputs retained the exact true support. These are synthetic-domain measurements, not physical accuracy or final-support coverage guarantees. SC1's emission ratio25 is outside the calibration ratio500..2000.

This proposal does not change the approved pair policy, authorize ProspectiveInlets, or establish backend readiness. Test-only analyst orchestration creates APPROVED missions because teammates' ops/cases paths are unbuilt. Actual supervisor proposal/approval, cases/sealing, physical SMS and trained-model flows remain unverified.
