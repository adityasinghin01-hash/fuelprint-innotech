---
title: "FuelPrint study pack"
subtitle: "The whole idea for all five of us · mostly for Abhivansh, our pitcher · 16 Oct 2026"
---

# Study pack

**Who reads what.** Everyone reads parts 1 to 6 and part 9. Abhivansh reads everything, including the hardware, the money and all 92 judge questions. The schedule at the end is his.

## 1. The two-minute pitch

> Industrial pollution investigations in NCR are slow and messy. In January 2026 the Commission for Air Quality Management ordered 16 industrial units to close, 14 of them in Sonipat, for problems ranging from missing consents to unapproved fuels and broken pollution-control equipment. An inspector facing a pollution spike has to guess which factory to visit, when, and what to measure.
>
> FuelPrint is an AI-assisted investigation system. Sensor boxes watch the air. When one sees a sustained sulphur rise, FuelPrint ranks which registered factories could explain it, and also keeps "unknown source" and "can't tell" as real answers. Then it plans the one next measurement that would best tell the remaining suspects apart, checks it is reachable and safe, and a human approves it. A drone (you can fly it from your phone) takes that measurement. Every sample is checked channel by channel, the inspector's specimen is kept separate with seals and handovers, and the whole case is sealed so any tampering shows up.
>
> What makes it different is honesty. A box can miss a plume entirely, and FuelPrint says so instead of calling the area clean. It never claims to detect a fuel or prove guilt. It tells the investigator what the evidence supports and what it doesn't.
>
> Today it runs on a synthetic estate laid on the real Kundli map. The hardware path is costed in stages, starting with a ₹50,000 two-day test against a reference SO2 analyser. Come and fly the drone.

Keep to this order: problem, what we do, why it's different, what's real today, invitation. Never add "detects coal" or a percentage.

## 2. The problem in India, and what the numbers mean

| Fact | Source | What it does NOT mean |
|---|---|---|
| On 9 Jan 2026 CAQM ordered 16 NCR industrial units closed, 14 in Sonipat. Reasons included missing consent to establish/operate, absent or ineffective pollution-control devices, unapproved fuels, GRAP violations, diesel generator issues and visible emissions. | PIB press release, 9 Jan 2026 | It does not measure how accurate FuelPrint is or how much time it would save |
| On 15 Sep 2026 a drive in Hapur inspected 22 units: 16 were non-compliant and 12 got show-cause notices. Problems included scrubber bypasses and leaks, online monitoring problems, missing stack reports and consent or fuel mismatches. | PIB, 16 Sep 2026 | It was a targeted drive. Never say "73 % of factories break the rules". |
| CAQM rules change with the date: Direction 98 (20 Feb 2026, industrial particulates), a 15 Apr 2026 change to the approved fuel list under Direction 65, and Direction 104 with revised GRAP (29 Sep 2026). | CAQM website | There is no simple, timeless "coal is banned everywhere" rule we can encode |

**Existing tools and how we differ.** CPCB's ambient network and AQI show city air quality but can't point at a chimney. SAMEER takes citizen complaints. CPCB's online emission monitoring (OCEMS) watches a factory's own stack. PRANA tracks city action plans. Companies like Oizom and Atmos sell sensor networks and dashboards. FuelPrint adds the step in between: from an observed event to the next useful measurement to a case record with honest limits. We never say we are the first or that nothing similar exists.

**Our history.** FuelPrint won first place at IDEAVERSE as "find factories burning coal at night from the SO2 to CO2 ratio". Research then showed that this ratio can't prove a fuel (scrubbers and blends change it) and that sensors can miss plumes. We kept the idea and made it honest. If a judge asks "why did you change?", say exactly that.

## 3. How FuelPrint works, in one run

1. Four boxes (N1 to N4) report SO2, CO2 and particles every second.
2. An alarm rule fires only after 15 valid seconds in a row are clearly above a fresh background.
3. The ranking tests every registered factory (S01 to S06), every pair, and a range of weather errors. It keeps the explanations that still fit, plus unknown.
4. The planner looks at up to 8 possible measurements, throws out the unreachable or unsafe ones, and picks the one that best separates the remaining explanations per minute of work. It may say "no measurement would help".
5. A supervisor approves. Any change in wind or analysis cancels the approval.
6. The drone flies (by phone or keyboard) and samples at its inlet, through all the stages.
7. Each channel is checked: VALID, LIMITED or REJECTED with reasons.
8. The ranking updates with the new evidence.
9. Cases: a separate inspector specimen, custody handovers, lab result with QC, a limited finding, a sealed and verifiable evidence pack.
10. The board decides and previews a public notice that names no factory.

