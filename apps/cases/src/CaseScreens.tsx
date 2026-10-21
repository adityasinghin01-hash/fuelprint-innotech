import { cloneElement, isValidElement, useEffect, useId, useRef, useState } from 'react';
import type { FormEvent, ReactElement, ReactNode } from 'react';
import { ArrowLeft, ArrowRight, ArrowUpRight, Clock, Fingerprint, Flask, ShieldCheck, WarningCircle } from './icons';
import { Explain, getJson, newCommandId, useCommand } from '@fp/shared';
import type { CaseRecord, FindingOutcome, LabResultRecord, Receipt, RecordBase, ScreeningRecord } from '@fp/shared';
import { Link, NavLink, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useCases } from './CasesContext';
import { buildTimeline, caseHref, caseStages, displayNumber, reference, shortId, timeLabel, words } from './caseData';
import { CaseReplay } from './CaseReplay';
import { EvidenceVaultScreen as VaultScreen } from './EvidenceVaultScreen';
import { AerosolCollectionForm, AerosolMedia } from './AerosolScreen';

export function Status({ value }: { value: string }) {
  const tone = /REJECT|FAIL|BROKEN|MISSING/.test(value) ? 'negative' : /^(PASS|VALID|LIVE|SUPPORTED_WITHIN_SCOPE|SEALED)$/.test(value) ? 'positive' : 'neutral';
  return <span className={`status-tag ${tone}`}><span />{words(value)}</span>;
}

function Note({ children, warning = false }: { children: ReactNode; warning?: boolean }) {
  return <div className={warning ? 'scope-note warning' : 'scope-note'}>{warning ? <WarningCircle size={20} /> : <ShieldCheck size={20} />}<p>{children}</p></div>;
}

export function useCaseCommand(role: string) {
  const { data, session, readOnly, readOnlyReason } = useCases();
  const command = useCommand();
  const retry = useRef<{ signature: string; commandId: string } | null>(null);
  const allowed = session.status === 'ready' && session.session?.signed_in === true && session.can(role);
  const blocked = readOnly || !allowed || command.pending;
  const reason = readOnly ? readOnlyReason : !allowed ? `Sign in to a station with the ${words(role).toLowerCase()} role to make this change.` : null;
  async function send(type: string, payload: object, revision: number | null = null) {
    if (blocked) return null;
    const signature = JSON.stringify([type, payload, revision, data.snapshot?.run_id]);
    if (retry.current?.signature !== signature) retry.current = { signature, commandId: newCommandId() };
    const result = await command.send(type, payload, revision, { runId: data.snapshot?.run_id, commandId: retry.current.commandId });
    if (result.status === 'APPLIED' || result.status === 'REJECTED') retry.current = null;
    if (result.status === 'APPLIED' || result.reason_code === 'STALE_REVISION') data.reload();
    return result;
  }
  return { ...command, blocked, reason, send };
}

export function CommandFeedback({ receipt }: { receipt: Receipt | null }) {
  if (!receipt) return null;
  return <div className={`command-feedback ${receipt.status === 'APPLIED' ? 'success' : ''}`} role="status"><strong>{receipt.status === 'APPLIED' ? 'Recorded by the server.' : receipt.reason_code === 'STALE_REVISION' ? 'The record changed. Review the latest version.' : receipt.status === 'INTERRUPTED' ? 'The connection was interrupted.' : 'The server could not apply this change.'}</strong><p>{receipt.reason_text ?? (receipt.status === 'APPLIED' ? 'The new record is part of the case history.' : 'Nothing is known to be saved. Check the record before retrying.')}</p>{receipt.reason_code && <small>{words(receipt.reason_code)}</small>}</div>;
}

function Field({ label, children, help }: { label: string; children: ReactNode; help?: string }) {
  const id = useId();
  return <div className="form-field"><label htmlFor={id}>{label}</label>{isValidElement(children) ? cloneElement(children as ReactElement<{ id?: string }>, { id }) : children}{help && <Explain what={help} />}</div>;
}

