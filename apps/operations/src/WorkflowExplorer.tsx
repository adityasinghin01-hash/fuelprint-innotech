import { Button, DoneAutomatically, Explain } from '@fp/shared';
import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { useOperations } from './OperationsContext';
import './workflow.css';

const stages = [
  { title: 'Observe', icon: '◎', role: 'Automatic monitoring', text: 'Saved sensor windows can open an investigation when a trigger fires. A quiet sensor does not prove that the air is safe.', link: '/investigations' },
  { title: 'Analyse', icon: '⌁', role: 'Automation / analyst', text: 'Physics checks which explanations survive. The learned model only orders survivors; it cannot change an outcome or remove UNKNOWN.', link: '/investigations' },
  { title: 'Plan', icon: '◇', role: 'Automation / analyst', text: 'The planner recommends a useful measurement when one is available. A recommendation is not permission to fly.', link: '/missions' },
  { title: 'Approve', icon: '✓', role: 'Human supervisor', text: 'A supervisor reviews the measurement and approves or rejects it. Automation cannot give this approval. Expired or changed plans need review again.', link: '/missions' },
  { title: 'Measure', icon: '✥', role: 'Field operator / visitor / autopilot', text: 'An approved mission collects a measurement. Completing a route does not make every captured channel usable.', link: '/missions' },
  { title: 'Review', icon: '⊞', role: 'Human QC reviewer', text: 'A person reviews the capture for its named use. Each channel can be valid, limited or rejected; raw evidence alone does not establish a finding.', link: '/missions' },
  { title: 'Finding', icon: '▤', role: 'Human case reviewer', text: 'A reviewer records a finding with cited evidence, alternatives and limitations. Support applies only within the stated scope; legal suitability is not assessed.', link: '/board' },
  { title: 'Board', icon: '⚑', role: 'Human board', text: 'The board reviews the saved finding and records its decision. Notices describe areas and intervals, without naming a real factory.', link: '/board' },
] as const;