## 4. The science in plain words

**Effective release height.** Hot smoke rises after it leaves the stack, so the plume travels higher than the stack top. Our model calls the total the effective height: 25 to 45 m for the six factories. A sensor at 3 m may sit far below the plume. In our older test code, a 20 m stack's plume centre was already 30 to 48 m up at 100 m downwind, depending on the weather.

**Puffs.** The simulation releases a small puff of gas 20 times a second from each active stack. Each puff drifts with the wind and slowly spreads out (a Gaussian puff). The ground reflects it back up. When the wind turns, puffs already in the air move with the new wind from where they are; old pollution doesn't jump. When a source stops, the puffs already released are still there.

**Wind "from".** 270° means wind from the west, blowing east. Our default is 3 m/s from 270°.

**Background.** Clean air is not zero. Our background is 5 ppb SO2 and 420 ppm CO2, and it drifts slowly. Every measurement is compared to a background measured close in time.

**Sensor lag (T90).** A sensor takes time to catch up with a change. T90 is the time to reach 90 % of a step. In our model SO2 takes 60 s and CO2 30 s. A short puff can be missed or flattened, and that is a valid result.

**Noise.** Each sensor adds its own random wobble (5 ppb SO2, 2 ppm CO2 in the model) on top of the shared background drift.

**Why CO2 and SO2 together.** Burning anything makes CO2, so the CO2 rise shows how much exhaust you caught. SO2 comes from sulphur in the fuel or the process. The ratio of the two rises is a sulphur-bearing clue. It is NOT a fuel test: scrubbers remove SO2, ash keeps sulphur, blends change the ratio, and some processes release SO2 with any fuel. Codex's research found two hypothetical cases with different sulphur in the fuel that gave exactly the same ratio.

**Particles.** PM2.5 is a size of particle, not a gas. Our particle reading is an optical estimate and is never used to name a fuel.

**Why a box can miss.** The wind can carry the plume beside the box, the plume can pass above it, a building can deflect it, or it can be too diluted. That is why "no alarm" never means "clean".

**The alarm rule (TEACH-DETECT-1).** Take a valid 30-second background no older than 180 s. Fire only after 15 valid one-second readings in a row are each at least 25 ppb above it (or three times the uncertainty, whichever is bigger). A gap or fault resets the count. A 10-second spike does not fire.

## 5. The AI part

**What the AI does.**

- **Ranking.** For each explanation (6 single factories, 15 pairs) and each of 27 weather variations (wind direction ±10°, speed ×0.8 to 1.2, height ±10 m), it fits how strongly each factory would have to emit to match the readings, never allowing negative emissions. Explanations whose error stays under a cutoff survive. The cutoff is calculated from separate calibration runs, never tuned on the final test.
- **Outcomes.** INVALID, NO_SIGNAL, UNKNOWN_OR_MISMATCH, AMBIGUOUS_OR_OPTIONAL_MIXTURE, PROVISIONAL_SINGLE. Even a single surviving factory never rules out an unregistered source.
- **Planning.** For each feasible action, it predicts the range of readings each surviving explanation would produce there, and scores how far apart those ranges are per minute of the full procedure. If all ranges overlap, the answer is NO_USEFUL_MEASUREMENT, and the investigator saves a wasted trip.

**What the AI never does.** It never approves its own mission, never sees the hidden truth, never turns a fit into a probability, never writes an official outcome, and never names a fuel.

**Honest weakness.** In an older synthetic test, the system made 25 wrong single-source calls in 240 episodes, mostly with mixed sources and weather errors. We keep that number and show we learned from it: the new version admits pairs, unknown sources and "can't tell".

**An LLM?** Optional in the finale, only to rewrite the explanation in plain words. It can't change any number or decision.

