import { useState } from 'react';
import { Button, Explain } from '@fp/shared';
import type { CaseRecord, SpecimenRecord } from '@fp/shared';
import { Feedback, useCases, useCasesCommand, numberOrNull, lines } from './CasesContext';

export function Intake(){
  const {snapshot}=useCases();const action=useCasesCommand('case_reviewer');
  const [selected,setSelected]=useState<string[]>([]);const [purpose,setPurpose]=useState('');
  return <section className="case-panel"><h2>1 · Case intake</h2>
    <p>Select actual saved screening records. Rejected channels remain rejected.</p>
    {snapshot.records('screening_record').map(s=><label className="case-choice" key={s.id}>
      <input type="checkbox" checked={selected.includes(s.id)} onChange={e=>setSelected(v=>e.target.checked?[...v,s.id]:v.filter(id=>id!==s.id))}/>
      {s.id} · {s.channel} · {s.decision} · {s.reasons.join('; ') || 'No reason supplied'}
    </label>)}
    {!snapshot.records('screening_record').length?<p>No reviewed screening records yet. Complete the Field QC review first.</p>:null}
    <label>Case purpose<input value={purpose} onChange={e=>setPurpose(e.target.value)}/></label>
    <Button disabled={action.disabled || !purpose.trim() || !selected.length} pending={action.pending}
      onClick={()=>void action.send('create_case',{screening_record_ids:selected,purpose})}>Create case intake</Button>
    <Feedback action={action}/>
  </section>;
}
export function SpecimenForm({record}:{record:CaseRecord}){
  const action=useCasesCommand('inspector_demo');const [site,setSite]=useState('S01');const [mass,setMass]=useState('');
  const [container,setContainer]=useState('');const [seal,setSeal]=useState('');
  return <section className="case-panel"><h2>2 · Separate fuel specimen</h2>
    <p>Declared synthetic material input. This does not identify the material or connect it to an air plume.</p>
    <label>Fictional collection site<select value={site} onChange={e=>setSite(e.target.value)}>{['S01','S02','S03','S04','S05','S06'].map(s=><option key={s}>{s}</option>)}</select></label>
    <label>Specimen mass (mg)<input type="number" step="any" value={mass} onChange={e=>setMass(e.target.value)}/></label>
    <Explain what="Recorded mass entered by the inspector. Unknown is never zero; the server checks the declared method minimum."/>
    <label>Container<input value={container} onChange={e=>setContainer(e.target.value)}/></label>
    <label>Seal identifier<input value={seal} onChange={e=>setSeal(e.target.value)}/></label>
    <Button disabled={action.disabled || numberOrNull(mass)===null || !container.trim() || !seal.trim() || record.state==='SEALED'}
      onClick={()=>void action.send('create_specimen',{case_id:record.id,site_id:site,material:'SYNTHETIC_COAL_COKE_LIKE',method_id:'TEACH-FUEL-S-1',container,seal_id:seal,mass_mg:numberOrNull(mass)},record.revision)}>Record fuel specimen</Button>
    <Feedback action={action}/>
  </section>;
}
export function CustodyForm({specimen}:{specimen:SpecimenRecord}){
  const {snapshot,session}=useCases();const action=useCasesCommand(session.can('inspector_demo')?'inspector_demo':'lab_demo');
  const [holder,setHolder]=useState('');const [time,setTime]=useState('');const [seal,setSeal]=useState('INTACT');const [exceptions,setExceptions]=useState('');
  return <section className="case-panel"><h2>3 · Custody</h2><p>Current holder: {specimen.current_holder}</p>
    <label>Transfer to human holder<input value={holder} onChange={e=>setHolder(e.target.value)}/></label>
    <label>Event time UTC<input placeholder="2026-10-20T10:00:00Z" value={time} onChange={e=>setTime(e.target.value)}/></label>
    <label>Seal state<select value={seal} onChange={e=>setSeal(e.target.value)}>{['INTACT','BROKEN','MISSING'].map(s=><option key={s}>{s}</option>)}</select></label>
    <label>Custody exceptions (one code per line)<textarea value={exceptions} onChange={e=>setExceptions(e.target.value)}/></label>
    <Explain what="Enter only documented exception codes, such as BROKEN_SEAL, MISSING_SEAL, RECIPIENT_ABSENT, REFUSED, LATE_TRANSFER or CORRECTION. A correction records the previous event and cannot silently transfer possession."/>
    <Button disabled={action.disabled || !holder.trim() || !time.trim()} onClick={()=>void action.send('append_custody',{specimen_id:specimen.id,to_holder:holder,seal_state:seal,event_time_utc:time,exceptions:lines(exceptions)},specimen.revision)}>Append custody event</Button>
    <ul>{snapshot.records('custody_event').filter(c=>c.specimen_id===specimen.id).map(c=><li key={c.id}>{c.event_time_utc} · {c.from_holder ?? 'Collection'} → {c.to_holder} · {c.seal_state} · {c.exceptions.join(', ')}</li>)}</ul>
    <Feedback action={action}/>
  </section>;
}
