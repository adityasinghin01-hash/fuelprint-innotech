import { useEffect, useRef, useState } from 'react';
import { ArrowRight, ArrowUpRight, Clock, Fingerprint, Flask, FolderOpen, Leaf, List, MagnifyingGlass, Scan, ShieldCheck, SignIn, Wind, X } from './icons';
import { AutomationLog, Explain, LiveBadge, LiveProvider, StaleBanner, StationLogin, useLive, valueOrUnknown } from '@fp/shared';
import type { LiveConnection } from '@fp/shared';
import { BrowserRouter, Link, NavLink, Route, Routes, useLocation } from 'react-router-dom';
import { CasesProvider, useCases } from './CasesContext';
import { caseHref, displayNumber, shortId, stages, timeLabel, words } from './caseData';
import { CaseDetail, IntakeScreen, LabScreen, CustodyScreen, Status } from './CaseScreens';
import './styles.css';

import { StoryLanding } from './StoryLanding';
import { animatePage } from './pageMotion';

export function App({ connection }: { connection?: LiveConnection }) {
  return <LiveProvider connection={connection}><BrowserRouter basename="/cases/"><CasesProvider><Frame /></CasesProvider></BrowserRouter></LiveProvider>;
}

function Frame() {
  const { session } = useCases();
  const live = useLive();
  const [loginOpen, setLoginOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();
  const loginRef = useRef<HTMLDialogElement>(null);
  const loginButton = useRef<HTMLButtonElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null),main = useRef<HTMLElement>(null);
  useEffect(() => { setMenuOpen(false); if (!/jsdom/i.test(navigator.userAgent)) window.scrollTo({ top: 0, behavior: 'instant' }); }, [location.pathname]);
  useEffect(()=>{
    if(location.pathname==='/')return;
    const animation=animatePage(main.current);
    return ()=>animation?.cancel();
  },[location.pathname]);
  useEffect(() => {
    if (loginOpen) loginRef.current?.showModal?.(); else if (loginRef.current?.open) loginRef.current.close();
  }, [loginOpen]);
  return <div className={`fuelprint${location.pathname === '/' ? ' cinematic-home' : ''}`}>
    <a className="skip-link" href="#main">Skip to content</a>
    <header className="site-header" onKeyDown={(event)=>{if(event.key==='Escape'&&menuOpen){setMenuOpen(false);menuButton.current?.focus();}}}>
      <Link to="/" className="wordmark" aria-label="FuelPrint home"><Fingerprint size={34} /><span>FuelPrint<span className="wordmark-dot">.</span></span></Link>
      <nav id="main-navigation" className={menuOpen ? 'main-nav is-open' : 'main-nav'} aria-label="Main navigation"><NavLink to="/" end>Observatory</NavLink><NavLink to="/workspace">Casework</NavLink><NavLink to="/guide">The evidence journey</NavLink></nav>
      <div className="header-actions"><span className="header-live"><LiveBadge status={live.status} /></span><button ref={loginButton} aria-label={session.session?.signed_in ? 'My station' : 'Station access'} className="station-button" onClick={() => setLoginOpen(true)}><SignIn size={18} /><span>{session.session?.signed_in ? 'My station' : 'Station access'}</span></button><button ref={menuButton} className="icon-button menu-toggle" aria-label={menuOpen ? 'Close menu' : 'Open menu'} aria-controls="main-navigation" aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? <X size={24} /> : <List size={24} />}</button></div>
    </header>
    <StaleBanner status={live.status} />
    <main ref={main} id="main" tabIndex={-1} className={location.pathname === '/' ? 'home-main' : 'workspace-main'}><Routes>
      <Route path="/" element={<Observatory />} /><Route path="/workspace" element={<Workspace />} /><Route path="/guide" element={<EvidenceGuide />} />
      <Route path="/intake" element={<IntakeScreen />} /><Route path="/specimens/:specimenId/custody" element={<CustodyScreen />} /><Route path="/specimens/:specimenId/lab" element={<LabScreen />} /><Route path="/:caseId/*" element={<CaseDetail />} />
    </Routes></main>
    <footer className="site-footer"><Link to="/" className="footer-wordmark"><Fingerprint size={23} /> FuelPrint</Link><p>A clearer view. An honest record.</p><span>KIET InnoTech26 · Synthetic teaching demo</span></footer>
    <dialog ref={loginRef} className="station-dialog" onCancel={() => setLoginOpen(false)} onClose={() => { setLoginOpen(false); loginButton.current?.focus(); }} aria-labelledby="station-title">
      <button className="icon-button dialog-close" aria-label="Close station access" onClick={() => setLoginOpen(false)}><X size={22} /></button><Fingerprint size={42} className="dialog-mark" /><h2 id="station-title">Your place in the workflow.</h2><p>Sign in to your station to review and record evidence. You can explore the observatory without signing in.</p>
      {session.session?.signed_in ? <div className="station-account"><Status value="LIVE" /><strong>{session.session.station_id}</strong><p>{session.session.roles.map(words).join(' · ')}</p><button className="button secondary" onClick={() => { void session.logout(); }}>Sign out of station</button></div> : loginOpen ? <StationLogin login={session.login} onLoggedIn={() => setLoginOpen(false)} /> : null}
    </dialog>
  </div>;
}