## 6. Samples and evidence

**Three different things.** The drone's online air reading (a screening record), a retained filter or container (finale), and the inspector's specimen are three separate items. One never becomes another.

**Custody.** The specimen has a seal ID. Every handover is a new record: who gave it, who took it, seal intact or broken, when. Mistakes are fixed by adding a correction, never by editing.

**Lab QC.** Blank, duplicate, spike recovery and hold time are checked against fixed limits. A failed blank fails the result even if the number looks fine. Below the reporting limit is "below LOQ", not zero.

**Sealing.** Every cited record is saved as exact bytes. A manifest lists each file with its SHA-256 fingerprint. Verifying recomputes them. Change one byte and verify fails.

**The seven checks.** Integrity, key trust, lineage, trusted time, custody documentation, measurement qualification, legal suitability. Each is shown separately. A passing integrity check proves the bytes didn't change, nothing more.

**The law.** India's Air Act section 26 describes official sampling: notice to the occupier, sampling in their presence, marking and sealing, sending to a recognised lab. Our inspector flow imitates the shape of that workflow. Legal suitability is always "not assessed".

## 7. Hardware (Abhivansh must know this well)

Nothing physical has been bought, built or tested. These are costed plans with conditions.

**Path B: optional bench box, at most ₹2,000.** An ESP32 board with a Sharp GP2Y1010AU0F optical dust sensor, protected 5 V supply and USB. Prices seen on 14 Oct: ESP32 ₹499, Sharp sensor kit ₹549 (a DHT11 temperature/humidity sensor ₹99 was an optional extra), leaving room for wires, protection and a box. It shows a relative dust signal only. It cannot detect gases, fuel or calibrated PM2.5. If someone blows smoke at it, that proves nothing about fuel.

**Path C: the first real test, about ₹50,000.** Rent time on a reference-grade SO2 analyser (Teledyne API N100) at a permitted fixed site for two days. Goal: show we can measure a sustained 25 ppb SO2 rise with an uncertainty of 10 ppb or better. Breakdown: reference 2 days × ₹15,000, operator ₹8,000, assistant ₹4,000, travel, consumables, review and contingency ₹2,000 each. An optional LI-COR LI-850 CO2 analyser adds about ₹25,000.

**Path D: ground pilot, ₹1,60,000, only after C works.** Five days of narrow SO2 measurements with lab QC and review. Optional CO2 adds ₹60,000.

**The custom sensor node (later).**

| Part | What it does | Its limit |
|---|---|---|
| Alphasense SO2-B4 + matched ISB board | Electrochemical SO2 cell | T90 under 60 s; reacts to other gases (NO2 pushes it negative); not accurate until tested in the whole box |
| Vaisala GMP252 | CO2 probe, flow-through, RS485 | ±40 ppm absolute in low range, which is not proof it can see a 20 ppm rise |
| Sensirion SPS30 | Optical particle estimate, own air path | Not lab-grade PM2.5 |
| ESP32-S3-DevKitC-1-N8R8 | The microcontroller: reads sensors, logs, Wi-Fi | |
| TI ADS1220 + REF5040 | 24-bit ADC with a 4.096 V reference for the SO2 cell | 24 bits is not ppb accuracy |
| SHT45, BMP390 | Temperature, humidity and pressure | |
| 2 × KNF NMP015 12 V pumps | One pump per gas branch, after the sensor | Catalogue flow is not installed flow |
| 2 × Sensirion SFM5500-2slm | Measure each branch's flow | Must be checked at real pressure and temperature |
| ISO1410 isolated transceivers | Protect the serial links | Isolation also needs separate power domains |
| microSD | Local log if the network drops | |

**The air paths.** Each gas has its own path: inlet, small sensor chamber, pump after the sensor, flow meter, exhaust. Targets 0.5 L/min for SO2 and 0.3 L/min for CO2. Particles have a separate path. Gas tubes are short PFA or PTFE because SO2 sticks to wet surfaces.

**Power.** Protected 24 V feeds isolated instrument supplies, 12 V for pumps, a quiet 5 V for the analog parts and 3.3 V for logic. The planning budget is 18 W continuous and 24 W peak. A 512 Wh battery after derating gives about 18 hours at 18 W. The node needs about 432 Wh a day but an illustrative solar setup harvests only about 207 Wh a day, so we start on mains power, not solar.