function Timeline({ record }: { record: CaseRecord }) {
  const { data } = useCases();
  const [history, setHistory] = useState<{ type: string; record: RecordBase }[]>([]);
  const [historyError, setHistoryError] = useState(false);
  const targets = [{ type: 'case', record }, ...data.records('specimen').filter((s) => s.case_id === record.id).map((s) => ({ type: 'specimen', record: s }))];
  const revisionKey = targets.map((item) => `${item.type}/${item.record.id}/${item.record.revision}`).join('|');
  useEffect(() => {
    let cancelled = false;
    setHistory([]); setHistoryError(false);
    const requests = targets.flatMap((item) => Array.from({ length: Math.min(item.record.revision - 1, 99) }, (_, index) =>
      getJson<RecordBase>(`/api/records/${item.type}/${item.record.id}?revision=${index + 1}`).then((old) => ({ type: item.type, record: old }))));
    void Promise.allSettled(requests).then((results) => { if (!cancelled) { setHistory(results.flatMap((result) => result.status === 'fulfilled' ? [result.value] : [])); setHistoryError(results.some((result) => result.status === 'rejected')); } });
    return () => { cancelled = true; };
  }, [revisionKey]);
  const events = buildTimeline(data.snapshot, record, history);
  return <aside className="case-timeline"><div className="timeline-heading"><Clock size={19} /><h2>The case, in sequence.</h2></div><Explain what="Public records and prior case/specimen revisions in event-time order. Corrections stay beside their original entry." />{historyError && <p>Some older revisions could not load. Current records remain visible.</p>}<ol>{events.map((event) => <li key={`${event.id}-${event.revision}-${event.title}`} className={event.correctionOf ? 'is-correction' : ''}><span className="timeline-marker" /><div><strong>{event.correctionOf ? 'Correction: ' : ''}{event.title}</strong><p>{event.detail}</p><time dateTime={event.at}>{timeLabel(event.at)}</time><small>{event.automatic ? 'Done automatically' : words(event.actor)}</small></div></li>)}</ol></aside>;
}

export function CaseDetail() {
  const { caseId } = useParams();
  const { data } = useCases();
  const location = useLocation();
  const record = data.record('case', caseId);
  const active = location.pathname.split('/').slice(2).join('/') || 'intake';
  if (!record) return <Missing title="Case unavailable" text={data.status === 'loading' ? 'Loading this case from the server…' : 'This case is not in the loaded run. Open the casebook to choose a current record.'} />;
  const specimen = data.records('specimen').filter((s) => s.case_id === record.id).at(-1);
  return <div className="case-detail"><Link className="back-link" to="/workspace"><ArrowLeft size={17} /> Casebook</Link><div className="case-heading"><div><span className="case-shortcode">Case {shortId(record.id)}</span><h1>{record.purpose}</h1><p>{words(record.state)} · Revision {record.revision} · Synthetic teaching data</p></div><Status value={record.state} /></div><nav className="stage-navigation" aria-label="Case stages">{caseStages.map((stage) => <NavLink key={stage.key} to={caseHref(record, stage.key)} className={active === stage.key ? 'active' : ''}>{stage.title}<ArrowUpRight size={14} /></NavLink>)}</nav><div className="case-work-grid"><div className="case-work-main">
    {active === 'intake' ? <IntakeScreen record={record} embedded /> : active === 'specimens/new' ? <SpecimenScreen record={record} /> : active === 'custody' ? specimen ? <CustodyScreen selectedId={specimen.id} embedded /> : <Missing inline title="No inspector specimen yet" text="Register a separate specimen before recording its custody." /> : active === 'lab' ? specimen ? <LabScreen selectedId={specimen.id} embedded /> : <Missing inline title="A specimen comes first" text="An air screening record cannot be used as the inspector’s specimen." /> : active === 'finding' ? <FindingScreen record={record} /> : active === 'vault' ? <VaultScreen record={record} /> : active === 'replay' ? <CaseReplay record={record} /> : active === 'aerosol' ? <><AerosolCollectionForm record={record} />{data.records('media').filter(item => item.case_id === record.id).map(item => <AerosolMedia key={item.id} media={item} />)}</> : <Missing inline title="Choose an evidence stage" text="Use the case navigation to review its records." />}
    </div><Timeline record={record} /></div></div>;
}

export function Missing({ title, text, inline = false }: { title: string; text: string; inline?: boolean }) {
  return <div className={inline ? 'empty-state' : 'missing-page empty-state'}>{inline ? <h2>{title}</h2> : <h1>{title}</h1>}<p>{text}</p>{!inline && <Link className="text-link" to="/workspace">Return to casebook <ArrowRight size={18} /></Link>}</div>;
}