export function WorkflowExplorer() {
  const { snapshot, live } = useOperations();
  const [selected, setSelected] = useState(0);
  const [angle, setAngle] = useState(-22);
  const [flat, setFlat] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    const preference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (!preference) return;
    const update = () => { setReduced(preference.matches); if (preference.matches) setPlaying(false); };
    update(); preference.addEventListener('change', update);
    return () => preference.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    if (!playing || reduced) return;
    const timer = window.setTimeout(() => {
      if (selected === stages.length - 1) setPlaying(false);
      else setSelected(selected + 1);
    }, 4500);
    return () => window.clearTimeout(timer);
  }, [playing, reduced, selected]);
  const runId = snapshot.snapshot?.run_id;
  const investigation = snapshot.records('investigation').filter(r => runId && r.run_id === runId).at(-1);
  const analysis = snapshot.records('analysis').filter(r => r.run_id === runId && r.id === investigation?.analysis_ids.at(-1)).at(-1);
  const proposal = snapshot.records('proposal').filter(r => r.run_id === runId && analysis && r.analysis_id === analysis.id).at(-1);
  const mission = snapshot.records('mission').filter(r => r.run_id === runId &&
    (proposal && r.proposal_id === proposal.id || investigation?.survey_id && r.survey_id === investigation.survey_id)).at(-1);
  const attempt = snapshot.records('attempt').filter(r => r.run_id === runId && mission && r.mission_id === mission.id).at(-1);
  const screening = snapshot.records('screening_record').filter(r => r.run_id === runId && attempt && r.attempt_id === attempt.id).at(-1);
  const caseRecord = snapshot.records('case').filter(r => r.run_id === runId && investigation && r.investigation_id === investigation.id).at(-1);
  const finding = snapshot.records('finding').filter(r => r.run_id === runId && caseRecord && r.case_id === caseRecord.id).at(-1);
  const board = snapshot.records('board_decision').filter(r => r.run_id === runId && caseRecord && r.case_id === caseRecord.id).at(-1);
  const records = [investigation, analysis, proposal, mission, attempt, screening, finding, board];
  const values = [investigation?.state, analysis?.outcome, proposal?.result, mission?.state, attempt?.state,
    screening?.decision, finding?.outcome, board?.decision];
  const record = records[selected];
  const stage = stages[selected];
  const choose = (index: number) => { setSelected(index); setPlaying(false); };
  return <section className="wf" aria-label="Interactive workflow explorer">
    <div className="wf-heading"><div><p className="wf-kicker">THE INVESTIGATION, EXPLAINED</p>
      <h2>From a signal<br />to a considered decision.</h2>
      <p>Explore the steps. See where people take responsibility.</p></div>
      <span className="wf-tag">SYNTHETIC TEACHING<Explain what="This explains a fictional demonstration, not a real pollution investigation." /></span></div>
    <div className="wf-layout"><div className="wf-visual">
      <div className={`wf-scene ${flat ? 'wf-flat' : ''}`} aria-hidden="true">
        <div className="wf-plane" style={{ '--wf-angle': `${angle}deg` } as CSSProperties}>
          <svg viewBox="0 0 600 300" preserveAspectRatio="none"><path d="M75 80 H525 V220 H75" /></svg>
          {stages.map((item, index) => <div key={item.title} className={`wf-node ${selected === index ? 'wf-active' : ''} ${item.role.startsWith('Human') ? 'wf-human' : ''}`}
            style={{ left: `${12.5 + (index < 4 ? index : 7 - index) * 25}%`, top: index < 4 ? '27%' : '73%' }}>
            <div className="wf-cube"><span>{item.icon}</span></div><b>{item.title}</b>
          </div>)}
        </div>
      </div>
      <p className="wf-caption">Conceptual process · not a map or a dispersion model.</p>
      <div className="wf-camera"><label>Rotate diagram <input aria-label="Rotate diagram" type="range" min="-40" max="20" value={angle} disabled={flat}
        onChange={event => setAngle(Number(event.target.value))} /></label>
        <Button variant="secondary" onClick={() => setFlat(!flat)}>{flat ? '3D view' : 'Flat view'}</Button></div>
      <Explain what="Rotation changes only the drawing. Flat view removes perspective. Mint marks the selected explanation; amber marks a human decision." />
    </div>
    <div className="wf-detail" aria-live="polite" aria-atomic="true"><p className="wf-kicker">STEP {selected + 1} / {stages.length}</p>
      <h3>{stage.title}</h3><p className="wf-role">{stage.role}</p><p>{stage.text}</p>
      <div className="wf-saved"><span>SAVED SERVER STATE</span><strong>{snapshot.status !== 'ready' ? 'Unknown — snapshot unavailable' : values[selected] ?? (record ? 'Unknown: saved record has no state' : 'Not saved for this investigation')}</strong>
        {live.status !== 'live' ? <p>Connection {live.status}: last saved information, read-only.</p> : null}
        {record?.created_by === 'automation' ? <DoneAutomatically at={record.created_utc} /> : null}
        <Explain what="The linked record for this investigation, not a completion indicator. Missing records do not prove that a step succeeded or failed." /></div>
      <Link to={stage.link}>Open {stage.link === '/board' ? 'board' : stage.link === '/missions' ? 'missions' : 'investigation'} workspace →</Link>
    </div></div>
    <div className="wf-steps" aria-label="Choose an explanation">{stages.map((item, index) =>
      <button key={item.title} type="button" aria-pressed={selected === index} onClick={() => choose(index)}><span aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>{item.title}</button>)}</div>
    <div className="wf-footer"><div><Button variant="secondary" disabled={selected === 0} onClick={() => choose(selected - 1)}>Previous</Button>{' '}
      <Button variant="secondary" disabled={selected === stages.length - 1} onClick={() => choose(selected + 1)}>Next step</Button>{' '}
      <Button disabled={reduced} onClick={() => { if (!playing && selected === 7) setSelected(0); setPlaying(!playing); }}>{playing ? 'Pause walkthrough' : 'Play walkthrough'}</Button></div>
      <p>Explore freely. No commands are sent.{reduced ? ' Automatic playback is off for reduced motion.' : ''}</p></div>
    <Explain what="Choose a step or use Previous and Next. Numbers show explanation order, not progress. Play advances explanations; Pause stops it. Workspace links open the saved records and role-controlled commands." />
  </section>;
}