**The drone path (conditional).** A DJI M350 RTK with a Sniffer4D Mini2 SO2 module. Added payload limit about 1 kg; 6.47 kg base plus 1 kg is 7.47 kg against a 9.2 kg maximum, but weight is not the only check (mount, balance, vibration). The advertised 55 minutes is without payload down to empty, not a loaded mission. Mini2's 5 ppb detection limit and T90 under 40 s are not field accuracy. Rotor wash can bias the inlet. Airspace permission and a trained operator are needed. If any of that fails, we stay on the ground.

**The van.** We don't buy one first. A portable kit with rented reference access comes first.

## 8. Money and business (Abhivansh)

| Stage | Cash | What it buys |
|---|---|---|
| A software | ₹0 | Our own laptops, free tools, our time |
| B bench box | up to ₹2,000 | Relative dust demo |
| C first test | ₹50,000 | Two-day SO2 reference test |
| C+ CO2 | +₹25,000 | Optional CO2 reference |
| D pilot | ₹1,60,000 | Five-day ground pilot, after C works |
| D+ CO2 | +₹60,000 | Optional |
| E own hardware, F drone | Quote needed | Only after earlier stages pass |

Base C + D is ₹2,10,000; with the bench box ₹2,12,000; with all CO2 options ₹2,97,000. These are unquoted allowances. The software would take 300 to 442 focused hours for the full version; at a made-up ₹500 per hour that labour is worth ₹2.1 to 3.2 lakh, but it isn't cash.

**Business, honestly.** Our first buyer guess is a measurement or lab partner, or an industrial estate association, testing a small paid pilot. A regulator buying it is a separate, slower path we can't assume. We have no customer yet. In one example, a fully managed service would cost ₹4,81,000 a month against ₹1,50,000 revenue and lose ₹3,31,000. A partner-software model breaks even at about four contracts under assumed prices. One two-person crew can handle about 20 complete events a month, so 100 events would need at least five crews. Without a grant, a three-month payment delay needs about ₹3.7 lakh of working capital.

## 9. What we say and never say

| Say | Never say |
|---|---|
| "Shows a sulphur-bearing clue" | "Detects coal" |
| "These registered sources still fit the model" | "This factory is guilty" |
| "A fit score" | "87 % probability" |
| "No alarm here; the plume may have missed the boxes" | "The area is clean" |
| "An official-style workflow demo" | "Legal evidence" |
| "A costed hardware plan, not yet tested" | "Our hardware works" |
| "Synthetic estate on the real Kundli map" | Any real factory name |

## 10. The 92 judge questions, short answers

Answers are short so you can say them. "Can't" answers are fine: judges respect a clear limit more than a bluff.

### A. Source and fuel (FP01 to FP10)

**FP01 Can the channels identify the source or fuel?** Sometimes a single source, depending on geometry and weather. A unique fuel, generally no. We report only what the signal and coverage support.

**FP02 Can allowed fuel plus a process mimic the signal?** Yes. A permitted fuel with sulphur from the process, a neighbour or dirty background can look the same. It is a reason to check records or measure again, not a violation.

**FP03 Can scrubbing, ash or blends hide the signature?** Yes. Two hypothetical cases with different sulphur gave the same ratio. A low ratio never certifies clean fuel.

**FP04 Is "unknown" useful?** Yes, if it says why (coverage, ambiguity, quality) and what to do next or why to stop. It prevents a wrong conclusion.

**FP05 When is the first physical product useful?** Ground-based screening where the rise is measurable, the wind and site are known, and access allows a measurement that can tell sources apart. Otherwise we only monitor.

**FP06 Missing or misplaced stack, unknown height?** Treated as uncertainty and an unknown-source option. We survey before any field trial.

**FP07 Bypass, roof vent, on-off process?** Modelled when known; otherwise "unregistered or mixed source" stays open. Pollution from earlier keeps drifting even after a source stops.

**FP08 Which records can be trusted for testing?** Records with who provided them, when, and uncertainty, held by an independent evaluator. A permit or fuel declaration is not proof of emissions.

