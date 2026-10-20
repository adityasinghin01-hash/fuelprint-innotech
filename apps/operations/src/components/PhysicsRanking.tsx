import { Explain, OutcomeBadge } from '@fp/shared';
import type { AnalysisRecord, Candidate } from '@fp/shared';

function registered(row: Candidate) {
  return row.status === 'SURVIVES' && row.sources.length > 0 && row.explanation_id !== 'UNKNOWN';
}

function SavedRow({ row }: { row: Candidate }) {
  return <li data-explanation={row.explanation_id}>
    <strong>{row.explanation_id}</strong> · {row.status} · fit {row.fit === null || !Number.isFinite(row.fit) ? 'unknown (fit unavailable)' : row.fit}
    <Explain what="Saved normalized physics error; lower fits the observations better." />
    {row.reason ? <p>{row.reason}</p> : null}
  </li>;
}

/** Uses only the sequence saved by the server. Never scores or sorts in the browser. */
export function PhysicsRanking({ analysis }: { analysis: AnalysisRecord }) {
  const applicable = analysis.outcome !== 'INVALID' && analysis.outcome !== 'NO_SIGNAL';
  const survivors = applicable ? analysis.candidates.filter(registered) : [];
  const others = analysis.candidates.filter(row => !applicable || !registered(row));
  return <section aria-label="Physics analysis">
    <h2>Physics fit order</h2>
    <p>Physics fit order — rules-based demo; {analysis.learned_status === 'OK' ? 'separate saved learned result available' : 'trained ranker unavailable'}.</p>
    <p>Synthetic teaching data · {analysis.qualification}</p>
    <p>Learned model: {analysis.learned_status ?? 'unknown (not recorded)'}</p>
    <Explain what="This demo presents existing public physics fits in server order. It does not train or activate a model." />
    <p><OutcomeBadge outcome={analysis.outcome} /> <span>{analysis.outcome}</span></p>
    <p>{analysis.explanation_text}</p>
    {survivors.length ? <ol aria-label="Registered survivors in server order">
      {survivors.map((row, i) => <SavedRow key={`${row.explanation_id}-${i}`} row={row} />)}
    </ol> : <p>No registered survivors to order.</p>}
    <Explain what="Registered survivors appear in the saved server sequence. UNKNOWN, background and rejected rows remain below with their saved status." />
    <ul aria-label="Other saved explanations">{others.map((row, i) => <SavedRow key={`${row.explanation_id}-${i}`} row={row} />)}</ul>
    {analysis.exclusions.length ? <section aria-label="Excluded windows"><h3>Excluded observations</h3>
      <ul>{analysis.exclusions.map((e, i) => <li key={i}>{e.reading_window}: {e.reason}</li>)}</ul>
    </section> : null}
    <section aria-label="Analysis limits"><h3>Limits</h3><ul>{analysis.limitations.map((limit, i) => <li key={i}>{limit}</li>)}</ul></section>
    <small>Saved analysis {analysis.id} · revision {analysis.revision} · {analysis.algorithm_version}</small>
  </section>;
}
