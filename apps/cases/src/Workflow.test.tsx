import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveConnection } from '@fp/shared';
import type { Snapshot } from '@fp/shared';
import caseFixture from '../../shared/fixtures/case.json';
import specimenFixture from '../../shared/fixtures/specimen.json';
import labFixture from '../../shared/fixtures/lab_result.json';
import exportFixture from '../../shared/fixtures/export.json';
import verificationFixture from '../../shared/fixtures/verification_report.json';
import screeningFixture from '../../shared/fixtures/screening_record.json';
import { App } from './App';

class Socket {
  static last: Socket;
  readyState = 1;
  onopen: (() => void) | null = null;
  onmessage: ((m: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  constructor(readonly url: string) { Socket.last = this; }
  close() { this.readyState = 3; }
}
let roles: string[];
let payloads: Record<string, unknown>[];
let snapshot: Snapshot;
const receipt = { receipt_id: 'receipt', command_id: 'command', status: 'REJECTED', reason_code: 'NOT_IMPLEMENTED', reason_text: 'This action is not available on this server yet.', record_refs: [], actor: 'laptop-4', automatic: false };

beforeEach(() => {
  roles = ['case_reviewer', 'lab_demo', 'exporter', 'inspector_demo'];
  payloads = [];
  snapshot = { schema_version: 'fp/1', server_epoch: 'epoch', mode: 'live', cursor: 1, run_id: caseFixture.run_id, run: {id:caseFixture.run_id,state:'PAUSED',state_reason:null}, latest_readings: [], records: { case: [caseFixture], specimen: [specimenFixture], lab_result: [labFixture], export: [exportFixture], verification_report: [verificationFixture], screening_record: Array.isArray(screeningFixture) ? screeningFixture : [screeningFixture] } } as unknown as Snapshot;
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/api/commands')) { payloads.push(JSON.parse(String(options?.body))); return new Response(JSON.stringify(receipt)); }
    const body = url.includes('/api/snapshot') ? snapshot : url.includes('/api/session/stations') ? [] : url.includes('/api/session') ? { signed_in: true, station_id: 'laptop-4', roles } : [];
    return new Response(JSON.stringify(body));
  }));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); });

async function mount(path: string) {
  window.history.replaceState({}, '', `/cases${path}`);
  const connection = new LiveConnection({ socketFactory: (url) => new Socket(url) as unknown as WebSocket });
  render(<App connection={connection} />);
  act(() => {
    Socket.last.onopen?.();
    Socket.last.onmessage?.({data:JSON.stringify({schema_version:'fp/1',server_epoch:'epoch',message_type:'hello',run_id:caseFixture.run_id,cursor:1,sim_time_s:20,payload:{mode:snapshot.mode}})});
    Socket.last.onmessage?.({ data: JSON.stringify({ schema_version: 'fp/1', server_epoch: 'epoch', message_type: 'tick', run_id: caseFixture.run_id, cursor: 1, sim_time_s: 20, payload: { vehicles: [], wind: null, latest_readings: [], time_scale: 1 } }) });
  });
  await waitFor(() => expect(screen.getByTestId('live-badge').textContent).toContain('Live'));
}

