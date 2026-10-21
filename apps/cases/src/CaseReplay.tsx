import { useEffect, useState } from 'react';
import { Explain, getJson } from '@fp/shared';
import type { CaseRecord, RecordBase } from '@fp/shared';
import { ArrowLeft, ArrowRight, Clock } from './icons';
import { useCases } from './CasesContext';
import { buildTimeline, shortId, timeLabel, words } from './caseData';

/** Read saved revisions only: this never changes the run clock or synthesizes a past outcome. */
export function CaseReplay({ record }: { record: CaseRecord }) {
  const { data } = useCases();
  const [history, setHistory] = useState<{type: string; record: RecordBase}[]>([]);
  const [status, setStatus] = useState('loading');
  const [step, setStep] = useState(0);
  const targets = [{type: 'case', record},
    ...data.records('specimen').filter(item => item.case_id === record.id).map(record => ({type:'specimen',record})),
    ...data.records('media').filter(item => item.case_id === record.id).map(record => ({type:'media',record})),
    ...data.records('finding').filter(item => item.case_id === record.id).map(record => ({type:'finding',record})),
  ];
  const key = targets.map(item => `${item.type}/${item.record.id}/${item.record.revision}`).join('|');
  useEffect(() => {
    let cancelled = false;
    setHistory([]); setStep(0); setStatus('loading');
    const requests = targets.flatMap(item => Array.from({length: Math.min(item.record.revision - 1, 99)}, (_,index) =>
      getJson<RecordBase>(`/api/records/${item.type}/${item.record.id}?revision=${index+1}`).then(record => ({type:item.type,record}))));
    void Promise.allSettled(requests).then(results => {
      if (cancelled) return;
      setHistory(results.flatMap(result => result.status === 'fulfilled' ? [result.value] : []));
      setStatus(results.some(result => result.status === 'rejected') || targets.some(item => item.record.revision > 100) ? 'partial' : 'ready');
    });
    return () => { cancelled = true; };
  }, [key]);
  if (record.state !== 'SEALED') return <section className="evidence-screen"><h2>Case replay.</h2><p>Replay becomes available after the case is sealed. The live timeline remains alongside each evidence stage.</p></section>;
  const events = buildTimeline(data.snapshot, record, history);
  const index = Math.min(step, Math.max(0, events.length-1));
  const event = events[index];
  return <section className="evidence-screen"><div className="screen-title"><Clock size={32} weight="light"/><div><h2>The saved case, step by step.</h2><p>Read-only history · Case {shortId(record.id)}</p></div></div>
    <Explain what="Move through saved public records and their revisions. This is a record-history viewer, not a replay of the Field simulation, and it changes no evidence."/>
    {status === 'loading' && <p role="status">Loading saved revisions…</p>}
    {status === 'partial' && <p role="alert">Some earlier revisions are unavailable or exceed this viewer’s first 100 revisions per record. This is a partial history.</p>}
    {event && <><div className="replay-controls"><button className="button secondary" disabled={index===0} onClick={()=>setStep(index-1)}><ArrowLeft size={18}/>Previous record</button><span>{index+1} of {events.length}</span><button className="button secondary" disabled={index===events.length-1} onClick={()=>setStep(index+1)}>Next record<ArrowRight size={18}/></button></div>
    <label className="form-field">History position<input aria-label="History position" type="range" min="0" max={events.length-1} value={index} onChange={e=>setStep(Number(e.target.value))}/></label>
    <article className="replay-record" aria-live="polite"><h3>{event.correctionOf ? 'Correction: ' : ''}{event.title}</h3><p>{event.detail}</p><p>Record {shortId(event.id)} · Revision {event.revision}</p><time dateTime={event.at}>{timeLabel(event.at)}</time><p>{event.automatic ? 'Done automatically' : words(event.actor)}</p>{event.correctionOf && <p>Corrects the preceding saved event {shortId(event.correctionOf)}; the original remains in the history.</p>}</article></>}
    <Explain what="A later finding or verification result is not presented as if it existed at an earlier step. The current evidence stages remain accessible through the case navigation."/>
  </section>;
}
