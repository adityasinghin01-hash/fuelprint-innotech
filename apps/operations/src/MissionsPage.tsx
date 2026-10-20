import { useState } from 'react';
import { Button, DoneAutomatically, Explain, valueOrUnknown } from '@fp/shared';
import type { ProposalAction } from '@fp/shared';
import { Link, useParams } from 'react-router-dom';
import { CommandFeedback, useOperations, useOperationsCommand } from './OperationsContext';

function ActionCard({ action, title }: { action: ProposalAction; title: string }) {
  return <section className="ops-panel"><h3>{title} · {action.action_id}</h3>
    <p>{action.target ? `${action.vehicle} to east ${action.target.east}, north ${action.target.north}, height ${action.target.up} m` : `${action.vehicle}: Target unavailable (see saved rejection)`}
      <Explain what="The vehicle and target in metres relative to the estate map origin." /></p>
    <p>Full time: {valueOrUnknown(action.complete_minutes, 'minutes')}<Explain what="Total measurement time estimated by the planner, including the whole trip." /></p>
    <p>{action.feasible ? 'Feasible' : `Not feasible: ${action.rejection_reason ?? 'reason unknown'}`}
      <Explain what="Whether the planner allows this action. Infeasible actions cannot be proposed." /></p>
    <p>Purpose: {action.kind}<Explain what="Why the planner considered this measurement." /></p>
  </section>;
}

export function MissionsPage() {
  const { snapshot, live } = useOperations();
  const { id } = useParams();
  const missions = snapshot.records('mission');
  const mission = id ? snapshot.record('mission', id) : missions.at(-1);
  const investigation = snapshot.records('investigation').at(-1);
  const savedProposal = snapshot.record('proposal', mission?.proposal_id);
  const latestProposal = snapshot.records('proposal').filter(p => p.analysis_id === investigation?.analysis_ids.at(-1)).at(-1);
  const replaceable = !id && !!mission && ['EXPIRED', 'REJECTED'].includes(mission.state)
    && mission.run_id === investigation?.run_id
    && !!(savedProposal && investigation?.analysis_ids.includes(savedProposal.analysis_id)
      || mission.survey_id && mission.survey_id === investigation?.survey_id);
  const proposal = replaceable ? latestProposal : mission ? savedProposal : id ? undefined : latestProposal;
  const recommended = proposal?.actions.find(action => action.action_id === proposal.best_action_id);
  const canPropose = proposal?.result === 'BEST_ACTION' && recommended?.feasible === true;
  const supervisor = useOperationsCommand('supervisor');
  const analyst = useOperationsCommand('analyst');
  const [reason, setReason] = useState('');
  const vehicle = live.tick?.payload.vehicles.find(v => v.vehicle_id === mission?.vehicle_id);
  const now = live.tick?.sim_time_s;
  const remaining = mission && typeof mission.expires_s === 'number' && Number.isFinite(mission.expires_s)
    && now != null && Number.isFinite(now) ? Math.max(0, mission.expires_s - now) : null;
  const expired = remaining !== null && remaining <= 0;
  if (snapshot.status !== 'ready') return null;
  return <section><p className="ops-eyebrow">OP04 · MISSIONS</p><h1>Approve the measurement.</h1>
    <p>A person approves the route. The planner and autopilot cannot sign for them.</p>
    <IndependentSurvey />
    <nav aria-label="Saved missions">{missions.map(m => <p key={m.id}><Link to={`/missions/${m.id}`}>{m.action_id}</Link> · {m.state}<Explain what="A saved mission and its current state." /></p>)}</nav>
    {id && !mission ? <p>This mission was not found in the current run.</p> : null}
    {replaceable ? <p>The previous mission {mission.state.toLowerCase()}. Review the latest saved recommendation before proposing a replacement.
      <Explain what="A new proposal creates a separate mission and requires new supervisor approval. The old mission stays in history." /></p> : null}
    {!proposal ? <p>{replaceable ? 'No current recommendation is available to replace this mission.' : mission?.survey_id && !mission.proposal_id ? 'Scheduled survey mission; no analysis proposal is attached.'
      : mission ? 'The proposal referenced by this mission is unavailable.' : 'No proposal for the current investigation is available.'}</p> : <>
      <p>Planner result: {String(proposal.result) === 'PENDING' ? 'Still working it out' : proposal.result === 'NO_USEFUL_MEASUREMENT'
        ? 'No measurement would help right now' : proposal.result === 'BEST_ACTION' ? 'A useful measurement is available' : 'Unknown planner result; no mission can be proposed'}<Explain what="The planner's result. Pending, unknown and no-useful-measurement results recommend no mission." /></p>
      <div className="ops-grid">{(proposal.result === 'BEST_ACTION' ? [proposal.best_action_id, proposal.cheaper_action_id] : []).map((actionId, i) => {
        const action = proposal.actions.find(a => a.action_id === actionId);
        return action ? <ActionCard key={`${i}-${actionId}`} action={action} title={i ? 'Cheaper useful option' : 'Recommended measurement'} /> : null;
      })}</div>
      <h2>Actions considered</h2><div className="ops-grid">{proposal.actions.filter(a => !a.feasible).map(a => <ActionCard key={a.action_id} action={a} title="Rejected action" />)}</div>
      {!mission || replaceable ? <>{proposal.result === 'BEST_ACTION' && !canPropose ? <p role="alert">The recommended action is missing or marked infeasible. A mission cannot be proposed from it.</p> : null}
        <div className="ops-actions"><Button pending={analyst.pending} disabled={analyst.disabled || !canPropose}
        onClick={() => { if (canPropose && recommended) void analyst.send('propose_mission', { proposal_id: proposal.id, action_id: recommended.action_id }, proposal.revision); }}>Propose recommended mission</Button></div>
        <Explain what="Ask the server to propose the recommended feasible action. It must still be approved by a supervisor." /><CommandFeedback action={analyst} /></> : null}
    </>}
    {mission ? <section className="ops-panel"><h2>Mission state · {mission.state}</h2><Explain what="The current saved stage. Expired or interrupted missions require a new proposal and approval." />
      <p>{mission.created_by === 'automation' ? <>Proposed automatically · <DoneAutomatically at={mission.created_utc} /></> : `Proposed by ${mission.created_by}`}</p>
      <p>Vehicle: {vehicle?.label ?? mission.vehicle_id}; pilot: {vehicle?.pilot ?? mission.pilot}<Explain what="The assigned vehicle and who is flying: visitor, operator, autopilot or nobody." /></p>
      <p>Method: {mission.method_id}; channels: {mission.channel_ids.join(', ')}<Explain what="The approved procedure and the air channels to measure." /></p>
      <p>Target: east {mission.target.east}, north {mission.target.north}, height {mission.target.up} m<Explain what="The server-approved sampling point in metres." /></p>
      <p>Approval window: {remaining === null ? 'unknown' : `${remaining.toFixed(1)} simulation seconds remaining`}
        <Explain what="Time until the saved expiry, using the server's simulation clock. The server makes the final expiry decision." /></p>
      <details><summary>Approved route</summary><ol>{mission.route.map((point, index) => <li key={index}>{point.east}, {point.north}, {point.up} m<Explain what="Route point in east, north and height metres, in flight order." /></li>)}</ol></details>
      <p>Phase timings, energy requirement and autopilot countdown: unknown; these fields are not supplied by the current server interface.</p>
      <Explain what="Unknown planning details are never replaced with invented estimates." />
      <label className="ops-form">Reason for rejection<input value={reason} onChange={e => setReason(e.target.value)} aria-describedby="reject-help" /></label>
      <Explain id="reject-help" what="A supervisor records why a proposed mission should not proceed." />
      <div className="ops-actions"><Button pending={supervisor.pending} disabled={supervisor.disabled || mission.state !== 'PROPOSED' || expired || remaining === null}
        onClick={() => void supervisor.send('approve_mission', { mission_id: mission.id, dependency_hash: mission.dependency_hash }, mission.revision)}>Approve mission</Button>
        <Button variant="secondary" pending={supervisor.pending} disabled={supervisor.disabled || mission.state !== 'PROPOSED' || !reason.trim()}
          onClick={() => void supervisor.send('reject_mission', { mission_id: mission.id, reason }, mission.revision)}>Reject mission</Button></div>
      <Explain what="Only a supervisor can approve or reject. Approval checks the current analysis, wind, route and expiry on the server." />
      <CommandFeedback action={supervisor} />
    </section> : <p>No mission has been saved yet.</p>}
  </section>;
}