function ScreeningItem({ record, selected, toggle }: { record: ScreeningRecord; selected?: boolean; toggle?: () => void }) {
  return <article className="screening-item"><div className="record-heading"><div>{toggle && <input type="checkbox" aria-label={`Select ${record.channel} screening ${shortId(record.id)}`} checked={selected} onChange={toggle} />}<strong>{record.channel.replace('_', ' / ')}</strong><span>Air screening · {shortId(record.id)}</span></div><Status value={record.decision} /></div><div className="record-measures"><div><span>Valid collection</span><strong>{displayNumber(record.summary.valid_seconds, 's')}</strong></div><div><span>Sample volume</span><strong>{displayNumber(record.summary.volume_L, 'L', 3)}</strong></div></div><Explain what="Valid seconds and collected volume reported for this channel by Field." /><p>{record.reasons.length ? record.reasons.map(words).join(' · ') : 'No rejection reasons reported.'}</p></article>;
}

export function IntakeScreen({ record, embedded = false }: { record?: CaseRecord; embedded?: boolean }) {
  const { data } = useCases();
  const command = useCaseCommand('case_reviewer');
  const navigate = useNavigate();
  const [selected, setSelected] = useState<string[]>([]);
  const [purpose, setPurpose] = useState('');
  const screening = data.records('screening_record').filter((item) => !record || record.screening_record_ids.includes(item.id));
  const investigation = record ? data.record('investigation',record.investigation_id) : null;
  const analysis = data.records('analysis').filter(item => !record || investigation?.analysis_ids.includes(item.id)).sort((a,b) => Date.parse(b.created_utc)-Date.parse(a.created_utc))[0];
  useEffect(() => { setSelected([]); }, [data.snapshot?.run_id]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    const result = await command.send('create_case', { screening_record_ids: selected, purpose });
    const ref = result?.record_refs.find((item) => item.type === 'case');
    if (ref) navigate(`/${ref.id}/intake?run=${data.snapshot?.run_id ?? ''}`);
  }
  return <section className={embedded ? 'evidence-screen' : 'intake-page evidence-screen'}><div className="screen-title"><WindMark /><div>{embedded ? <h2>Start with the air reading.</h2> : <h1>Air screening intake.</h1>}<p>Every channel, including the ones rejected for use.</p></div></div><Note>This is an air reading from the drone. It is not a specimen.</Note>
    {data.status === 'error' ? <Note warning>{data.error ?? 'Screening records could not load.'} <button className="text-button" onClick={data.reload}>Retry</button></Note> : !screening.length ? <Missing inline title={data.status === 'loading' ? 'Loading air screening…' : 'Waiting for a reviewed capture'} text="Field will send a separate screening record for each channel. Missing measurements are never treated as zero." /> : <div className="screening-list">{screening.map((item) => <ScreeningItem key={item.id} record={item} selected={selected.includes(item.id)} toggle={!record ? () => setSelected((list) => list.includes(item.id) ? list.filter((id) => id !== item.id) : [...list, item.id]) : undefined} />)}</div>}
    {record && <p className="reference-line">Investigation {shortId(record.investigation_id)} · <a href={`/operations/?investigation_id=${encodeURIComponent(record.investigation_id)}`}>Open in Operations <ArrowUpRight size={14} /></a></p>}
    {analysis && <div className="analysis-context"><h3>Current analysis</h3><p>{words(analysis.outcome)}</p><Explain what="The latest analysis in this run, supplied by the server. This is model support, not a fuel identification." /></div>}
    {!record && <form onSubmit={submit} className="evidence-form"><h2>Open a case.</h2><Field label="Investigation purpose"><textarea aria-label="Investigation purpose" value={purpose} onChange={(e) => setPurpose(e.target.value)} required placeholder="What needs investigating, and why?" /></Field>{command.reason && <p className="permission-note">{command.reason}</p>}<button className="button primary" disabled={command.blocked || !selected.length || !purpose.trim()}>{command.pending ? 'Requesting case…' : 'Create case'}<ArrowUpRight size={18} /></button><Explain what="The server checks the selected record IDs and returns a command receipt." /><CommandFeedback receipt={command.receipt} /></form>}
    {record && <Link className="button secondary" to={caseHref(record, 'specimens/new')}>Continue to inspector specimen <ArrowRight size={18} /></Link>}
  </section>;
}

