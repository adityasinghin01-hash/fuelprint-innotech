import { useState } from 'react';
import { Explain, useLive, useSession, useSnapshot } from '@fp/shared';
import { CasesContext } from './CasesContext';
import { Intake, SpecimenForm, CustodyForm } from './IntakeSpecimen';
import { LabForm, FindingForm } from './LabFinding';
import { EvidenceVault } from './EvidenceVault';
export function CasesWorkbench(){
  const snapshot=useSnapshot();const session=useSession();const live=useLive();const [selected,setSelected]=useState('');const [specimenId,setSpecimen]=useState('');
  const cases=snapshot.records('case');const record=cases.find(c=>c.id===selected) ?? cases.at(-1);
  const specimens=snapshot.records('specimen').filter(s=>s.case_id===record?.id);const specimen=specimens.find(s=>s.id===specimenId) ?? specimens.at(-1);
  return <CasesContext.Provider value={{snapshot,session,live}}><section className="case-workbench"><h1>Cases · evidence in scope</h1>
    <p>SYNTHETIC_TEACHING · Separate fuel specimens, air screening and aerosol media. A passing hash or laboratory QC is not plume causation.</p>
    {snapshot.status==='loading'?<p>Loading case records…</p>:null}{snapshot.error?<p role="alert">{snapshot.error}</p>:null}
    <Intake/>
    {record?<><label>Saved case<select value={record.id} onChange={e=>{setSelected(e.target.value);setSpecimen('');}}>{cases.map(c=><option key={c.id} value={c.id}>{c.id} · {c.state}</option>)}</select></label>
      <h2>{record.purpose}</h2><p>{record.id} · revision {record.revision} · {record.state}</p>
      <Explain what="Every change saves a new revision. These controls submit the revision you are viewing."/>
      <SpecimenForm key={`specimen-${record.id}`} record={record}/>
      {specimen?<><label>Saved fuel specimen<select value={specimen.id} onChange={e=>setSpecimen(e.target.value)}>{specimens.map(s=><option key={s.id} value={s.id}>{s.site_id} · {s.id}</option>)}</select></label>
        <p>Method {specimen.method_id} · declared material {specimen.material} · mass {specimen.mass_mg} mg</p>
        <CustodyForm key={`custody-${specimen.id}`} specimen={specimen}/><LabForm key={`lab-${specimen.id}`} specimen={specimen}/></>:<p>No separate specimen recorded for this case.</p>}
      <FindingForm key={`finding-${record.id}`} record={record}/><EvidenceVault record={record}/>
    </>:snapshot.status==='ready'?<p>No cases saved in this run.</p>:null}
  </section></CasesContext.Provider>;
}