function IndependentSurvey() {
  const { snapshot } = useOperations();
  const action = useOperationsCommand('analyst');
  const [reason,setReason] = useState('');
  const [actionId,setActionId] = useState('SURVEY-DRONE-0-100-35');
  const surveys=snapshot.records('survey');
  return <section className="ops-panel" aria-label="Independent surveys"><h2>Independent survey</h2>
    <p>A survey can be scheduled even when the planner has no useful recommendation. No information benefit is promised.</p>
    <label className="ops-form">Public survey action<select value={actionId} onChange={e=>setActionId(e.target.value)}>
      <option value="SURVEY-DRONE-0-100-35">DRONE · east 0, north 100, up 35 m</option>
      <option value="SURVEY-DRONE-280-140-45">DRONE · east 280, north 140, up 45 m</option>
    </select></label><Explain what="The two existing public teaching survey actions. The server checks vehicle availability and route authority."/>
    <label className="ops-form">Survey reason<input value={reason} onChange={e=>setReason(e.target.value)}/></label>
    <Button disabled={action.disabled || !reason.trim()} pending={action.pending} onClick={()=>void action.send('schedule_survey',{action_id:actionId,reason},null)}>Schedule independent survey</Button>
    <Explain what="Saves a human-directed survey. It still needs a mission proposal and separate human supervisor approval."/>
    {surveys.map(s=><div key={s.id}><p>{s.action_id} · {s.reason} · revision {s.revision}</p>
      <Button disabled={action.disabled || snapshot.records('mission').some(m=>m.survey_id===s.id && !['EXPIRED','REJECTED','COMPLETE','INTERRUPTED'].includes(m.state))}
        onClick={()=>void action.send('propose_mission',{survey_id:s.id,action_id:s.action_id},s.revision)}>Propose survey mission</Button>
    </div>)}<CommandFeedback action={action}/>
  </section>;
}
