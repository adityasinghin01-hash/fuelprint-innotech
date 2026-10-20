import {
  AutomationLog, Explain, LiveBadge, LiveProvider, StaleBanner, StationLogin, useLive, useLiveEvents, useSession, useSnapshot,
  valueOrUnknown,
} from '@fp/shared';
import type { LiveConnection } from '@fp/shared';
import { useState } from 'react';
import { BrowserRouter, Link, NavLink, Route, Routes } from 'react-router-dom';
import { OperationsContext, PageState, useOperations } from './OperationsContext';
import { InvestigationPage } from './InvestigationPage';
import { MissionsPage } from './MissionsPage';
import { BoardPage } from './BoardPage';
import { ScenariosPage } from './ScenariosPage';
import './operations.css';
import { PhysicsRanking } from './components/PhysicsRanking';
import { WorkflowExplorer } from './WorkflowExplorer';

export function App({ connection }: { connection?: LiveConnection }) {
  return <LiveProvider connection={connection}><BrowserRouter basename="/operations"><Frame /></BrowserRouter></LiveProvider>;
}
const navigation = [['/', 'Overview'], ['/workflow', 'Workflow explorer'], ['/health', 'Health'], ['/investigations', 'Investigation'],
  ['/analysis', 'Physics order'], ['/missions', 'Missions'], ['/board', 'Board'], ['/scenarios', 'Scenarios'], ['/ai', 'AI and automation'], ['/hardware', 'Hardware']];