function WindMark() { return <Fingerprint size={34} weight="light" />; }

function SpecimenScreen({ record }: { record: CaseRecord }) {
  const { data } = useCases();
  const command = useCaseCommand('inspector_demo');
  const [site, setSite] = useState('S01');
  const [container, setContainer] = useState('Sealed sample container');
  const [seal, setSeal] = useState('');
  const [mass, setMass] = useState('');
  const specimens = data.records('specimen').filter((s) => s.case_id === record.id);
  async function submit(event: FormEvent) {
    event.preventDefault();
    await command.send('create_specimen', { case_id: record.id, site_id: site, material: 'SYNTHETIC_COAL_COKE_LIKE', method_id: 'TEACH-FUEL-S-1', container, seal_id: seal, mass_mg: Number(mass) }, record.revision);
  }
  return <section className="evidence-screen"><div className="screen-title"><Flask size={32} weight="light" /><div><h2>A specimen of its own.</h2><p>Separate ID. Separate seal. Separate chain of custody.</p></div></div><Note>Inspector workflow demonstration only. Authority basis: Inspector demo. Legal suitability: Not assessed.</Note>
    {specimens.map((specimen) => <div className="specimen-record" key={specimen.id}><div><strong>Specimen {shortId(specimen.id)}</strong><span>{specimen.site_id} · Seal {specimen.seal_id}</span></div><span>{displayNumber(specimen.mass_mg, 'mg')}</span><Link className="text-link" to={`/specimens/${specimen.id}/custody?run=${record.run_id}`}>Custody <ArrowUpRight size={16} /></Link></div>)}
    <form className="evidence-form" onSubmit={submit}><fieldset disabled={command.blocked || record.state === 'SEALED'}><legend>Register an inspector specimen</legend><div className="form-grid"><Field label="Fictional site"><select aria-label="Fictional site" value={site} onChange={(e) => setSite(e.target.value)}>{Array.from({ length: 6 }, (_, i) => `S0${i + 1}`).map((id) => <option key={id}>{id}</option>)}</select></Field><Field label="Mass (mg)" help="The teaching method requires at least 50 mg. The server validates the value."><input aria-label="Mass (mg)" type="number" min="0" step="any" value={mass} onChange={(e) => setMass(e.target.value)} required /></Field><Field label="Container"><input aria-label="Container" value={container} onChange={(e) => setContainer(e.target.value)} required /></Field><Field label="Seal ID"><input aria-label="Seal ID" value={seal} onChange={(e) => setSeal(e.target.value)} required /></Field><Field label="Teaching material"><input aria-label="Teaching material" value="Synthetic coal/coke-like material" readOnly /></Field><Field label="Method"><input aria-label="Method" value="TEACH-FUEL-S-1" readOnly /></Field></div><button className="button primary">{command.pending ? 'Registering…' : 'Register specimen'}<ArrowUpRight size={18} /></button></fieldset>{command.reason && <p className="permission-note">{command.reason}</p>}{record.state === 'SEALED' && <p>This case is sealed. Its records are read-only.</p>}<CommandFeedback receipt={command.receipt} /></form></section>;
}

