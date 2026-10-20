import { useState } from 'react';
import { Button, Explain } from '@fp/shared';
import type { CaseRecord, MediaRecord, SealState } from '@fp/shared';
import { Feedback, lines, useCases, useCasesCommand } from './CasesContext';
import { HumanNumber, humanNumber } from './HumanNumber';

const collectionFields = [
  ['start_sim_s', 'Collection start (sim s)'], ['end_sim_s', 'Collection end (sim s)'],
  ['valid_s', 'Valid collection duration (s)'], ['flow_L_min', 'Recorded flow (L/min)'],
] as const;
const labFields = [
  ['gross_ug', 'Aerosol gross sulfur assay (ug)'], ['blank_ug', 'Aerosol blank sulfur (ug)'],
  ['dup_a_ug', 'Aerosol duplicate A (ug)'], ['dup_b_ug', 'Aerosol duplicate B (ug)'],
  ['spike_added_ug', 'Aerosol spike added (ug)'], ['spike_recovered_ug', 'Aerosol spike recovered (ug)'],
] as const;

export function AerosolCollectionForm({record}: {record: CaseRecord}) {
  const action = useCasesCommand('inspector_demo');
  const [container, setContainer] = useState('');
  const [seal, setSeal] = useState('');
  const [location, setLocation] = useState('');
  const [raw, setRaw] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<'COMPLETE' | 'INTERRUPTED'>('COMPLETE');
  const parsed = collectionFields.map(([key]) => [key, humanNumber(raw[key] ?? '')] as const);
  const incomplete = parsed.some(([, input]) => input.value === null || input.error);
  return <section className="case-panel"><h2>Separate aerosol filter · collection</h2>
    <p>Human-recorded SYNTHETIC_TEACHING inputs for an unclassified aerosol filter. No airborne aerosol capture was simulated.</p>
    <p>Method TEACH-AEROSOL-S-1 · material SYNTHETIC_UNCLASSIFIED_AEROSOL · size UNCLASSIFIED.</p>
    <label>Aerosol container<input maxLength={256} value={container} onChange={e => setContainer(e.target.value)}/></label>
    <label>Aerosol seal identifier<input maxLength={256} value={seal} onChange={e => setSeal(e.target.value)}/></label>
    <label>Aerosol collection location<input maxLength={256} value={location} onChange={e => setLocation(e.target.value)}/></label>
    <Explain what="Describe the collection location. A factory source label is not accepted. This filter is separate from fuel specimens and makes no PM2.5 or PM10 claim."/>
    {collectionFields.map(([key, label]) => <HumanNumber key={key} label={label} value={raw[key] ?? ''}
      onChange={value => setRaw(previous => ({...previous, [key]: value}))}/>)}
    <label>Collection status<select value={status} onChange={e => setStatus(e.target.value as typeof status)}>
      <option value="COMPLETE">COMPLETE</option><option value="INTERRUPTED">INTERRUPTED</option>
    </select></label>
    <Explain what="Enter the recorded simulation times, valid collection seconds and flow. The server calculates effective volume and checks collection quality. Blank entries are unknown, never zero."/>
    <Button disabled={action.disabled || record.state === 'SEALED' || incomplete || !container.trim() || !seal.trim() || !location.trim()}
      pending={action.pending} onClick={() => void action.send('create_media', {
        case_id: record.id, media_type: 'FILTER', material: 'SYNTHETIC_UNCLASSIFIED_AEROSOL', method_id: 'TEACH-AEROSOL-S-1',
        container, seal_id: seal, location_label: location,
        collection: {...Object.fromEntries(parsed.map(([key, input]) => [key, input.value])), status},
      }, record.revision)}>Record aerosol filter</Button>
    <Feedback action={action}/>
  </section>;
}

export function AerosolMedia({media}: {media: MediaRecord}) {
  return <>
    <section className="case-panel"><h2>Saved aerosol filter {media.id}</h2>
      <p>{media.location_label} · {media.material} · {media.size_class} · revision {media.revision}</p>
      <p>{media.input_basis}</p><p>Field link: {media.attempt_ref_reason}</p>
      <p>Server effective volume: {media.collection_computed.volume_L ?? 'unknown'} L · {media.collection_computed.volume_m3 ?? 'unknown'} m³</p>
      <ul>{media.collection_reasons.map((reason, i) => <li key={i}>{reason}</li>)}</ul>
      <Explain what="Volume and collection checks come from the saved server record. This filter is not linked to a drone capture; it does not establish which source caused a plume."/>
      <details><summary>Saved collection inputs and decimal calculations</summary><pre>{JSON.stringify({collection: media.collection, calculations: media.collection_computed}, null, 2)}</pre></details>
    </section>
    <AerosolCustody media={media}/><AerosolLab media={media}/>
  </>;
}

