import { useState } from 'react';
import { Button, Explain, OutcomeBadge } from '@fp/shared';
import type { CaseRecord, FindingOutcome, RecordRef, SpecimenRecord } from '@fp/shared';
import { Feedback, lines, numberOrNull, useCases, useCasesCommand } from './CasesContext';
const fields=[['gross_ug','Gross sulfur (ug)'],['blank_ug','Blank sulfur (ug)'],['dup_a_pct','Duplicate A (percent w/w)'],['dup_b_pct','Duplicate B (percent w/w)'],['spike_added_ug','Spike added (ug)'],['spike_recovered_ug','Spike recovered (ug)']] as const;
export function LabForm({specimen}:{specimen:SpecimenRecord}){
  const {snapshot}=useCases();const action=useCasesCommand('lab_demo');const [raw,setRaw]=useState<Record<string,string>>({});
  return <section className="case-panel"><h2>4 · Fuel lab teaching inputs</h2>
    <p>Manually declared synthetic lab inputs. The server performs the arithmetic, QC and reporting decision.</p>
    {fields.map(([key,label])=><label key={key}>{label}<input type="number" step="any" value={raw[key] ?? ''} onChange={e=>setRaw(v=>({...v,[key]:e.target.value}))}/></label>)}
    <Explain what="Blank fields are submitted as unknown. Percent w/w here is a laboratory unit, never a candidate likelihood."/>
    <Button disabled={action.disabled} pending={action.pending} onClick={()=>void action.send('request_lab',{specimen_id:specimen.id,method_id:'TEACH-FUEL-S-1',qc_inputs:Object.fromEntries(fields.map(([key])=>[key,numberOrNull(raw[key] ?? '')]))},specimen.revision)}>Request fuel lab evaluation</Button>
    <Feedback action={action}/>
    {snapshot.records('lab_result').filter(r=>r.specimen_id===specimen.id).map(r=><article key={r.id}><h3>Saved result {r.id}</h3>
      <p>QC {r.qc_status} · {r.reporting}</p><ul>{r.qc_reasons.map((reason,i)=><li key={i}>{reason}</li>)}</ul>
      <dl>{Object.entries(r.computed).map(([key,value])=><div key={key}><dt>{key}</dt><dd>{value ?? 'unknown (not computable)'}</dd></div>)}</dl>
      <details><summary>Saved raw values</summary><pre>{JSON.stringify(r.raw,null,2)}</pre></details>
      <Explain what="These stored results describe this specimen only. They do not identify a fuel type or establish plume causation."/>
    </article>)}
  </section>;
}
export function FindingForm({record}:{record:CaseRecord}){
  const {snapshot}=useCases();const action=useCasesCommand('case_reviewer');
  const [outcome,setOutcome]=useState<FindingOutcome>('INCONCLUSIVE');const [limitations,setLimits]=useState('');const [alternatives,setAlternatives]=useState('');const [cited,setCited]=useState<RecordRef[]>([]);
  const samples=snapshot.records('specimen').filter(s=>s.case_id===record.id).map(s=>s.id);
  const investigation=snapshot.record('investigation',record.investigation_id);
  const available=[...snapshot.records('screening_record').filter(s=>record.screening_record_ids.includes(s.id)).map(s=>({type:'screening_record',record:s})),
    ...snapshot.records('lab_result').filter(l=>samples.includes(l.specimen_id)).map(l=>({type:'lab_result',record:l})),
    ...snapshot.records('analysis').filter(a=>investigation?.analysis_ids.includes(a.id)).map(a=>({type:'analysis',record:a}))];
  return <section className="case-panel"><h2>5 · Human finding</h2><p>Legal suitability remains NOT_ASSESSED.</p>
    <label>Finding outcome<select value={outcome} onChange={e=>setOutcome(e.target.value as FindingOutcome)}>{['SUPPORTED_WITHIN_SCOPE','REJECTED_FOR_USE','INCONCLUSIVE'].map(o=><option key={o}>{o}</option>)}</select></label>
    <fieldset><legend>Exact evidence revisions to cite</legend>{available.map(({type,record:r})=>{const ref={type,id:r.id,revision:r.revision};return <label className="case-choice" key={`${type}/${r.id}`}>
      <input type="checkbox" checked={cited.some(c=>c.type===type && c.id===r.id && c.revision===r.revision)} onChange={e=>setCited(v=>e.target.checked?[...v.filter(c=>c.id!==r.id),ref]:v.filter(c=>c.id!==r.id))}/>{type} {r.id} revision {r.revision}</label>;})}</fieldset>
    <label>Finding limitations (one per line)<textarea value={limitations} onChange={e=>setLimits(e.target.value)}/></label>
    <label>Alternative explanations (one per line)<textarea value={alternatives} onChange={e=>setAlternatives(e.target.value)}/></label>
    <Explain what="A person selects the outcome and exact citations. The server enforces QC and claim limits; automation cannot sign this finding."/>
    <Button disabled={action.disabled || record.state==='SEALED' || !limitations.trim() || !cited.length} pending={action.pending}
      onClick={()=>void action.send('revise_finding',{case_id:record.id,outcome,cited_refs:cited,alternatives:lines(alternatives),limitations:lines(limitations)},record.revision)}>Save human finding</Button>
    <Feedback action={action}/>
    {snapshot.records('finding').filter(f=>f.case_id===record.id).map(f=><article key={f.id}><OutcomeBadge outcome={f.outcome}/><p>{f.legal_suitability}</p><ul>{f.limitations.map((l,i)=><li key={i}>{l}</li>)}</ul></article>)}
  </section>;
}