export function CustodyScreen({ selectedId, embedded = false }: { selectedId?: string; embedded?: boolean }) {
  const { specimenId } = useParams();
  const { data, session } = useCases();
  const specimen = data.record('specimen', selectedId ?? specimenId);
  const command = useCaseCommand(session.can('inspector_demo') ? 'inspector_demo' : 'lab_demo');
  const [holder, setHolder] = useState('');
  const [seal, setSeal] = useState('INTACT');
  const [exception, setException] = useState('');
  const [eventTime, setEventTime] = useState('');
  if (!specimen) return <Missing inline={embedded} title="Specimen unavailable" text="Choose a registered specimen from the case to see its custody history." />;
  const events = data.records('custody_event').filter((e) => e.specimen_id === specimen.id).sort((a, b) => Date.parse(a.event_time_utc) - Date.parse(b.event_time_utc));
  async function submit(event: FormEvent) { event.preventDefault(); if (!specimen) return; await command.send('append_custody', { specimen_id: specimen.id, to_holder: holder, seal_state: seal, event_time_utc: new Date(eventTime).toISOString(), exceptions: exception ? [exception] : [] }, specimen.revision); }
  return <section className="evidence-screen"><div className="screen-title"><ShieldCheck size={32} weight="light" /><div>{embedded ? <h2>Every handover matters.</h2> : <h1>Chain of custody.</h1>}<p>Specimen {shortId(specimen.id)} · Seal {specimen.seal_id}</p></div></div><Note>Current holder: {specimen.current_holder}. A correction adds to the record; it never erases an earlier event.</Note><ol className="custody-chain">{events.map((event) => <li key={event.id}><span className="custody-symbol">{event.seal_state === 'INTACT' ? <ShieldCheck size={23} /> : <WarningCircle size={23} />}</span><div><strong>{event.from_holder ?? 'Collector'} <ArrowRight size={15} /> {event.to_holder}</strong><time>{timeLabel(event.event_time_utc)}</time><p>{event.exceptions.length ? event.exceptions.map(words).join(' · ') : 'No exceptions recorded'}</p></div><Status value={event.seal_state} /></li>)}</ol>
    <form className="evidence-form" onSubmit={submit}><fieldset disabled={command.blocked}><legend>Record a handover or exception</legend><div className="form-grid"><Field label="Recipient / next holder"><input aria-label="Recipient / next holder" required value={holder} onChange={(e) => setHolder(e.target.value)} /></Field><Field label="Seal state"><select aria-label="Seal state" value={seal} onChange={(e) => setSeal(e.target.value)}>{['INTACT', 'BROKEN', 'MISSING'].map((value) => <option key={value} value={value}>{words(value)}</option>)}</select></Field><Field label="Handover time" help="Local time is converted to UTC for the record."><input type="datetime-local" aria-label="Handover time" required value={eventTime} onChange={(e) => setEventTime(e.target.value)} /></Field><Field label="Exception"><select aria-label="Exception" value={exception} onChange={(e) => setException(e.target.value)}><option value="">No exception</option>{['BROKEN_SEAL', 'MISSING_SEAL', 'RECIPIENT_ABSENT', 'REFUSED', 'LATE_TRANSFER', 'CORRECTION'].map((value) => <option value={value} key={value}>{words(value)}</option>)}</select></Field></div><button className="button primary">{command.pending ? 'Recording…' : 'Append custody event'}<ArrowUpRight size={18} /></button></fieldset>{command.reason && <p className="permission-note">{command.reason}</p>}<CommandFeedback receipt={command.receipt} /></form></section>;
}

const qcFields = [{ key: 'gross_ug', label: 'Gross sulphur (µg)' }, { key: 'blank_ug', label: 'Blank (µg)' }, { key: 'dup_a_pct', label: 'Duplicate A (% w/w)' }, { key: 'dup_b_pct', label: 'Duplicate B (% w/w)' }, { key: 'spike_added_ug', label: 'Spike added (µg)' }, { key: 'spike_recovered_ug', label: 'Spike recovered (µg)' }] as const;
const validExample = { gross_ug: '2010', blank_ug: '10', dup_a_pct: '2.0', dup_b_pct: '2.2', spike_added_ug: '100', spike_recovered_ug: '90' };