function AerosolCustody({media}: {media: MediaRecord}) {
  const {snapshot, session} = useCases();
  const action = useCasesCommand(session.can('inspector_demo') ? 'inspector_demo' : 'lab_demo');
  const [holder, setHolder] = useState('');
  const [time, setTime] = useState('');
  const [seal, setSeal] = useState<SealState>('INTACT');
  const [exceptions, setExceptions] = useState('');
  return <section className="case-panel"><h2>Aerosol filter · custody</h2><p>Current holder: {media.current_holder}</p>
    <label>Aerosol transfer to human holder<input value={holder} onChange={e => setHolder(e.target.value)}/></label>
    <label>Aerosol event time UTC<input placeholder="2026-10-20T10:00:00Z" value={time} onChange={e => setTime(e.target.value)}/></label>
    <label>Aerosol seal state<select value={seal} onChange={e => setSeal(e.target.value as SealState)}>{['INTACT', 'BROKEN', 'MISSING'].map(value => <option key={value}>{value}</option>)}</select></label>
    <label>Aerosol custody exceptions (one code per line)<textarea value={exceptions} onChange={e => setExceptions(e.target.value)}/></label>
    <Explain what="Record a real hand-over in this teaching workflow. Exceptions include BROKEN_SEAL, MISSING_SEAL, RECIPIENT_ABSENT, REFUSED, LATE_TRANSFER and CORRECTION. A correction cannot silently transfer possession."/>
    <Button disabled={action.disabled || !holder.trim() || !time.trim()} pending={action.pending}
      onClick={() => void action.send('append_media_custody', {media_id: media.id, to_holder: holder, seal_state: seal, event_time_utc: time, exceptions: lines(exceptions)}, media.revision)}>Append aerosol custody event</Button>
    <ul>{snapshot.records('media_custody_event').filter(event => event.media_id === media.id).map(event => <li key={event.id}>
      {event.event_time_utc} · {event.from_holder ?? 'Collection'} → {event.to_holder} · {event.seal_state} · {event.exceptions.join(', ')}
    </li>)}</ul><Feedback action={action}/>
  </section>;
}

function AerosolLab({media}: {media: MediaRecord}) {
  const {snapshot} = useCases();
  const action = useCasesCommand('lab_demo');
  const [raw, setRaw] = useState<Record<string, string>>({});
  const parsed = labFields.map(([key]) => [key, humanNumber(raw[key] ?? '')] as const);
  return <section className="case-panel"><h2>Aerosol filter · sulfur lab inputs</h2>
    <p>Direct sulfur-assay masses in micrograms, entered by a person. These are separate from fuel percentages and bulk filter weighing.</p>
    {labFields.map(([key, label]) => <HumanNumber key={key} label={label} value={raw[key] ?? ''} onChange={value => setRaw(previous => ({...previous, [key]: value}))}/>)}
    <Explain what="Blank lab inputs are unknown. The server computes blank-corrected mass, concentration, QC and reporting limits. A result below the reporting limit stays limited; it is never changed to zero."/>
    <Button disabled={action.disabled || parsed.some(([, input]) => input.error)} pending={action.pending}
      onClick={() => void action.send('request_media_lab', {media_id: media.id, method_id: 'TEACH-AEROSOL-S-1', qc_inputs: Object.fromEntries(parsed.map(([key, input]) => [key, input.value]))}, media.revision)}>Request aerosol lab evaluation</Button>
    <Feedback action={action}/>
    {snapshot.records('media_lab_result').filter(result => result.media_id === media.id).map(result => <article key={result.id}>
      <h3>Saved aerosol result {result.id}</h3><p>QC {result.qc_status} · reporting {result.reporting ?? 'unknown'} · use {result.result_use}</p>
      <p>{result.reporting_reason}</p><ul>{[...result.qc_reasons, ...result.diagnostics, ...result.limitations].map((reason, i) => <li key={i}>{reason}</li>)}</ul>
      <dl>{Object.entries(result.computed).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value ?? 'unknown; see saved reason'}</dd></div>)}</dl>
      <p>Hold time: {result.hold_time_s ?? 'unknown'} s · {result.hold_time_reason}</p>
      <dl>{Object.entries(result.qc_checks).map(([check, detail]) => <div key={check}><dt>{check}</dt><dd>{detail.status} · {detail.reasons.join('; ')}</dd></div>)}</dl>
      <details><summary>Saved aerosol raw values, decimal results and reasons</summary><pre>{JSON.stringify({raw: result.raw, computed_decimal: result.computed_decimal, calculation_precision: result.calculation_precision, computed_reasons: result.computed_reasons}, null, 2)}</pre></details>
      <Explain what="The saved QC and use decision describe only this synthetic filter. A person still decides the finding; usable lab QC cannot establish plume causation or legal suitability."/>
    </article>)}
  </section>;
}
