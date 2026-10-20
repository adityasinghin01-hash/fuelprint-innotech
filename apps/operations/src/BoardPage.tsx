import { DailySummary } from './DailySummary';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { Button, DoneAutomatically, Explain, OutcomeBadge } from '@fp/shared';
import type { BoardDecision, CaseRecord, FindingRecord } from '@fp/shared';
import { Link } from 'react-router-dom';
import areas from '../../../contracts/reference/alert-areas.json';
import { CommandFeedback, useOperations, useOperationsCommand } from './OperationsContext';

const decisions: [BoardDecision, string][] = [
  ['MORE_INVESTIGATION', 'More investigation'], ['NO_SUPPORTED_SOURCE', 'No supported source'],
  ['CANDIDATE_SUPPORTED_SIMULATED', 'Candidate supported in the simulation'], ['SAMPLE_INVALID', 'Sample invalid'],
];
export function BoardPage() {
  const { snapshot } = useOperations();
  const [selected, setSelected] = useState('');
  const cases = snapshot.records('case');
  const caseRecord = cases.find(c => c.id === selected) ?? cases.at(-1);
  const finding = snapshot.records('finding').filter(f => f.case_id === caseRecord?.id).at(-1);
  if (snapshot.status !== 'ready') return null;
  return <section><p className="ops-eyebrow">OP05 · BOARD REVIEW</p><h1>Keep the conclusion in scope.</h1>
    <p>Supported, rejected and inconclusive findings are all valid results of an investigation.</p>
    <DailySummary />
    {cases.length ? <><label className="ops-form">Case<select value={caseRecord?.id ?? ''} onChange={e => setSelected(e.target.value)} aria-describedby="case-help">
      {cases.map(c => <option value={c.id} key={c.id}>{c.purpose} · {c.state}</option>)}</select></label>
      <Explain id="case-help" what="Choose the case whose finding the board will review." /></> : <p>No cases have been saved for this run.</p>}
    {caseRecord ? <><section className="ops-panel"><h2>{caseRecord.purpose}</h2>
      <p><Link to={`/investigations/${caseRecord.investigation_id}`}>Read the investigation</Link><Explain what="The investigation whose measurements support this case." /></p>
      {finding ? <><OutcomeBadge outcome={finding.outcome} /><h3>Finding limitations</h3><ul>{finding.limitations.map((line, i) => <li key={i}>{line}</li>)}</ul>
        <h3>Alternatives</h3><ul>{finding.alternatives.map((line, i) => <li key={i}>{line}</li>)}</ul>
        <p>Legal suitability: not assessed<Explain what="This synthetic teaching finding is not an assessment of legal suitability." /></p></> : <p>A finding must be recorded before the board can decide.</p>}
      </section><BoardForm key={`${caseRecord.id}-${caseRecord.revision}`} caseRecord={caseRecord} finding={finding} /></> : null}
  </section>;
}
function BoardForm({ caseRecord, finding }: { caseRecord: CaseRecord; finding?: FindingRecord }) {
  const { snapshot } = useOperations();
  const action = useOperationsCommand('board');
  const investigation = snapshot.record('investigation', caseRecord.investigation_id);
  const [selectedAreas, setAreas] = useState<string[]>(investigation?.warned_area_ids ?? []);
  const [decision, setDecision] = useState<BoardDecision>('MORE_INVESTIGATION');
  const [rationale, setRationale] = useState('');
  const [interval, setInterval] = useState('');
  const [text, setText] = useState('');
  const forbidden = /S0[1-6]/i.test(text + interval);
  const areaText = areas.areas.filter(a => selectedAreas.includes(a.id)).map(a => a.name).join(', ') || 'No areas selected';
  const saved = snapshot.records('board_decision').filter(b => b.case_id === caseRecord.id);
  const notices = snapshot.records('notification').filter(n => n.kind === 'BOARD_UPDATE' && saved.some(b => b.id === n.cause?.id));
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!finding || forbidden) return;
    void action.send('record_board_decision', { case_id: caseRecord.id, decision, rationale,
      notice: { area_ids: selectedAreas, area: areaText, interval, status_text: text } }, caseRecord.revision);
  }
  return <><form className="ops-panel ops-form" onSubmit={submit} aria-label="Board decision">
    <h2>Record the board decision</h2><label>Decision<select value={decision} onChange={e => setDecision(e.target.value as BoardDecision)} aria-describedby="decision-help">
      {decisions.map(([id, label]) => <option value={id} key={id}>{label}</option>)}</select></label>
    <Explain id="decision-help" what="The board's human decision. It does not change the finding or any model output." />
    <label>Rationale<textarea required value={rationale} onChange={e => setRationale(e.target.value)} aria-describedby="rationale-help" /></label>
    <Explain id="rationale-help" what="Why the board chose this decision, including the limits it relied on." />
    <fieldset><legend>Notice areas</legend>{areas.areas.map(area => <label key={area.id}><input type="checkbox" checked={selectedAreas.includes(area.id)}
      onChange={e => setAreas(current => e.target.checked ? [...current, area.id] : current.filter(id => id !== area.id))} />{area.name}</label>)}</fieldset>
    <Explain what="Warned areas are selected initially. The notice goes only to people signed up for the chosen areas." />
    <label>Time interval<input required value={interval} onChange={e => setInterval(e.target.value)} aria-describedby="interval-help" /></label>
    <Explain id="interval-help" what="The period covered by this area notice, written by the board." />
    <label>Area notice<textarea required value={text} onChange={e => setText(e.target.value)} aria-describedby="notice-help" /></label>
    <Explain id="notice-help" what="Public status text. Do not name a factory or source S01 to S06." />
    {forbidden ? <p role="alert">A public notice must not name a source S01 to S06.</p> : null}
    <Button type="submit" pending={action.pending} disabled={action.disabled || !finding || forbidden || !rationale.trim() || !interval.trim() || !text.trim()}>Save board decision</Button>
    <CommandFeedback action={action} />
  </form><section className="ops-panel"><h2>Phone notice preview</h2><div className="ops-phone"><strong>FuelPrint DEMO (simulated)</strong><p>{areaText}</p><p>{interval || 'Interval not entered'}</p><p>{text || 'Write an area notice to preview it.'}</p></div>
    <p>SMS stays LOG_ONLY in this demo; no message is sent to a phone.</p><Explain what="Draft area notice, not a delivery confirmation. The final checked SMS text and delivery result appear below after the server saves them." /></section>
    <h2>Saved decisions and SMS delivery</h2>{saved.length === 0 ? <p>No board decision saved for this case yet.</p> : null}
    {saved.map(b => <section key={b.id} className="ops-panel"><p>{b.decision} · {b.rationale}</p><p>{b.created_utc} by {b.created_by}</p><Explain what="An immutable board-decision record and who signed it." /></section>)}
    {notices.length === 0 ? <p>No board-update notification has been returned yet.</p> : null}
    {notices.map(n => <section key={n.id} className="ops-panel"><h3>{n.state}</h3><p>{n.text}</p><p>{n.area_ids.join(', ')} · sent {n.sent}, failed {n.failed}, recipients {n.recipients}</p>
      <Explain what="Actual notification text and delivery counts from the server; suppression is reported with a reason." />
      <p>{n.suppressed_reason ?? n.gateway_mode}</p>{n.created_by === 'automation' ? <DoneAutomatically at={n.created_utc} /> : null}</section>)}
  </>;
}