export function LabScreen({ selectedId, embedded = false }: { selectedId?: string; embedded?: boolean }) {
  const { specimenId } = useParams();
  const { data } = useCases();
  const specimen = data.record('specimen', selectedId ?? specimenId);
  const command = useCaseCommand('lab_demo');
  const [inputs, setInputs] = useState<Record<string, string>>(Object.fromEntries(qcFields.map((field) => [field.key, ''])));
  if (!specimen) return <Missing inline={embedded} title="Choose a specimen" text="Laboratory results describe a registered inspector specimen, not the drone air screening." />;
  const result = data.records('lab_result').filter((r) => r.specimen_id === specimen.id).sort((a, b) => b.revision - a.revision)[0];
  async function submit(event: FormEvent) { event.preventDefault(); if (!specimen) return; const qc_inputs = Object.fromEntries(qcFields.map(({ key }) => [key, inputs[key].trim() === '' ? null : Number(inputs[key])])); await command.send('request_lab', { specimen_id: specimen.id, method_id: specimen.method_id, qc_inputs }, specimen.revision); }
  return <section className="evidence-screen"><div className="screen-title"><Flask size={32} weight="light" /><div>{embedded ? <h2>Quality before conclusions.</h2> : <h1>Laboratory review.</h1>}<p>Specimen {shortId(specimen.id)} · {specimen.method_id}</p></div></div><Note>Synthetic teaching method. A valid result describes this specimen only. It does not show that this material caused the plume.</Note><form className="evidence-form" onSubmit={submit}><div className="example-actions"><span>Fill teaching inputs</span><button type="button" className="text-button" onClick={() => setInputs(validExample)}>Valid example</button><button type="button" className="text-button" onClick={() => setInputs({ ...validExample, blank_ug: '30' })}>Blank fails</button></div><fieldset disabled={command.blocked}><legend>Laboratory inputs</legend><div className="form-grid">{qcFields.map(({ key, label }) => <Field key={key} label={label}><input type="number" step="any" aria-label={label} value={inputs[key]} onChange={(e) => setInputs((values) => ({ ...values, [key]: e.target.value }))} /></Field>)}</div><button className="button primary">{command.pending ? 'Requesting lab result…' : 'Request lab check'}<ArrowUpRight size={18} /></button></fieldset>{command.reason && <p className="permission-note">{command.reason}</p>}<CommandFeedback receipt={command.receipt} /></form>{result ? <LabResult result={result} /> : <Missing inline title="No lab result recorded" text="The server will return the raw inputs, calculations, quality reasons and reporting status after the lab check." />}</section>;
}

