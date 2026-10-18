// SKELETON ONLY (Owners: Antara (backend) and Aditya (screens CA01 to CA06).) Replace the placeholder page with the real screens.
import {
  AutomationLog, Explain, LiveBadge, LiveProvider, StaleBanner, StationLogin, useLive, useSession, useSnapshot,
  valueOrUnknown,
} from '@fp/shared';
import type { LiveConnection } from '@fp/shared';
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';

export function App({ connection }: { connection?: LiveConnection }) {
  return (
    <LiveProvider connection={connection}>
      <BrowserRouter basename="/cases">
        <Frame />
      </BrowserRouter>
    </LiveProvider>
  );
}

function Frame() {
  const live = useLive();
  const snap = useSnapshot();
  const session = useSession();
  const run = snap.snapshot?.run ?? null;
  const tick = live.tick;
  return (
    <>
      <header style={{ display: 'flex', gap: '1.5rem', alignItems: 'center', padding: '0.5rem 1rem', flexWrap: 'wrap' }}>
        <strong>FuelPrint Cases</strong>
        <LiveBadge status={live.status} />
        <span data-testid="run-state">
          Run: {run ? `${run.preset_id} (${run.state})` : 'none yet'}
          <Explain what="The scenario the server is playing and its state." />
        </span>
        <span data-testid="sim-time">
          Time: {valueOrUnknown(tick?.sim_time_s, 's', 1)}
          <Explain what="Seconds since the run started, from the server's clock." />
        </span>
        <span>
          Wind: {tick?.payload.wind ? `from ${tick.payload.wind.from_deg}° at ${tick.payload.wind.speed_m_s} m/s` : 'unknown'}
          <Explain what="Where the wind comes from, clockwise from north." />
        </span>
        <span>
          {session.session?.signed_in ? `Signed in: ${session.session.station_id} (${session.session.roles.join(', ')})` : 'Not signed in'}
        </span>
      </header>
      <StaleBanner status={live.status} />
      <main style={{ padding: '1rem' }}>
        <Routes>
          <Route path="/" element={<Placeholder />} />
          <Route path="*" element={<p>This page does not exist yet. <Link to="/">Back</Link></p>} />
        </Routes>
        {session.status === 'ready' && !session.session?.signed_in ? (
          <section aria-label="Sign in">
            <h2>Sign in this laptop</h2>
            <StationLogin login={session.login} />
          </section>
        ) : null}
        <AutomationLog runId={snap.snapshot?.run_id} max={5} />
      </main>
    </>
  );
}

/** Placeholder: proves the live connection works (the drone moves in the ticks). */
function Placeholder() {
  const live = useLive();
  const drone = live.tick?.payload.vehicles[0];
  return (
    <section>
      <h1>Cases</h1>
      <p>Where evidence is kept honestly: intake, specimens, custody, lab, finding and the evidence vault.</p>
      <p data-testid="drone">
        Drone: {drone ? `${drone.label} at height ${valueOrUnknown(drone.pose.up, 'm', 1)}, ${drone.stage ?? 'no mission'}, pilot ${drone.pilot}` : 'no vehicle yet'}
        <Explain what="Where the drone is, from the server's 10-per-second tick." why="It proves this screen sees live data." />
      </p>
      <p data-testid="cursor">Last event: {live.cursor ?? 'none yet'}</p>
    </section>
  );
}
