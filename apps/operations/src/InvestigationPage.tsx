import { Button, DoneAutomatically, Explain, OutcomeBadge, valueOrUnknown } from '@fp/shared';
import type { AnalysisRecord } from '@fp/shared';
import { Link, useParams } from 'react-router-dom';
import { CommandFeedback, useOperations, useOperationsCommand } from './OperationsContext';

export function InvestigationPage() {
  const { snapshot } = useOperations();
  const action = useOperationsCommand('analyst');
  const { id } = useParams();
  const investigations = snapshot.records('investigation');
  const investigation = id ? snapshot.record('investigation', id) : investigations.at(-1);
  const trigger = snapshot.record('trigger', investigation?.trigger_id);
  const sensor = snapshot.record('sensor', trigger?.sensor);
  if (snapshot.status !== 'ready') return null;
  return <section><p className="ops-eyebrow">OP03 · INVESTIGATION</p><h1>Which explanations still fit?</h1>
    <p>No alarm does not mean the air is clean. A plume can pass above or beside every box.</p>
    <nav aria-label="Investigation history">{investigations.map(record => <p key={record.id}>
      <Link to={`/investigations/${record.id}`}>{record.id}</Link> · {record.state}
      <Explain what="An investigation and its current saved state." /></p>)}</nav>
    {!investigation ? <p>{id ? 'This investigation was not found in the current run.' : 'No investigation has been saved for this run yet.'}</p> : <>
      <section className="ops-panel"><h2>{investigation.state}</h2>
        <Explain what="The stage reached by the investigation, as saved by the server." />
        <p>{trigger ? `Trigger on ${sensor?.label ?? trigger.sensor}, channel ${trigger.channel}` : investigation.survey_id ? 'Scheduled survey' : 'Investigation basis unknown'}
          <Explain what="What started the investigation. Surveys can start without an alarm." /></p>
        {trigger ? <ul>{trigger.intervals.map(([start, end], index) => <li key={index}>{start} to {end} simulation seconds
          <Explain what="Intervals in which the fixed alarm rule observed a rise." /></li>)}</ul> : null}
        {investigation.created_by === 'automation' ? <p>Opened automatically · <DoneAutomatically at={investigation.created_utc} /></p> : <p>Opened by {investigation.created_by}</p>}
        <p>Who was warned: {investigation.warned_area_ids.join(', ') || 'No areas'}<Explain what="Areas selected by the server for a smoke-warning notice. Surveys warn no area." /></p>
        {snapshot.records('notification').filter(n => n.kind === 'SMOKE_WARNING' && n.cause?.id === investigation.id).map(n =>
          <p key={n.id}>{n.state} · sent {n.sent}, failed {n.failed}; {n.suppressed_reason ?? n.gateway_mode}<Explain what="Actual SMS delivery counts and any suppression reason, from the alerts service." /></p>)}
        <Button pending={action.pending} disabled={action.disabled || investigation.state === 'CLOSED'}
          onClick={() => void action.send('run_analysis', { investigation_id: investigation.id }, investigation.revision)}>Run analysis again</Button>
        <Explain what="Ask the physics ranker, learned ranker and planner to examine saved windows again." />
        <CommandFeedback action={action} />
      </section>
      <p><Link to="/analysis">Open rules-based physics order</Link></p>
      <h2>Analysis history</h2><Explain what="Every saved analysis remains visible so you can compare before and after a measurement." />
      {investigation.analysis_ids.length === 0 ? <p>No analysis has been saved yet.</p> : null}
      {[...investigation.analysis_ids].reverse().map(analysisId => {
        const analysis = snapshot.record('analysis', analysisId);
        return analysis ? <AnalysisCard key={analysisId} analysis={analysis} /> : <p key={analysisId}>Analysis {analysisId} is not available in this snapshot.</p>;
      })}
    </>}
  </section>;
}