function Observatory() {
  return <><StoryLanding /><section className="story-workspace" id="casework" tabIndex={-1} aria-labelledby="casework-heading"><div className="story-entrybar"><span>Illustration complete · Synthetic teaching data</span><Link to="/workspace">Open full workspace <ArrowUpRight size={18}/></Link></div><Workspace fromStory /></section></>;
}

export function LiveWindow({ expanded = false }: { expanded?: boolean }) {
  const { data } = useCases();
  const live = useLive();
  const run = data.snapshot?.run;
  const tick = live.tick;
  const drone = tick?.payload.vehicles[0];
  const readings = tick?.payload.latest_readings.filter((r) => r.sensor === 'N2').slice(0, 3) ?? [];
  return <div className="live-instrument"><div className="instrument-heading"><span>Field connection</span><Status value={live.status.toUpperCase()} /></div><p className="instrument-run" data-testid="run-state">Run: {run ? `${run.preset_id} (${run.state})` : 'none yet'}</p><div className="instrument-values"><div><span>Simulation time</span><strong data-testid="sim-time">{valueOrUnknown(tick?.sim_time_s, 's', 1)}</strong><Explain what="Elapsed time in this synthetic run, from the server clock." /></div><div><span>Wind from</span><strong>{tick?.payload.wind ? `${tick.payload.wind.from_deg}°` : 'Unknown'}</strong><Explain what={tick?.payload.wind ? `Wind speed ${displayNumber(tick.payload.wind.speed_m_s, 'm/s')}; direction is where it comes from.` : 'No current wind data.'} /></div></div><p className="drone-line" data-testid="drone"><span className="scene-dot" /> Drone: {drone ? `${drone.label} at height ${valueOrUnknown(drone.pose.up, 'm', 1)}, ${drone.stage ?? 'no mission'}, pilot ${drone.pilot}` : 'no vehicle yet'}</p>{expanded && <div className="reading-details">{readings.length ? readings.map((r) => <p key={r.channel}><span>N2 · {r.channel.replace('_', ' / ')}</span><strong>{displayNumber(r.value)}</strong><small>{r.quality.map(words).join(' · ')}</small></p>) : <p>No current channel readings. Unknown is not zero.</p>}</div>}<span className="cursor-note" data-testid="cursor">Last event: {live.cursor ?? data.snapshot?.cursor ?? 'none yet'}</span></div>;
}

function Workspace({fromStory=false}: {fromStory?: boolean}) {
  const { data } = useCases();
  const Heading=fromStory?'h2':'h1';
  return <div className="workspace-layout"><aside className="workspace-sidebar"><span className="sidebar-context">Cases workspace</span><Link className="sidebar-active" to="/workspace"><FolderOpen size={19} />Casebook</Link><Link to="/intake"><Wind size={19} />Screening intake</Link><Link to="/guide"><ShieldCheck size={19} />Evidence guide</Link><div className="sidebar-note"><Leaf size={23} /><p>Every record has a history.<br />Every finding has a scope.</p><span>Synthetic teaching data</span></div></aside><div className="workspace-content"><div className="page-heading"><div><Heading id={fromStory?'casework-heading':undefined}>{fromStory?'Follow the evidence.':'Your casebook.'}</Heading><p>Evidence, connected. Conclusions, considered.</p></div><Link to="/intake" className="button primary">New case <ArrowUpRight size={18} /></Link></div><div className="workspace-summary"><div><span>Cases in this run</span><strong>{data.status === 'ready' ? data.records('case').length : '—'}</strong><Explain what="The number of case records in this server run." /></div><div><span>Air screening records</span><strong>{data.status === 'ready' ? data.records('screening_record').length : '—'}</strong><Explain what="Per-channel air records; these are not inspector specimens." /></div><div><span>Legal suitability</span><strong className="summary-words">Not assessed</strong><Explain what="This is a teaching workflow, not a legal determination." /></div></div><div className="workspace-grid"><section className="casebook-panel"><CaseList /></section><section className="activity-panel" aria-label="Recent automatic actions"><AutomationLog runId={data.snapshot?.run_id} max={5} /></section></div><details className="connection-details"><summary>Live field context</summary><LiveWindow expanded /></details></div></div>;
}