function LabResult({ result }: { result: LabResultRecord }) {
  const saved = result as LabResultRecord & { qc_checks?: Record<string, {status:string; reasons:string[]}>; hold_time_s?:number|null; hold_time_reason?:string; computed_reasons?:Record<string,string|null>; reporting_reason?:string };
  const rules = [{ title: 'Specimen mass', limit: 'At least 50 mg', key: 'mass' }, { title: 'Blank', limit: 'At most 20 µg', key: 'blank' }, { title: 'Net measurement', limit: 'Retain the blank-corrected value', key: 'net' }, { title: 'Duplicate agreement', limit: 'RPD at most 20%', key: 'duplicate' }, { title: 'Spike recovery', limit: '80–120%', key: 'recovery' }, { title: 'Hold time', limit: 'At most 300 simulation seconds', key: 'hold' }];
  return <section className="lab-results"><div className="section-heading"><h3>Server result</h3><Status value={result.qc_status} /></div><div className="result-values">{[{ label: 'Sulphur fraction', value: displayNumber(result.computed.percent_w_w, '% w/w', 2) }, { label: 'Duplicate RPD', value: displayNumber(result.computed.rpd_pct, '%', 4) }, { label: 'Spike recovery', value: displayNumber(result.computed.recovery_pct, '%', 1) }].map(({ label, value }) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}</div><Explain what="These calculations and QC decisions are returned by the server. No lab maths runs in this screen." /><p>Reporting: {words(result.reporting)} {saved.reporting_reason} · Hold time used: {displayNumber(saved.hold_time_s, 's')} {saved.hold_time_reason}</p><div className="qc-rules">{rules.map((rule) => <div key={rule.title}><span><strong>{rule.title}</strong><small>{rule.limit}</small></span><span><Status value={saved.qc_checks?.[rule.key]?.status ?? 'NOT_REPORTED'} />{saved.qc_checks?.[rule.key]?.reasons.map(reason=><small key={reason}>{reason}</small>)}</span></div>)}</div>{result.qc_reasons.length > 0 && <Note warning>{result.qc_reasons.map(words).join(' · ')}</Note>}<details className="raw-values"><summary>Raw inputs and net sulphur</summary><dl>{Object.entries(result.raw).map(([key, value]) => <div key={key}><dt>{words(key)}</dt><dd>{displayNumber(value, '', 3)}</dd></div>)}<div><dt>Net sulphur (µg)</dt><dd>{displayNumber(result.computed.net_ug)}</dd></div></dl></details></section>;
}

function FindingScreen({ record }: { record: CaseRecord }) {
  const { data } = useCases();
  const command = useCaseCommand('case_reviewer');
  const [outcome, setOutcome] = useState<FindingOutcome>('INCONCLUSIVE');
  const [limitations, setLimitations] = useState('');
  const [alternatives, setAlternatives] = useState('');
  const [citations, setCitations] = useState<string[]>([]);
  const specimenIds = new Set(data.records('specimen').filter((s) => s.case_id === record.id).map((s) => s.id));
  const mediaIds = new Set(data.records('media').filter(item=>item.case_id===record.id).map(item=>item.id));
  const choices = [...data.records('screening_record').filter((r) => record.screening_record_ids.includes(r.id)).map((r) => ({ record: r, type: 'screening_record', label: `${r.channel} · ${words(r.decision)}` })), ...data.records('lab_result').filter((r) => specimenIds.has(r.specimen_id)).map((r) => ({ record: r, type: 'lab_result', label: `Lab result · ${words(r.qc_status)}` })), ...data.records('media_lab_result').filter(item=>mediaIds.has(item.media_id)).map(item=>({record:item,type:'media_lab_result',label:`Aerosol lab · ${words(item.result_use)}`}))];
  const finding = data.records('finding').filter((f) => f.case_id === record.id).sort((a, b) => b.revision - a.revision)[0];
  const wording = finding ? data.records('wording').find((w) => w.target.id === finding.id && w.target.revision === finding.revision && w.purpose === 'FINDING_SUMMARY') : null;
  async function submit(event: FormEvent) { event.preventDefault(); await command.send('revise_finding', { case_id: record.id, outcome, cited_refs: choices.filter((choice) => citations.includes(choice.record.id)).map((choice) => reference(choice.record, choice.type)), alternatives: alternatives.split('\n').map((line) => line.trim()).filter(Boolean), limitations: limitations.split('\n').map((line) => line.trim()).filter(Boolean) }, record.revision); }
  return <section className="evidence-screen"><div className="screen-title"><Fingerprint size={34} weight="light" /><div><h2>A finding with a scope.</h2><p>The evidence supports a statement. A person signs it.</p></div></div><Note>Legal suitability: Not assessed. This field cannot be changed. Findings are always reviewed by a person.</Note>{finding && <div className="recorded-finding"><h3>Current recorded finding</h3><Status value={finding.outcome} /><h4>Limitations</h4><ul>{finding.limitations.map((limit) => <li key={limit}>{limit}</li>)}</ul>{finding.alternatives.length > 0 && <><h4>Alternatives retained</h4><ul>{finding.alternatives.map((alternative) => <li key={alternative}>{alternative}</li>)}</ul></>}{wording && <div className="ai-wording"><strong>{wording.source === 'LLM' ? 'Plain words by AI (it cannot change any number)' : 'Fixed text · template fallback'}</strong><p>{wording.text}</p>{wording.fallback_reason && <small>{words(wording.fallback_reason)}</small>}</div>}</div>}
    <form className="evidence-form" onSubmit={submit}><fieldset disabled={command.blocked || record.state === 'SEALED'}><legend>Record a new finding revision</legend><Field label="Outcome"><select aria-label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value as FindingOutcome)}>{['SUPPORTED_WITHIN_SCOPE', 'REJECTED_FOR_USE', 'INCONCLUSIVE'].map((value) => <option key={value} value={value}>{words(value)}</option>)}</select></Field><div className="citation-list"><strong>Cite the records</strong>{choices.map((choice) => <label key={choice.record.id}><input type="checkbox" checked={citations.includes(choice.record.id)} onChange={() => setCitations((ids) => ids.includes(choice.record.id) ? ids.filter((id) => id !== choice.record.id) : [...ids, choice.record.id])} /><span>{choice.label}<small>{shortId(choice.record.id)} · Revision {choice.record.revision}</small></span></label>)}</div><Field label="Alternative explanations" help="One per line. Keep alternatives where the analysis is not a provisional single explanation."><textarea aria-label="Alternative explanations" value={alternatives} onChange={(e) => setAlternatives(e.target.value)} /></Field><Field label="Limitations" help="Required. State the scope and what the evidence cannot establish, one limitation per line."><textarea aria-label="Limitations" required value={limitations} onChange={(e) => setLimitations(e.target.value)} /></Field><button className="button primary">{command.pending ? 'Recording finding…' : 'Record finding revision'}<ArrowUpRight size={18} /></button></fieldset>{command.reason && <p className="permission-note">{command.reason}</p>}{record.state === 'SEALED' && <p>This case has been sealed. Earlier findings remain visible.</p>}<CommandFeedback receipt={command.receipt} /></form></section>;
}
