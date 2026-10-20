import { useState } from 'react';
import { Button, Explain, useCommand } from '@fp/shared';
import type { AttemptRecord, ChannelDecision } from '@fp/shared';
const decisions:ChannelDecision[]=['VALID','LIMITED','REJECTED'];
export function CaptureReview({attempt,disabled,reload}:{attempt:AttemptRecord;disabled:boolean;reload:()=>void}){
  const action=useCommand();const [chosen,setChosen]=useState<Record<string,ChannelDecision|''>>({});const [note,setNote]=useState('');
  const complete=attempt.channels.every(c=>chosen[c.channel] && decisions.indexOf(chosen[c.channel] as ChannelDecision)>=decisions.indexOf(c.status ?? 'REJECTED'));
  return <section className="frame-details"><h3>Capture {attempt.id} · {attempt.state}</h3><p>Revision {attempt.revision} · {attempt.method_id}</p>
    {attempt.channels.map(c=><div key={c.channel}><p>{c.channel} · automatic precheck {c.status ?? 'unknown'} · {c.valid_seconds ?? 'unknown'} valid seconds · {c.volume_L ?? 'unknown'} L</p>
      <p>{c.reasons.join('; ') || 'No precheck reason supplied'}</p>
      <label>Decision for {c.channel}<select value={chosen[c.channel] ?? ''} onChange={e=>setChosen(v=>({...v,[c.channel]:e.target.value as ChannelDecision|''}))}>
        <option value="">Choose a human decision</option>{decisions.map(d=><option key={d} disabled={decisions.indexOf(d)<decisions.indexOf(c.status ?? 'REJECTED')}>{d}</option>)}
      </select></label></div>)}
    <label>Review reasons (one per line)<textarea value={note} onChange={e=>setNote(e.target.value)}/></label>
    <Explain what="A person must choose every channel. A failed automatic check cannot be promoted; the server validates each decision again."/>
    <Button disabled={disabled || action.pending || !complete || attempt.state==='COLLECTING'} pending={action.pending} onClick={async()=>{
      if(disabled || !complete || action.pending)return;
      await action.send('review_capture',{attempt_id:attempt.id,channel_decisions:attempt.channels.map(c=>({channel:c.channel,decision:chosen[c.channel],reasons:note.split('\n').map(x=>x.trim()).filter(Boolean)}))},attempt.revision,{runId:attempt.run_id});reload();
    }}>Save human capture review</Button>
    {action.receipt?<p role={action.receipt.status==='APPLIED'?'status':'alert'}>{action.receipt.status}: {action.receipt.reason_text ?? action.problem ?? 'Saved by server'}</p>:null}
  </section>;
}