export function CaseList({ compact = false }: { compact?: boolean }) {
  const { data } = useCases();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const records = data.records('case');
  const findings = data.records('finding');
  const visible = records.filter((record) => (filter === 'all' || (filter === 'open' ? record.state !== 'SEALED' : record.state === 'SEALED')) && `${record.id} ${record.purpose} ${record.state}`.toLowerCase().includes(query.toLowerCase()));
  return <>{!compact && <div className="case-toolbar"><div className="filter-controls" aria-label="Filter cases">{['all', 'open', 'sealed'].map((value) => <button key={value} className={value === filter ? 'selected' : ''} aria-pressed={value === filter} onClick={() => setFilter(value)}>{words(value)}</button>)}</div><label className="search-field"><MagnifyingGlass size={17} /><input aria-label="Search cases" placeholder="Search cases" value={query} onChange={(e) => setQuery(e.target.value)} /></label></div>}
    {data.status === 'error' ? <div className="empty-state"><h3>The casebook couldn’t load.</h3><p>{data.error}</p><button className="button secondary" onClick={data.reload}>Try again</button></div> : data.status === 'loading' ? <div className="empty-state" role="status"><h3>Loading the casebook…</h3><p>Fetching the server’s current records.</p></div> : !visible.length ? <div className="empty-state"><FolderOpen size={34} weight="light" /><h3>{query || filter !== 'all' ? 'No cases match this view.' : 'The next investigation starts here.'}</h3><p>{query || filter !== 'all' ? 'Try another search or show all cases.' : 'When air screening records arrive, review their limits and open a case.'}</p><Link className="text-link" to="/intake">Review screening intake <ArrowRight size={18} /></Link></div> : <div className="case-list">{(compact ? visible.slice(0, 3) : visible).map((record) => { const finding = findings.filter((f) => f.case_id === record.id).sort((a, b) => b.revision - a.revision)[0]; const next = record.state === 'SEALED' ? 'exporter' : record.state === 'SPECIMEN' || record.state === 'LAB' ? 'lab_demo' : record.state === 'INTAKE' ? 'inspector_demo' : 'case_reviewer'; return <Link className="case-row" key={record.id} to={caseHref(record)}><span className="case-symbol"><Fingerprint size={25} weight="light" /></span><span className="case-identity"><strong>{record.purpose}</strong><small>Case {shortId(record.id)} · {timeLabel(record.created_utc)}</small></span><span className="case-result"><Status value={finding?.outcome ?? record.state} /><small>Next: {words(next)}</small></span><ArrowUpRight size={20} /></Link>; })}</div>}
  </>;
}

function GuideIcon({index}: {index:number}) {
  const Icon=[Wind,Scan,Clock,Flask,Fingerprint,ShieldCheck][index];
  return <Icon size={23} aria-hidden="true" />;
}

function EvidenceGuide() {
  return <div className="guide-page"><div className="page-heading"><div><h1>Follow the evidence.</h1><p>A strong record keeps different things different.</p></div><Link className="button primary" to="/workspace">Open casework <ArrowUpRight size={18} /></Link></div><div className="guide-intro"><Fingerprint size={68} weight="light" /><p>An air reading is a clue. A specimen is a separate item. A sealed record preserves what was recorded. None of them, alone, establishes a legal conclusion.</p></div><div className="guide-stages">{stages.map((stage,index) => <article key={stage.key}><GuideIcon index={index} /><div><h2>{stage.title}</h2><p>{stage.detail} {stage.key === 'vault' ? 'Integrity, key trust, lineage, trusted time, custody documentation, measurement qualification and legal suitability remain separate checks.' : stage.key === 'finding' ? 'Supported within scope, rejected for use and inconclusive are all normal outcomes.' : stage.key === 'custody' ? 'Add corrections beside the original. Never erase an earlier handover.' : stage.key === 'lab' ? 'A valid result describes this specimen only. It does not establish what caused the plume.' : stage.key === 'intake' ? 'Rejected channels stay visible with their reasons, collection volume and valid seconds.' : 'Use its own ID, seal, container, collection basis and method.'}</p></div></article>)}</div></div>;
}