**FP09 Are coordinates and heights consistent?** One system (east, north, up in metres from a fixed origin), one conversion for 3D, separate ground, roof, inlet and release heights. Real use needs surveyed positions.

**FP10 Privacy and access of real source data?** Real identifiers stay restricted. Demos use fictional S01 to S06 and never accuse a real operator.

### B. Wind, height and placement (FP11 to FP20)

**FP11 Wind carries the plume away from every box?** Then no alarm. We show blind sectors and allow scheduled surveys. A low reading never certifies compliance.

**FP12 Calm or turning wind?** Old pollution keeps its path; direction inference is marked unsupported in calm. We never just rotate a plume picture.

**FP13 Roof, street and stack winds differ?** Yes, so local wind uncertainty is shown separately, and attribution can be refused.

**FP14 Hot plume over every inlet?** It may stay undetectable. We test other heights and positions, use an elevated platform only if qualified, otherwise report no adequate measurement.

**FP15 Building wake, inversion or outside plume?** Outside our simple model. Shown as model uncertainty, unknown explanations kept.

**FP16 Can every mount be used?** Only after owner permission, structural and service checks. A roof in a map is not an approved mount.

**FP17 Does another box help?** Only if it adds coverage or tells similar candidates apart. A duplicate in the same blind spot adds cost, not coverage.

**FP18 Best spot is private or unsafe?** Excluded. We pick the best allowed spot or say no adequate layout.

**FP19 Cheaper layout with seasonal coverage?** Possibly, but only with a stated goal and local weather evidence.

**FP20 No affordable layout works?** We say so and stop spending. Software can explain the limit, not create observations.

### C. Gas instruments and sampling (FP21 to FP30)

**FP21 Can the SO2 and CO2 instruments see the rise above background?** Not proven yet. That is exactly what the ₹50,000 reference test checks.

**FP22 Interfering gas or saturation?** Raw signed values kept with a flag. A negative reading is evidence, not something to clip to zero.

**FP23 Event shorter than sensor response?** It may be missed or flattened, and that is a valid result. Longer stationary sampling helps.

**FP24 Drift, pressure, temperature?** Compensated only with documented methods, raw kept, uncertainty widened or measurement rejected.

**FP25 What cancels in background subtraction?** A stable offset may cancel; gain error, drift and background uncertainty don't. We calculate it, not assume it.

**FP26 Does tubing remove or delay SO2?** Yes, it can. Short PFA/PTFE lines, tested recovery; never assume zero loss.

**FP27 Can a pump keep flow with loaded filters?** Only an assembly test shows that. One pump per branch; reject data if flow leaves range.

**FP28 Can the flow meters resolve the tolerance?** Chosen for range at the real operating point, checked at real pressure and temperature.

**FP29 Leak, kink, blocked filter?** Pressure and flow checks flag it; affected data is rejected and kept on record; a new attempt needs new approval. This is our blocked-inlet demo.

**FP30 Transport time when flow changes?** Tracked by the actual volume pulled through the line, including stops and restarts.

### D. Particles and retained samples (FP31 to FP35)

**FP31 Does the inlet select PM2.5?** Only a qualified size selector at its exact flow does. Until then it's an "unclassified aerosol" sample.

**FP32 Losses in bends, static, humidity?** Tracked; the gas tube is never reused for particles. Unknown losses mean uncertainty or rejection.

**FP33 Enough mass in the time available?** Calculated: a 15-minute example gives 7.5 µg total but only 0.075 µg of a 1 % analyte. Often too little; we say so.

**FP34 Blanks, duplicates, limits?** Defined before sampling. Below the limit is not zero and not absence.

**FP35 Does a bag or filter preserve the sample?** Only if that exact method is qualified. Retained gas is switched off; online SO2 is the default.

### E. Electronics, power and communications (FP36 to FP50)

**FP36 Voltages and pins compatible?** A checked rail and harness table is mandatory: protected 24 V in, 5 V analog, 3.3 V logic, isolated instrument branches.

**FP37 Is ADC noise low enough?** Chosen for an input-referred noise budget, then tested with dummy inputs on the real board. Bit count alone isn't enough.