describe('Cases evidence workflow', () => {
  it('keeps replay commands read-only even with a signed-in lab role',async()=>{
    snapshot.mode='replay';
    await mount(`/specimens/${specimenFixture.id}/lab`);
    const button=await screen.findByRole('button',{name:/Request lab check/});
    expect(button.matches(':disabled')).toBe(true);
    expect(screen.getByText(/Replay is read-only/)).toBeTruthy();
    fireEvent.click(button);
    expect(payloads).toHaveLength(0);
  });
  it('keeps a known failed QC check visible while the overall result is pending',async()=>{
    snapshot.records.lab_result=[{...labFixture,qc_status:'PENDING',qc_reasons:['BLANK_ABOVE_LIMIT','RECOVERY_UNDEFINED'],qc_checks:{blank:{status:'FAIL',reasons:['BLANK_ABOVE_LIMIT']},recovery:{status:'PENDING',reasons:['RECOVERY_UNDEFINED']}}}] as unknown as Snapshot['records']['lab_result'];
    await mount(`/specimens/${specimenFixture.id}/lab`);
    await screen.findByText('Server result');
    expect(screen.getByText('Fail')).toBeTruthy();
    expect(screen.getAllByText('Pending').length).toBeGreaterThan(0);
    expect(payloads).toHaveLength(0);
  });
  it('shows signature validity separately from independent trust and offers the real ZIP endpoint',async()=>{
    const original=snapshot.records.export![0];
    snapshot.records.export=[{...original,state:'SEALED',signing_policy:'SIGN_REQUIRED',signature:{algorithm:'Ed25519',path:'signature.json',length:50,sha256:'a'.repeat(64),key_fingerprint_sha256:'b'.repeat(64)}}];
    snapshot.records.verification_report=[{...verificationFixture,export_id:original.id,signature_check:{validity:'VALID',trust:'UNTRUSTED',trust_source:'NONE',key_fingerprint_sha256:'b'.repeat(64),reason:'No external trust anchor supplied.'}}] as unknown as Snapshot['records']['verification_report'];
    await mount(`/${caseFixture.id}/vault`);
    await screen.findByText('Signature validity:');
    expect(screen.getByText('External key trust:')).toBeTruthy();
    expect(screen.getByText('Untrusted')).toBeTruthy();
    expect(screen.getByRole('link',{name:'Download original ZIP'}).getAttribute('href')).toContain(`/api/exports/${original.id}/pack.zip?run_id=`);
    expect(payloads).toHaveLength(0);
  });
  it('steps through a sealed case history without issuing commands',async()=>{
    snapshot.records.case=[{...caseFixture,state:'SEALED',revision:1}];
    await mount(`/${caseFixture.id}/replay`);
    await screen.findByRole('heading',{name:'The saved case, step by step.'});
    fireEvent.click(screen.getByRole('button',{name:'Next record'}));
    expect((screen.getByRole('slider',{name:'History position'}) as HTMLInputElement).value).toBe('1');
    fireEvent.click(screen.getByRole('button',{name:'Previous record'}));
    expect((screen.getByRole('slider',{name:'History position'}) as HTMLInputElement).value).toBe('0');
    expect(payloads).toHaveLength(0);
  });
  it('closes mobile navigation with Escape and restores focus to its trigger',async()=>{
    await mount('/workspace');
    await act(async()=>{await Promise.resolve();});
    fireEvent.click(screen.getByRole('button',{name:'Open menu'}));
    const close=screen.getByRole('button',{name:'Close menu'});
    expect(close.getAttribute('aria-expanded')).toBe('true');
    fireEvent.keyDown(screen.getByRole('navigation',{name:'Main navigation'}),{key:'Escape'});
    const open=screen.getByRole('button',{name:'Open menu'});
    expect(open.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(open);
    expect(payloads).toHaveLength(0);
  });
  it('reveals the actual casebook after the story without sending mission or capture commands',async()=>{
    await mount('/');
    await within(screen.getByRole('region',{name:'Follow the evidence.'})).findByRole('heading',{name:'Follow the evidence.',level:2});
    await screen.findByText(caseFixture.purpose);
    expect(screen.getByRole('img',{name:/Conceptual trace/})).toBeTruthy();
    expect(screen.getByText(/No investigation, proposal or mission record is created/)).toBeTruthy();
    expect(screen.getByText(/not results from this illustration/)).toBeTruthy();
    expect(screen.getByRole('link',{name:'Enter Cases workspace'}).getAttribute('href')).toBe('#casework');
    const search=screen.getByRole('textbox',{name:'Search cases'});
    fireEvent.change(search,{target:{value:'no matching case'}});
    expect(screen.getByText('No cases match this view.')).toBeTruthy();
    fireEvent.change(search,{target:{value:''}});
    expect(screen.getByText(caseFixture.purpose)).toBeTruthy();
    expect(payloads).toHaveLength(0);
    fireEvent.click(screen.getByRole('link',{name:/New case/}));
    await screen.findByRole('heading',{name:'Air screening intake.'});
    expect(payloads).toHaveLength(0);
  });
  it('shows seven separate verification meanings and keeps legal suitability unassessed', async () => {
    await mount(`/${caseFixture.id}/vault`);
    await screen.findByText('Seven checks. Seven meanings.');
    expect(screen.getByText('Byte integrity')).toBeTruthy();
    expect(screen.getByText('Key trust')).toBeTruthy();
    expect(screen.getByText('Record lineage')).toBeTruthy();
    expect(screen.getByText('Trusted time')).toBeTruthy();
    expect(screen.getByText('Custody record')).toBeTruthy();
    expect(screen.getByText('Measurement scope')).toBeTruthy();
    expect(screen.getByText('Legal suitability')).toBeTruthy();
    expect(screen.getByText('Not assessed')).toBeTruthy();
  });
  it('fills lab teaching inputs but sends maths to the server and surfaces its rejection', async () => {
    await mount(`/specimens/${specimenFixture.id}/lab`);
    fireEvent.click(await screen.findByRole('button', { name: 'Valid example' }));
    expect((screen.getByLabelText('Gross sulphur (µg)') as HTMLInputElement).value).toBe('2010');
    fireEvent.click(screen.getByRole('button', { name: /Request lab check/ }));
    await screen.findByText(receipt.reason_text);
    expect(payloads).toHaveLength(1);
    expect(payloads[0]).toMatchObject({ command_type: 'request_lab', expected_revision: specimenFixture.revision, run_id: caseFixture.run_id, payload: { specimen_id: specimenFixture.id, qc_inputs: { gross_ug: 2010, blank_ug: 10, dup_a_pct: 2, dup_b_pct: 2.2, spike_added_ug: 100, spike_recovered_ug: 90 } } });
    expect(JSON.stringify(payloads[0])).not.toContain('percent_w_w');
    expect(screen.queryByText('Recorded by the server.')).toBeNull();
  });
  it('prevents a station without a lab role from submitting a lab command', async () => {
    roles = ['case_reviewer'];
    await mount(`/specimens/${specimenFixture.id}/lab`);
    const button = await screen.findByRole('button', { name: /Request lab check/ });
    expect(button.matches(':disabled')).toBe(true);
    fireEvent.click(button);
    expect(payloads).toHaveLength(0);
  });
  it('reuses the command ID when the same interrupted action is retried', async () => {
    const previousFetch = globalThis.fetch;
    vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
      if (url.includes('/api/commands')) {
        payloads.push(JSON.parse(String(options?.body)));
        return new Response(JSON.stringify({ ...receipt, status: 'INTERRUPTED', reason_code: 'NO_CONNECTION', reason_text: 'No reply was received.' }));
      }
      return previousFetch(url, options);
    }));
    await mount(`/specimens/${specimenFixture.id}/lab`);
    fireEvent.click(await screen.findByRole('button', { name: 'Valid example' }));
    fireEvent.click(screen.getByRole('button', { name: /Request lab check/ }));
    await screen.findByText('The connection was interrupted.');
    fireEvent.click(screen.getByRole('button', { name: /Request lab check/ }));
    await waitFor(() => expect(payloads).toHaveLength(2));
    expect(payloads[0].command_id).toBe(payloads[1].command_id);
  });
  it('keeps rejected channels visible and distinguishes a real zero from unknown', async () => {
    const first = snapshot.records.screening_record![0];
    snapshot.records.screening_record = [{ ...first, decision: 'REJECTED', reasons: ['FLOW_FAULT'], summary: { valid_seconds: 0, volume_L: null } }];
    await mount('/intake');
    await screen.findByText('Flow fault');
    expect(screen.getByText('Rejected')).toBeTruthy();
    expect(screen.getByText('0.0 s')).toBeTruthy();
    expect(screen.getByText('Unknown')).toBeTruthy();
  });
  it('disables a live lab form when its connection becomes stale', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await mount(`/specimens/${specimenFixture.id}/lab`);
    const button = await screen.findByRole('button', { name: /Request lab check/ });
    await waitFor(() => expect(button.matches(':disabled')).toBe(false));
    act(() => { vi.advanceTimersByTime(3100); });
    expect(button.matches(':disabled')).toBe(true);
    expect(screen.getByTestId('stale-banner')).toBeTruthy();
  });
});