export function AnalysisCard({ analysis }: { analysis: AnalysisRecord }) {
  const { snapshot } = useOperations();
  const wording = snapshot.records('wording').find(w => w.purpose === 'EXPLANATION' && w.target.type === 'analysis'
    && w.target.id === analysis.id && w.target.revision === analysis.revision);
  return <article className="ops-panel"><h3>Analysis · {analysis.created_utc}</h3>
    <Explain what="When this analysis revision was saved, in UTC." />
    {analysis.created_by === 'automation' ? <DoneAutomatically at={analysis.created_utc} /> : null}
    <p><OutcomeBadge outcome={analysis.outcome} /></p>
    <p>{analysis.qualification}<Explain what="Synthetic model only, not field validated. Qualification is always shown with the outcome." /></p>
    <p>{analysis.explanation_text}<Explain what="The deterministic explanation returned by the physics model." /></p>
    <p>Rules-based synthetic presentation. A missing, inapplicable or failed learned model stays unavailable.</p>
    <p>Learned ranker: {analysis.learned_status ?? 'unknown'} · <Link to={`/ai${analysis.learned_model_version_id ? `#${analysis.learned_model_version_id}` : ''}`}>Model version</Link>
      <Explain what="The learned ranker orders surviving registered explanations. It cannot bring back a rejected factory." /></p>
    <div className="ops-table-scroll"><table><caption>Possible explanations · a lower fit number fits better</caption><thead><tr>
      <th>Explanation</th><th>Physics fit</th><th>Status and reason</th><th>AI order</th></tr></thead><tbody>
      {analysis.candidates.map(candidate => <tr key={candidate.explanation_id}>
        <th scope="row">{candidate.explanation_id}<Explain what={candidate.explanation_id === 'UNKNOWN' ? 'An unknown or unregistered source is always considered.' : 'A registered source or combination, or the background explanation.'} /></th>
        <td>{valueOrUnknown(candidate.fit)}{candidate.fit !== null ? <span className="ops-fit" aria-hidden="true" style={{ width: `${candidate.fit}ch` }} /> : null}
          <Explain what="Normalised error returned by the physics model. Lower fits better; it is not a likelihood. UNKNOWN has no fit value." /></td>
        <td>{candidate.status} · {candidate.reason ?? 'No reason supplied'}<Explain what="Whether this explanation survives the physical checks, and why." /></td>
        <td>{analysis.learned_order.find(rank => rank.explanation_id === candidate.explanation_id)?.rank ?? 'Not ranked'}
          <Explain what="The learned rank, shown as an order only. UNKNOWN and rejected explanations are not ranked." />
          {candidate.sources.map(source => <p key={source}>{source}: fit on earlier runs: {analysis.history_counts?.[source] ?? 'unknown'}
            <Explain what="Number of earlier runs in which this source survived; unknown stays unknown." /></p>)}</td>
      </tr>)}
    </tbody></table></div>
    <h3>Windows used</h3><Explain what="Saved averaging windows cited by the analysis; timings are simulation seconds." />
    <ul>{analysis.input_window_ids.map(id => { const window = snapshot.record('window', id); return <li key={id}>{id}
      {window ? ` · ${window.channel}, ${window.start_s}–${window.end_s} s, ${window.valid_seconds} usable seconds` : ' · window details unavailable'}</li>; })}</ul>
    <h3>What we ignored</h3>{analysis.exclusions.length ? <ul>{analysis.exclusions.map((excluded, i) =>
      <li key={`${excluded.reading_window}-${i}`}>{excluded.reading_window}: {excluded.reason}</li>)}</ul> : <p>No exclusions reported.</p>}
    <Explain what="Readings the model excluded, with the server's reason." />
    <h3>What this cannot tell you</h3><ul>{analysis.limitations.map((limit, i) => <li key={i}>{limit}</li>)}</ul>
    <Explain what="Limits on interpreting this model result; they remain visible for every outcome." />
    <h3>Plain words by AI (it cannot change any number)</h3>
    {wording ? <><p>{wording.text}</p><p>{wording.source === 'TEMPLATE' ? `Fixed text · ${wording.fallback_reason ?? 'template used'}` : `AI wording · ${wording.model ?? 'model unknown'}`}</p></> : <p>No AI wording record yet. The deterministic explanation remains above.</p>}
    <Explain what="A wording service may explain the result but cannot change its numbers, status or outcome." />
  </article>;
}
