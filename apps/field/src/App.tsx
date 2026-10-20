import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { AutomationLog, Button, Explain, LiveBadge, LiveProvider, StaleBanner, StationLogin, useCommand, useLive, useSession, useSnapshot } from '@fp/shared';
import type { LiveConnection, TickMessage } from '@fp/shared';
import { CaptureReview } from './components/CaptureReview';
import { ReadingPanel } from './components/ReadingPanel';
import { selectFrame } from './liveFrame';
import './field.css';
const FieldScene = lazy(() => import('./scene/FieldScene'));

export function App({ connection, sceneEnabled = true }: { connection?: LiveConnection; sceneEnabled?: boolean }) {
  return <LiveProvider connection={connection}><FieldView sceneEnabled={sceneEnabled}/></LiveProvider>;
}
function FieldView({ sceneEnabled }: { sceneEnabled: boolean }) {
  const live = useLive(); const snap = useSnapshot(); const session = useSession(); const command = useCommand();
  const [error, setError] = useState<string | null>(null);
  const last = useRef<TickMessage | null>(null);
  const boundary = useRef({ connects: 0, oldTick: null as TickMessage | null });
  if (boundary.current.connects !== live.connects) {
    boundary.current = { connects: live.connects, oldTick: boundary.current.connects > 0 ? live.tick : null };
  }
  const run = snap.snapshot?.run ?? null;
  const waiting = boundary.current.oldTick !== null && boundary.current.oldTick === live.tick;
  const candidate = live.status === 'live' && !waiting
    ? selectFrame(live.tick, snap.snapshot?.run_id ?? null, live.serverEpoch, run?.state ?? null, last.current) : null;
  const frozen = live.status !== 'live' && last.current?.run_id === run?.id && last.current?.server_epoch === live.serverEpoch ? last.current : null;
  const frame = candidate ?? frozen;
  useEffect(() => { if (candidate) last.current = candidate; }, [candidate]);
  const disabled = live.status !== 'live' || !candidate || snap.status !== 'ready' || command.pending;
  const drone = frame?.payload.vehicles.find(v => v.kind === 'DRONE');
  const missions = snap.records('mission').filter(m => m.run_id === run?.id && !['COMPLETE','INTERRUPTED','EXPIRED','REJECTED'].includes(m.state));
  const active = missions[missions.length - 1];
  async function send(type: string, payload: object, revision: number | null) {
    setError(null); const r = await command.send(type, payload, revision, { runId: run?.id });
    if (r.status !== 'APPLIED') setError(r.reason_text ?? r.reason_code ?? r.status);
    snap.reload();
  }
  return <main className="field-shell">
    <header><div><p className="eyebrow">FUELPRINT / SYNTHETIC TEACHING</p><h1>Field observatory</h1></div>
      <nav aria-label="Apps"><a href="/operations/">Operations</a><a href="/cases/">Cases</a></nav><LiveBadge status={live.status}/></header>
    <StaleBanner status={live.status}/>
    <p>Drone motion and sensor observations come from the Java server. Trained ranker unavailable; SMS remains LOG_ONLY.</p>
    {snap.status === 'loading' ? <p>Loading current run…</p> : null}{snap.error ? <p role="alert">{snap.error}</p> : null}
    <section className="field-controls" aria-label="Run controls">
      <p>{run ? `${run.preset_id} · ${run.state}` : 'No run yet'}</p>
      {!run && session.can('presenter') ? <Button disabled={live.status !== 'live' || command.pending} onClick={() => void send('reset_run', {preset_id:'SC1-NIGHT-PLUME',mode:'TEACHING'},null)}>Start synthetic run</Button> : null}
      <Button disabled={disabled || !session.can('presenter') || run?.state !== 'RUNNING'} onClick={() => void send('pause_run',{run_id:run?.id},run?.revision ?? null)}>Pause run</Button>
      <Button disabled={disabled || !session.can('presenter') || run?.state !== 'PAUSED'} onClick={() => void send('resume_run',{run_id:run?.id},run?.revision ?? null)}>Resume run</Button>
      <Explain what="Presenter controls the server clock. Controls require fresh matching run data and your station's role."/>
      {command.pending ? <p role="status">Command pending…</p> : null}{error ? <p role="alert">{error}</p> : null}
    </section>
    <section className="field-stage" aria-label="Connected estate">
      {sceneEnabled ? <Suspense fallback={<p>Loading estate renderer; textual observations remain available.</p>}><FieldScene frame={frame}/></Suspense> : null}
      <div className="frame-details"><h2>Server frame</h2>
        <p data-testid="frame-identity">{frame ? `Run ${frame.run_id} · tick time ${frame.sim_time_s} s · ${frame.server_time_utc} · epoch ${frame.server_epoch}` : 'Waiting for a matching fresh server frame.'}</p>
        {live.status !== 'live' && frame ? <p>Last observed frame — frozen while connection is {live.status}.</p> : null}
        {drone ? <>
          <p data-testid="vehicle-pose">{drone.label} pose ENU (m): {drone.pose.east}, {drone.pose.north}, {drone.pose.up}</p>
          <p data-testid="vehicle-inlet">Inlet ENU (m): {drone.inlet.east}, {drone.inlet.north}, {drone.inlet.up}</p>
          <p>Stage {drone.stage ?? 'unknown (no mission)'} · pilot {drone.pilot} · energy {drone.energy_wh ?? 'unknown'} Wh</p>
        </> : <p>No vehicle in this server frame.</p>}
        <p>Wind: {frame?.payload.wind ? `from ${frame.payload.wind.from_deg}° at ${frame.payload.wind.speed_m_s} m/s` : 'unknown (no wind supplied)'}</p>
        <Explain what="Pose is the vehicle body; inlet is where the instrument samples. These are observed coordinates, with no predicted flight added."/>
      </div>
    </section>
    <section aria-label="Approved flight"><h2>Approved mission</h2>
      {active ? <><p>{active.id} · {active.state} · {active.pilot}</p>
        <Button disabled={disabled || !session.can('field_operator') && !session.can('supervisor') || active.state !== 'APPROVED'}
          onClick={() => void send('start_autopilot',{mission_id:active.id,vehicle_id:active.vehicle_id},active.revision)}>Start approved autopilot</Button>
        <Button disabled={disabled || !session.can('field_operator') && !session.can('supervisor') || active.pilot !== 'AUTOPILOT'}
          onClick={() => void send('stop_autopilot',{vehicle_id:active.vehicle_id},null)}>Stop autopilot</Button></> : <p>No active mission. An analyst schedules a survey in Operations; a supervisor must approve it.</p>}
      <Explain what="Autopilot follows the server-approved route. A scene cannot approve or choose a mission."/>
    </section>
    <section aria-label="Human capture review"><h2>Capture QC review</h2>
      {!session.can('qc_reviewer')?<p>Sign in as qc_reviewer to record a human review.</p>:null}
      {snap.records('attempt').filter(a=>!snap.records('screening_record').some(s=>s.attempt_id===a.id)).map(a=><CaptureReview key={a.id} attempt={a} disabled={disabled || !session.can('qc_reviewer')} reload={snap.reload}/>)}
      {snap.records('screening_record').map(s=><p key={s.id}>{s.channel} · {s.decision} · {s.reasons.join('; ')} · saved human review</p>)}
    </section>
    <ReadingPanel frame={frame} readings={snap.snapshot?.latest_readings ?? []} sensors={snap.records('sensor')}/>
    {session.status === 'ready' && !session.session?.signed_in ? <section><h2>Sign in this laptop</h2><StationLogin login={session.login}/></section> : null}
    <AutomationLog runId={run?.id} max={8}/>
  </main>;
}
