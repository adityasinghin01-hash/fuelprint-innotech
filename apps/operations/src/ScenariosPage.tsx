import { useState } from 'react';
import { Button, Explain, valueOrUnknown } from '@fp/shared';
import { Link } from 'react-router-dom';
import { CommandFeedback, useOperations, useOperationsCommand } from './OperationsContext';

const presets = [
  ['SC1-NIGHT-PLUME', 'Night plume', 'A sustained rise starts an investigation; a drone measurement helps distinguish explanations.'],
  ['SC2-BLOCKED-INLET', 'Blocked inlet', 'A failed gas-flow path makes that channel unusable, even when its raw reading looks normal.'],
  ['SC3-ABOVE-THE-BOXES', 'Above the boxes', 'A high plume misses the fixed sensors. No alarm does not mean clean air.'],
];
export function ScenariosPage() {
  const { snapshot, live } = useOperations();
  const action = useOperationsCommand('presenter', true);
  const [preset, setPreset] = useState(presets[0][0]);
  const [confirmReset, setConfirmReset] = useState(false);
  const run = snapshot.snapshot?.run;
  const active = run?.state === 'RUNNING' || run?.state === 'PAUSED';
  return <section><p className="ops-eyebrow">OP06 · SCENARIOS</p><h1>Run a teaching scenario.</h1>
    <div className="ops-grid">{presets.map(([id, title, description]) => <section className="ops-panel" key={id}><h2>{title}</h2><p>{description}</p><p>{id}</p><Explain what="A reproducible synthetic scenario defined by the project agreement." /></section>)}</div>
    <label className="ops-form">Scenario<select value={preset} onChange={e => { setPreset(e.target.value); setConfirmReset(false); }} aria-describedby="scenario-help">
      {presets.map(([id, title]) => <option value={id} key={id}>{title}</option>)}</select></label>
    <Explain id="scenario-help" what="Choose which synthetic situation the server should create." />
    <p>Mode: {run?.mode ?? 'BLIND for new runs'}<Explain what="Blind mode reveals no hidden truth and offers no truth controls." /></p>
    <p>Time scale: {valueOrUnknown(live.tick?.payload.time_scale, '×')}<Explain what="The speed of the simulation clock. The server permits faster captures only while vehicles are stationary." /></p>
    <p>Captures can run 15× faster while the vehicle stands still. This is controlled by the server, not this screen.</p>
    <div className="ops-actions"><Button pending={action.pending} disabled={action.disabled || active}
      onClick={() => void action.send('create_run', { preset_id: preset, mode: 'BLIND' })}>Start run</Button>
      <Button variant="secondary" pending={action.pending} disabled={action.disabled || run?.state !== 'RUNNING'}
        onClick={() => run && void action.send('pause_run', { run_id: run.id }, run.revision)}>Pause run</Button>
      <Button variant="secondary" pending={action.pending} disabled={action.disabled || run?.state !== 'PAUSED'}
        onClick={() => run && void action.send('resume_run', { run_id: run.id }, run.revision)}>Resume run</Button>
      <Button variant="secondary" disabled={action.disabled || !run} onClick={() => setConfirmReset(true)}>Reset to a new run</Button></div>
    <Explain what="Start creates a new run; pause and resume change the current run through presenter commands." />
    <p>Reset makes a new run and preserves old cases, model versions and SMS sign-ups.</p>
    {confirmReset ? <section className="ops-panel" aria-label="Confirm reset"><p>Reset the selected scenario to a new BLIND run? Existing evidence remains saved.</p>
      <Button pending={action.pending} disabled={action.disabled} onClick={() => {
        void action.send('reset_run', { preset_id: preset, mode: 'BLIND' }); setConfirmReset(false);
      }}>Confirm reset</Button> <Button variant="secondary" onClick={() => setConfirmReset(false)}>Cancel</Button><Explain what="Confirmation avoids accidentally interrupting a demonstration." /></section> : null}
    <CommandFeedback action={action} /><p><Link to="/ai">AI and automation switches</Link></p>
  </section>;
}