function Frame() {
  const live = useLive();
  const snapshot = useSnapshot();
  const session = useSession();
  const [reportRevision, setReportRevision] = useState(0);
  useLiveEvents(event => {
    if (['finding', 'run', 'investigation', 'case', 'mission'].includes(event.payload.record_type)) {
      setReportRevision(revision => revision + 1);
    }
  });
  const run = snapshot.snapshot?.run;
  const tick = live.tick;
  const investigation = snapshot.records('investigation').at(-1);
  const proposal = snapshot.records('proposal').filter(record => record.analysis_id === investigation?.analysis_ids.at(-1)).at(-1);
  const mission = snapshot.records('mission').filter(record =>
    proposal && record.proposal_id === proposal.id || investigation?.survey_id && record.survey_id === investigation.survey_id).at(-1);
  const endedMission = mission?.state === 'EXPIRED' || mission?.state === 'REJECTED';
  const autoPropose = snapshot.records('automation_settings').at(-1)?.auto_propose;
  const analysisNext = String(proposal?.result) === 'PENDING'
    ? 'The planner is still working it out. No mission is recommended yet.'
    : proposal?.result === 'NO_USEFUL_MEASUREMENT'
      ? 'No measurement would help right now. Review the analysis and its limits.'
      : proposal?.result !== 'BEST_ACTION' || !proposal.best_action_id
        ? 'Review the analysis. No recommended measurement is available yet.'
        : autoPropose === true ? 'Review the analysis. Automation can propose the recommended mission.'
          : autoPropose === false ? 'Automatic proposals are off. An analyst can propose the recommended measurement.'
            : 'Review the recommended measurement. Automation settings are unavailable.';
  const next = !run ? 'Choose a scenario and start a new run.' : run.state_reason === 'INTERRUPTED'
    ? 'The previous run was interrupted. Review the saved records before resetting.'
    : run.state === 'PAUSED' ? 'The presenter can resume this paused run.'
    : run.state === 'COMPLETED' || run.state === 'ABORTED' ? 'This run has ended. Review saved records or start a new scenario.'
    : investigation && endedMission && ['MISSION_PROPOSED', 'MISSION_APPROVED'].includes(investigation.state)
      ? 'The saved mission expired or was rejected. Review the latest analysis and propose a new measurement.'
    : investigation ? ({ OPEN: 'Automation will analyse the saved windows.',
      ANALYSED: analysisNext,
      MISSION_PROPOSED: 'A supervisor must review and approve or reject the mission.',
      MISSION_APPROVED: 'A visitor, operator or autopilot can fly the approved route.',
      EVIDENCE_BACK: 'Automation will analyse the returned evidence.', CLOSED: 'The board can review the case and its limits.',
    })[investigation.state] : 'Watch the sensors, or schedule a survey when no alarm fires.';
  return <OperationsContext.Provider value={{ live, snapshot, session, reportRevision }}>
    <a className="ops-skip" href="#ops-content">Skip to content</a>
    <div className="ops-shell"><aside className="ops-sidebar">
      <Link className="ops-brand" to="/">FuelPrint<span>OPERATIONS · SIMULATED</span></Link>
      <nav aria-label="Operations pages">{navigation.map(([path, label]) => <NavLink key={path} to={path} end={path === '/'}>{label}</NavLink>)}</nav>
      <p>Synthetic teaching data on a fictional estate. No real factory is being investigated.</p>
      <Explain what="Navigation opens the control-room workspaces. All decisions remain on the server." />
    </aside><div className="ops-workspace">
      <header className="ops-topbar"><LiveBadge status={live.status} />
        <span data-testid="run-state">Run: {run ? `${run.preset_id} (${run.state})` : 'none yet'}<Explain what="The scenario and run state reported by the server." /></span>
        <span data-testid="sim-time">Time: {valueOrUnknown(tick?.sim_time_s, 's', 1)}<Explain what="Simulation seconds since this run began." /></span>
        <span>Wind: {tick?.payload.wind ? <><span aria-hidden="true" className="ops-wind"
          style={{ transform: `rotate(${tick.payload.wind.from_deg}deg)` }}>↓</span> from {tick.payload.wind.from_deg}° at {tick.payload.wind.speed_m_s} m/s</> : 'unknown'}
          <Explain what="Direction the wind comes from, clockwise from north, and its speed." /></span>
        <span>{session.session?.signed_in ? `${session.session.station_id}: ${session.session.roles.join(', ')}` : 'Not signed in'}<Explain what="Station roles decide which commands you may send." /></span>
      </header>
      <StaleBanner status={live.status} />
      <div className="ops-next" role="status">Next action · {next}<Explain what="Guidance from the saved run and investigation state; this line does not change either." /></div>
      <main id="ops-content" className="ops-main">
        {session.status === 'error' ? <p role="alert">Session could not be loaded: {session.error}</p> : null}
        {session.status === 'loading' ? <p>Loading station session…</p> : null}
        {session.status === 'ready' && !session.session?.signed_in ? <section className="ops-panel" aria-label="Sign in"><h2>Sign in this laptop</h2><StationLogin login={session.login} /></section> : null}
        {run?.state_reason === 'INTERRUPTED' ? <p className="fp-banner">Interrupted run: records are preserved; investigation controls are read-only.</p> : null}
        <PageState />
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/workflow" element={<WorkflowExplorer />} />
          <Route path="/investigations" element={<InvestigationPage />} />
          <Route path="/investigations/:id" element={<InvestigationPage />} />
          <Route path="/missions" element={<MissionsPage />} />
          <Route path="/missions/:id" element={<MissionsPage />} />
          <Route path="/board" element={<BoardPage />} />
          <Route path="/analysis" element={<SavedPhysics />} />
          <Route path="/scenarios" element={<ScenariosPage />} />
          <Route path="/health" element={<TeammatePage title="Sensor health" />} />
          <Route path="/ai" element={<TeammatePage title="AI and automation" />} />
          <Route path="/hardware" element={<TeammatePage title="Hardware" />} />
          <Route path="*" element={<p>Page not found. <Link to="/">Return to overview</Link></p>} />
        </Routes><AutomationLog runId={snapshot.snapshot?.run_id} max={5} />
      </main>
    </div></div>
  </OperationsContext.Provider>;
}
function Overview() {
  const { live, snapshot } = useOperations();
  const drone = live.tick?.payload.vehicles[0];
  return <section><p className="ops-eyebrow">CONTROL ROOM</p><h1>Follow the evidence.</h1>
    <p>Watch the investigation, review the next measurement and keep every conclusion within its limits.</p>
    <WorkflowExplorer />
    <div className="ops-grid">{[['/investigations', 'Investigation', 'See the possible explanations and what the models cannot tell us.'],
      ['/missions', 'Missions', 'Review the measurement before a person approves it.'], ['/board', 'Board', 'Review findings and record an area-level notice.'],
      ['/scenarios', 'Scenarios', 'Start, pause or reset a synthetic teaching run.']].map(([path, title, text]) =>
      <Link className="ops-panel ops-link-card" key={path} to={path}><h2>{title}</h2><p>{text}</p></Link>)}</div>
    <section className="ops-panel"><h2>Live field view</h2><p data-testid="drone">Drone: {drone
      ? `${drone.label} at height ${valueOrUnknown(drone.pose.up, 'm', 1)}, ${drone.stage ?? 'no mission'}, pilot ${drone.pilot}` : 'no vehicle yet'}
      <Explain what="The drone position, mission stage and pilot from the server tick." /></p>
      <p data-testid="cursor">Last event: {live.cursor ?? 'none yet'}<Explain what="Saved-event cursor; it advances when the server saves records." /></p>
      {!snapshot.snapshot?.run ? <p>No run yet. A presenter can start one on the scenario page.</p> : null}
    </section>
  </section>;
}
function TeammatePage({ title }: { title: string }) {
  return <section><h1>{title}</h1><p>This contributor panel is unavailable in the pinned contribution.</p>
    <p>Use Physics order for the rules-based prototype. The trained ranker and normal-air model remain unavailable.</p></section>;
}

function SavedPhysics() {
  const {snapshot}=useOperations();const analyses=snapshot.records('analysis');
  return <section><h1>Saved physics order</h1>{!analyses.length?<p>No analyses saved for this run yet.</p>:analyses.map(a=><PhysicsRanking key={a.id} analysis={a}/>)}</section>;
}
