---
title: "FuelPrint Field plan"
subtitle: "Aditya Singh's part · shared so everyone knows what Field does and gives · 16 Oct 2026"
---

# Field plan

## What Field is

Field is the 3D world where the approved measurement actually happens. A drone leaves the depot, flies to the approved point, and samples the air at the real position of its inlet. What it reads depends on where the inlet really was and for how long, so a lazy flight gives a worse sample. Visitors can fly it from their own phone.

Field is built on Aditya Singh's Mac. Claude designs the look (scene, lighting, materials, the mission screen, the phone page). Codex writes the code. Nobody else edits `apps/field` or `fuelprint.field`.

## What is in the 3D world

| Thing | Where it comes from |
|---|---|
| Real context buildings | The Kundli map crop in `data/context/derived-scene-crop.json` (991 footprints). At most 300 are drawn as boxes, all at an assumed 8 m height. Credited to Overture Maps, Google Open Buildings, Microsoft and OpenStreetMap. |
| Six fictional factories S01 to S06 | `world-profile.json`: 80 × 60 m sheds with 8 m roofs. Stacks drawn up to their release height (25 to 45 m), labelled "synthetic". |
| Four sensor boxes N1 to N4 | Poles with the inlet at 3 m, a status light and a small reading label |
| Roads | The rectangle from −400 to 400 m east and −350 to 250 m north, a centre cross and a spur, 6 m wide |
| Depot | At the origin. The drone starts and returns here. |
| Wind | An arrow and drifting dust specks that show direction and speed only |
| The drone | Body, rotors, and a separate inlet marker so everyone can see where the air is taken |
| Reading trail | Coloured dots along the drone path showing what it actually measured |

There is **no smoke plume coming out of the real source**. Drawing it would give away the hidden answer. Only measured values appear in the world. In the finale, a model prediction overlay can be added, clearly labelled as an estimate.

## The drone, in numbers

| Rule | Value |
|---|---|
| Top speed | 3 m/s, acceleration 1 m/s² |
| Allowed area | East and north within ±450 m, height 2 to 80 m |
| Clearance | 2 m from any building or stack; the server refuses to fly through them |
| Battery | 64 Wh usable, 12 Wh reserve |
| Power use | Hover 160 W + instruments 10 W + speed² W |
| Server tick | 20 times a second; screens get 10 updates a second |

The server moves the drone, not the browser. The phone only says "I want to go this way, this fast". If the return trip would eat into the reserve, the screen warns and the mission must return.

## A mission, step by step

1. Operations approves a mission. It appears on laptop 2 with its target point, height band and method.
2. The field operator presses "Let a visitor fly" and a 6-letter code plus QR appears for 120 seconds.
3. A visitor opens `/field/controller` on their phone (same Wi-Fi), types the code, sees a two-word phrase, and the operator confirms the phrase matches. The visitor joins the queue.
4. The first visitor in the queue presses "Fly" and holds the lease. One pilot at a time.
5. They fly to the target. Readings at the inlet change as they move.
6. At the target, the capture runs in stages: setup 30 s, background 30 s, purge 30 s, settle at least 120 s, collect 30 valid seconds, recover at least 120 s, then review 30 s. While the drone hovers still, these stages can run 15 times faster so a visitor does not wait 6 minutes.
7. The drone returns to the depot.
8. The QC reviewer on laptop 2 checks each channel. A channel that broke a rule cannot be passed.
9. Each channel becomes a `screening_record` (VALID, LIMITED or REJECTED with reasons), which Cases and Operations receive.

## The capture rules

From the method `TEACH-NODE-ONLINE-2`:

- SO2 branch pulls 0.5 L/min, CO2 branch 0.3 L/min, each within ±5 %.
- Collection needs 30 consecutive valid seconds. A gap or fault ends the run of valid seconds; two good pieces are never glued together.
- 30 valid seconds gives 0.25 L of SO2 sample and 0.15 L of CO2 sample.
- Settling must reach a model error of 5 % or less, after at least 120 s.
- In the blocked inlet scenario, SO2 flow drops to zero at collection second 10. The SO2 number on screen still looks normal, but it is flagged FLOW_FAULT and adds no valid volume. The 10 good seconds before the fault (0.083 L) are kept in the record but do not make a valid 30 s window, so SO2 is REJECTED. CO2 can still finish and be VALID.

## Phone and keyboard rules

| Rule | Value |
|---|---|
| Input rate | About 10 messages a second |
| Dead man | No fresh input for 400 ms means the server stops the drone (target: stopped within 500 ms) |
| Lease | 2 s, renewed only by fresh valid input |
| Leaving the page | Phone sends one "neutral" message; the drone stops |
| Reconnect | Stays stopped until the visitor presses "Fly" again; old input is never replayed |
| Take over | The operator can take control at any time; the visitor's lease ends first |
| Keyboard on laptop 2 | W A S D to move, R and F for up and down, Space to stop |

The phone page is two large touch pads (left: move, right: up and down), a stop button, the lease timer, and the drone's live height and battery. It needs no camera, no location and no app install.

## What Field gives others

| To | What |
|---|---|
| Operations | Mission state changes (LEASED to COMPLETE) |
| Cases and Operations | `screening_record` per channel after the review |
| Everyone | Vehicle positions in every tick |

## What Field needs

| From | What |
|---|---|
| Core | Server, sessions, `/ws/live`, `/ws/pilot`, records |
| Science | `ObservationProvider` readings at the drone inlet each tick; `CaptureRules` for settle error |
| Operations | Missions in state APPROVED |

## Dates

| Date | Field work |
|---|---|
| Sat 17 Oct | Field package skeleton, vehicle physics with limits and collision checks |
| Sun 18 Oct | 3D scene (map crop, factories, boxes, roads, depot), drone model, keyboard flying |
| Mon 19 Oct | Pairing, queue, lease, phone page, capture stages, QC review screen |
| Tue 20 Oct | Join day |
| Wed 21 Oct | Rehearsal on real phones (Android Chrome and iPhone Safari), frame rate check |

The 3D view must hold at least 30 frames per second on laptop 2. If it can't, it drops detail, never science.

## Finale work

- Instrument cutaway: the inside of the sampler with its two gas branches, pumps after the sensors, flow meters, and the separate particle path.
- 3D lab workbench showing the real specimen record, quantity, blank and QC results from Cases.
- The ground van on the road network, with its own inlet at 3 to 6 m.
- A model prediction overlay, labelled "estimate, not measured".
- A practice score based only on following the procedure (approval, readiness, proper rejection, safe return), never on guessing the factory.
- Reduced-motion mode and a 2D fallback map that uses the same server rules.