**FP38 Grounding, isolation, surge?** Separate motor and quiet analog returns, real isolated supplies, protection sized for the site.

**FP39 Rain, dust, insects, heat?** An enclosure rating doesn't protect an open inlet. Protected serviceable inlets; stop measuring outside limits.

**FP40 Can parts be serviced without contamination?** Labelled replaceable modules, accessible filters and calibration ports; after service, leak and flow checks before use.

**FP41 Startup and peak loads?** Separate columns for continuous (18 W) and peak (24 W); converters sized from measured inrush.

**FP42 Battery runtime in cold and age?** Derated: about 18 hours at 18 W from 512 Wh. No 72-hour claim.

**FP43 Foggy days and solar?** Even an example 200 W panel fails the foggy winter balance. Mains first.

**FP44 Battery recovery while working?** Only when the panel makes more than the node uses; no surplus means no recovery.

**FP45 Brownout or load shedding?** Save raw data first, stop new captures, restart warm-up and checks after reboot.

**FP46 Can the link carry the data, including backlog?** Sized from frame rates; local store and forward, so an outage doesn't erase data.

**FP47 Gaps, retries, duplicates, reboots?** Each frame keyed by device, boot and sequence with a hash; duplicates handled, gaps kept visible.

**FP48 Clock or position errors?** Carried as uncertainty; a measurement is excluded if the error defeats the purpose.

**FP49 Storage wear or sudden power loss?** Bounded journal plus transactional storage and backup; must be tested on the real design.

**FP50 Are the different times separated?** Yes: capture time, receive time, simulation time and wall time are stored separately. Replay never turns synthetic data into real data.

### F. Van and drone (FP51 to FP60)

**FP51 Is a portable rented kit enough before a van?** Yes, that's the recommended first step, if access is secured.

**FP52 Can the operator reach and stay at the spot?** Feasibility includes permission, safety and time there. A van can't drive through buildings or raise its inlet to stack height.

**FP53 Does the vehicle's own exhaust contaminate?** It can. Record engine state and wind; reject or repeat if unclear.

**FP54 Is all the time counted?** Yes: warm-up, setup, background, travel, purge, settle, collect, recover, return and review. The 15× speed-up in the demo moves all of them together.

**FP55 Preferred site unavailable?** Another action only if it still helps. Otherwise NO_USEFUL_MEASUREMENT; no trip just to look busy.

**FP56 Is the payload compatible with the drone?** Only a weighed setup and provider review can say. 6.47 + 1 kg against 9.2 kg is arithmetic, not approval.

**FP57 Enough battery in wind?** Budget the whole mission plus reserve; the example rejects the advertised 12-minute dwell after a safe return calculation.

**FP58 Rotor wash bias?** Unknown until compared with a reference. If not qualified, the aerial channel is unsupported.

**FP59 Airspace, permissions, operator, weather?** Must be checked for the real site and date. The demo drone is virtual; phones never fly real drones.

**FP60 Why a drone over ground methods?** Only if it gives a measured benefit ground methods can't, at acceptable cost. Otherwise ground first.

### G. Software, model and AI (FP61 to FP70)

**FP61 Units and conventions consistent?** Metres east-north-up, wind-from degrees, gas conversions with temperature and pressure; checked by tests.

**FP62 Is mass conserved, history kept?** Yes in the model: puffs keep their history, wind changes move existing mass, a source turning off never deletes it.

**FP63 Does a judge's action really change results?** Yes. Commands go to one server and change measurements, checks and evidence downstream.

**FP64 Can the hidden answer leak?** It must not. Truth stays in one sealed package; no screen, API or AI sees it. A test enforces this.

**FP65 3D or phone fails?** The investigation lives on the server. Keyboard and 2D controls work; the dead man stops orphan movement.

**FP66 Background drift and mixed sources?** Allowed for, not fitted away. Pairs and unknown sources are always options.

**FP67 Ties, weak signal, invalid samples?** Kept unresolved with explicit outcomes. A softmax is not a probability.

**FP68 Is the planner useful?** It picks the most separating feasible action per minute. Synthetic results show promise and real failures; no claim of field superiority.

**FP69 Are development and test data separate?** Yes, by scenario, source and day. Results we already looked at count as development only.

