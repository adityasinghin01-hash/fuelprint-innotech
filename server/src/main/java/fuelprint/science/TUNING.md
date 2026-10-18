# Science tuning notes (part 1: world, sensors, detector, scenarios)

Only the emission strength was tuned. No rule (detector thresholds, capture limits, puff physics, sensor
response, noise) was changed to make a story work.

## What was tuned

`TruthLibrary.SO2_G_S` (in `science/truth`) is the SO2 emission of the one emitting source in each preset:

| Preset | Emitting source | SO2 g/s | CO2 g/s |
|---|---|---|---|
| SC1-NIGHT-PLUME | S02 | 20 | 500 |
| SC2-BLOCKED-INLET | S02 | 20 | 500 |
| SC3-ABOVE-THE-BOXES | S03 | 20 | 500 |

The nominal value in `world-profile.json` is 0.5 g/s. At 0.5 g/s the ground boxes see almost nothing
(steady-state S02 smoke at N2, 3 m inlet, wind from 270 at 3 m/s, 300 s: **1.7 ppb**), far below the alarm
rule's 25 ppb floor, so SC1 could never trigger. The 35 m release is well above the 3 m inlets, and the
smoke only reaches the ground as it spreads vertically (Kz = 1 m2/s).

## Measurements (steady smoke after 300 s, no noise, ppb SO2)

| SO2 g/s | S01 at N2 | S02 at N2 | S01 at (0,100,35) | S02 at (0,100,35) |
|---|---|---|---|---|
| 0.5 | 4.4 | 1.7 | 34.5 | 84.3 |
| 2 | 17.5 | 6.7 | 138 | 337 |
| 5 | 43.7 | 16.8 | 345 | 843 |
| 10 | 87.4 | 33.6 | 691 | 1687 |
| 20 | 174.7 | 67.2 | 1382 | 3374 |
| 40 | 349.4 | 134.4 | 2763 | 6747 |

Why 20 g/s: at 10 g/s S02's smoke at N2 (33.6 ppb) sits only 8 ppb over the 25 ppb floor, and with the 5 ppb
channel noise and sensor lag the 15-second run would be marginal. At 20 g/s the rise at N2 is about 2.7 times
the floor, the trigger is robust, and the drone at (0, 100, 35) reads about 3,400 ppb against about 5 ppb
background, so the capture is clear.

## What the headless runs show with 20 g/s (fixed seeds)

- SC1: N2 triggers at about sim second 94 to 108 (15 enhanced seconds). N3, further downwind, triggers later
  (about second 153). N1 (upwind) and N4 (far) do not trigger.
- A 30 s capture with the drone at (0, 100, 35) after a 120 s settle reads about 3,300 ppb SO2 on average,
  every second above 500 ppb.
- Separation of S01 from S02 at that point: for steady smoke, S02 gives 2.0 times the S01 reading at the
  target, and the target-to-N2 ratio differs by a factor of 5.1 between the two (S02 about 50, S01 about 8).
  That is the difference the ranker uses. This job does not test any ranking outcome.
- SC3: S03 emits from a 45 m release. A drone at (280, 140, 45) in the plume reads well above 500 ppb; the
  3 m inlets of all four boxes stay below 45 ppb and never trigger.
- SC2: same start as SC1; the collection-second-10 fault gives SO2 REJECTED (FLOW_FAULT, 10 valid seconds,
  0.083 L kept) and CO2 VALID (30 s, 0.15 L).

## Things that are assumptions, not tuning

- Sim time zero is 02:00 IST on 15 Jan 2026 (used only by the optional background profile hook).
- The detector keeps its own one-second history per box; the core may hand it only the newest readings.
- The smoke concentration at an inlet is sampled once per simulated second and held; transport, chamber and
  sensor response step every 0.05 s tick.
- Particle (`PM_ugm3`) readings are an optical estimate with the quality flag `OPTICAL_ESTIMATE`; nothing infers
  from them.
- CO2 at 500 g/s adds only a few ppm at ground boxes (it is a second channel, never a fuel signature).
