# FuelPrint continuous story

Implemented Phase 1.1 + Phase 2, approved 21 October 2026. Nine scenes share one persistent canvas
and landscape; twelve reading beats provide chapter access without splitting the world.

| Scene | Narrative and visual | Scroll interval |
| --- | --- | --- |
| Living estate | Aerial green estate, soft clouds, illustrative smoke, oversized FUELPRINT | 0–0.18 |
| Rooftop monitoring | Rooftop instruments and subtle network paths; labelled conceptual trace without measured values | 0.18–0.355 |
| Signal to HQ | Brass pulse follows a curve to HQ; investigation context and proposed next measurement; explanations remain uncertain | 0.355–0.425 |
| Human approval | Supervisor reviews the proposal before activation; no actual command | 0.425–0.48 |
| Activation | Gradual rotor acceleration, vertical lift and stable hold above the pad | 0.48–0.55 |
| Outbound flight | Smooth Bézier flight with banking; companion camera follows an illustrative measurement route | 0.55–0.69 |
| Air screening | Hover, purge, settle, collect; human channel review explains possible statuses without inventing results | 0.69–0.837 |
| Return | Shorter return arc, gentle turn, vertical landing on the same pad; rotor slowdown follows touchdown | 0.837–0.955 |
| Evidence | Camera eases back to HQ and the landed drone; Follow the evidence appears over the same world; native scroll continues into actual Cases | 0.955–1 |

Desktop hold: 1400svh; phone: 1350svh. Existing native scroll and GSAP ScrollTrigger scrub (0.6 s)
drive the same pure state. The original estate approach remains; portrait HQ framing is widened
before closing on the drone. Outbound travel lasts 0.13 progress; return travel lasts 0.068, followed
by controlled descent. Camera easing includes deliberate readable holds.
Final polish keeps those timings and positions: aircraft heading follows its flight tangent; desktop
look targets track the drone during outbound/return so its rotor guards stay in frame. The final
camera keeps HQ visible instead of a full-screen ivory veil.

Camera, drone position/heading/bank, signal, communication, approval, power, procedure stages, review,
landing and reveal are functions of scroll. Backward scrolling restores those states. Clouds, smoke,
rotor phase and decorative inlet particles use separate pausable time. No Field route is planned.

Reduced motion, short windows and unavailable WebGL show the estate poster and complete story in
document flow. Keyboard chapter controls and direct Cases entry remain available. The real Workspace
uses existing providers; no fake record, approval or scientific outcome is created. Air-screening
records and inspector specimens keep separate identities.