**FP70 Are misses and effort counted?** All episodes count: misses, invalid samples, wrong calls, unknowns, time and cancellations.

### H. Custody, evidence and law (FP71 to FP75)

**FP71 Is the inspector specimen separate from the screening sample?** Yes, its own ID, container, basis, custody and lab method.

**FP72 Is the record complete?** Raw records kept, every link and version stored, custody exceptions appended. A signature proves integrity, not that every real handover was recorded.

**FP73 Right authority, forms, lab?** Must be signed off for the real site and use. We only show the shape of the workflow.

**FP74 Does the verifier trust the key independently?** Yes: the trusted key comes separately, never from inside the evidence pack. Self-declared time is reported as unverified.

**FP75 Archive attacks or a stolen key?** Size and path limits, reject anything unexpected, revoke compromised keys, never backdate.

### I. Business (FP76 to FP80)

**FP76 Who buys first?** Our hypothesis: a measurement or lab partner, or an estate association, in a small paid pilot. Regulator purchase is separate.

**FP77 Partner instruments before owning a fleet?** Yes: software first, optional bench, then rented reference access.

**FP78 Do prices cover the real costs?** Not yet. A managed example loses money; partner software breaks even at about four assumed contracts.

**FP79 Can one crew be shared?** One two-person crew covers about 20 full events a month, not 100.

**FP80 Working capital without a grant?** About ₹3.7 lakh at a three-month payment delay, ₹5.3 lakh at six months with no receipts.

### J. Combined stress cases (FP81 to FP92)

**FP81 Wind reverses while the SO2 inlet is half blocked?** SO2 is quarantined, CO2 and earlier valid data kept; no candidate is guaranteed to survive.

**FP82 Weak plume plus background drift plus NO2 interference?** Treated as weak signed signals with uncertainty; the interferent can't be identified from a negative SO2 alone.

**FP83 Packet loss, clock skew and brownout together?** Capture and receive times kept apart, raw data buffered, gaps and reboots recorded.

**FP84 Wind shifts during a 15× dwell with too little filter mass?** The approval is cancelled, and the below-limit sample stays limited.

**FP85 Power cut makes many factories start diesel generators?** Outage and generator use are competing explanations, not proof of cause.

**FP86 Wet scrubber cools the plume to 45 to 55 °C?** It may still rise, but wet plume behaviour is outside our simple model; we narrow the claim.

**FP87 Inlet passes through an undiluted puff and saturates?** Clipped data is kept as a lower bound, not a value; recovery must be checked.

**FP88 A high-CO2 gas boiler mixes with a small SO2 leak next door?** Mixtures are allowed; a strong correlation can't prove a shared source or fuel.

**FP89 Someone blows smoke at the ₹2,000 bench box?** It's a relative dust sensor in its own data channel. It proves nothing about gas or fuel.

**FP90 Four devices send conflicting commands in the same second?** The server checks who they are, then applies commands one at a time by revision. Only one drone lease exists.

**FP91 Calibration gas degrades or sticks in a brass regulator?** The calibration path itself needs qualifying; a cylinder label is not proof.

**FP92 Small errors in wind, height, lag and interference combine to blame an innocent neighbour?** The model can fail like that, which is why we keep uncertainty, mixtures and unknowns, and need independent evidence before any action.

## 11. Abhivansh's schedule

| Day | Study | Practise |
|---|---|---|
| Fri 16 Oct | Parts 1 to 3 and the team plan | Read the pitch aloud three times |
| Sat 17 Oct | Part 4, questions A and B | Explain "why a box can miss" to a friend |
| Sun 18 Oct | Parts 5 and 6, questions G and H | Say the seven checks from memory |
| Mon 19 Oct | Part 7 hardware, questions C, D, E, F | Draw the node block diagram from memory |
| Tue 20 Oct | Part 8 money, questions I and J | Watch the join day; note what each screen shows |
| Wed 21 Oct | Everything again | Full 6-minute demo talk; teammates fire 10 random questions each |
| 25 to 29 Oct | Weak answers again, the finale additions | Two timed run-throughs a day; one-page hardware sheet for judges |

Rule for answering on stage: answer in one sentence, give one number if you have it, say the limit, then offer to show it on screen.
