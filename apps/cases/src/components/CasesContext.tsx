import { createContext, useContext } from 'react';
import { Explain, useCommand } from '@fp/shared';
import type { LiveState, Role, SessionState, SnapshotState } from '@fp/shared';
export interface CasesState { live: LiveState; snapshot: SnapshotState; session: SessionState; }
export const CasesContext=createContext<CasesState|null>(null);
export function useCases(){const state=useContext(CasesContext);if(!state)throw new Error('Cases workbench needs provider');return state;}
export function useCasesCommand(role:Role){
  const {live,snapshot,session}=useCases();const action=useCommand();const run=snapshot.snapshot?.run;
  const allowed=session.status==='ready' && session.session?.signed_in===true && session.can(role);
  const disabled=!allowed || action.pending || live.status!=='live' || live.mode!=='live' || snapshot.status!=='ready' || !run || run.state_reason==='INTERRUPTED';
  async function send(type:string,payload:object,revision:number|null=null){
    if(disabled)return;
    const receipt=await action.send(type,payload,revision,{runId:run?.id});
    if(receipt.status==='APPLIED' || receipt.reason_code==='STALE_REVISION')snapshot.reload();
  }
  return {...action,allowed,disabled,send,role};
}
export function Feedback({action}:{action:ReturnType<typeof useCasesCommand>}){
  return <div aria-live="polite">{!action.allowed?<p>Not allowed: sign in with {action.role}.</p>:null}
    {action.pending?<p role="status">Command pending…</p>:null}
    {action.receipt?<p role={action.receipt.status==='APPLIED'?'status':'alert'}>{action.receipt.status}: {action.receipt.reason_text ?? action.problem ?? 'Saved by server'}</p>:null}
    <Explain what="The server validates role, current revision and idempotency. Interrupted or stale views are read-only."/>
  </div>;
}
export function lines(value:string){return value.split('\n').map(x=>x.trim()).filter(Boolean);}
export function numberOrNull(value:string){return value.trim()==='' || !Number.isFinite(Number(value)) ? null:Number(value);}
