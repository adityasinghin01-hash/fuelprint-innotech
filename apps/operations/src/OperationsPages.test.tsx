import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import type { RecordType, RecordTypes, Role, Snapshot } from '@fp/shared';
import run from '../../shared/fixtures/run.json';
import investigation from '../../shared/fixtures/investigation.json';
import analysis from '../../shared/fixtures/analysis.json';
import proposal from '../../shared/fixtures/proposal.json';
import mission from '../../shared/fixtures/mission.json';
import caseRecord from '../../shared/fixtures/case.json';
import finding from '../../shared/fixtures/finding.json';
import wording from '../../shared/fixtures/wording.json';
import tick from '../../shared/fixtures/tick.json';
import { OperationsContext } from './OperationsContext';
import type { OperationsState } from './OperationsContext';
import { InvestigationPage } from './InvestigationPage';
import { MissionsPage } from './MissionsPage';
import { BoardPage } from './BoardPage';
import { ScenariosPage } from './ScenariosPage';



function state(role: Role = 'analyst'): OperationsState {
  const snapshot = structuredClone({ schema_version: 'fp/1', server_epoch: 'epoch', mode: 'live', cursor: 1, run_id: run.id, run,
    records: { investigation: [investigation], analysis: [analysis], proposal: [proposal],
      mission: [{ ...mission, state: 'PROPOSED' }], case: [caseRecord], finding: [finding], wording: [wording] },
    latest_readings: [] }) as unknown as Snapshot;
  return {
    snapshot: { status: 'ready', snapshot, error: null, reload: vi.fn(),
      records: <T extends RecordType>(type: T) => (snapshot.records[type] ?? []) as RecordTypes[T][],
      record: <T extends RecordType>(type: T, id: string | null | undefined) =>
        ((snapshot.records[type] ?? []) as RecordTypes[T][]).find(r => r.id === id),
    },
    session: { status: 'ready', session: { signed_in: true, actor_label: `${role}@test`, station_id: 'test', roles: [role] },
      error: null, can: r => r === role, login: vi.fn(), logout: vi.fn(), refresh: vi.fn() },
    live: { status: 'live', serverEpoch: 'epoch', mode: 'live', cursor: 1,
      tick: { schema_version: 'fp/1', message_type: 'tick', run_id: run.id, server_epoch: 'epoch', cursor: 1,
        sim_time_s: 500, server_time_utc: run.created_utc, payload: tick.payload } as unknown as OperationsState['live']['tick'],
      lastMessageMs: 1, connects: 1 },
  };
}
function show(page: ReactNode, value = state()) {
  return render(<MemoryRouter><OperationsContext.Provider value={value}>{page}</OperationsContext.Provider></MemoryRouter>);
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ receipt_id: 'receipt', command_id: 'command',
    status: 'APPLIED', reason_code: null, reason_text: null, record_refs: [], actor: 'analyst@test', automatic: false }), { status: 200 })));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('investigation record presentation', () => {
  it('keeps UNKNOWN, physics statuses, limits, history counts and AI wording visible without order scores', () => {
    const value = state();
    const model = value.snapshot.records('analysis')[0];
    model.learned_order = model.learned_order.map(rank => ({ ...rank, order_score: 987654.321 }));
    show(<InvestigationPage />, value);
    expect(screen.getByRole('rowheader', { name: /UNKNOWN/ })).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'AI order' })).toBeTruthy();
    expect(screen.getByText('What this cannot tell you')).toBeTruthy();
    expect(screen.getByText('Plain words by AI (it cannot change any number)')).toBeTruthy();
    expect(document.body.textContent).not.toContain('987654.321');
    expect(document.body.textContent).not.toContain('%');
    expect(document.body.textContent).toContain(model.qualification);
    expect(document.body.textContent).toContain('fit on earlier runs');
  });
  it('shows template fallback and missing model results honestly', () => {
    const value = state();
    value.snapshot.snapshot!.records.wording = [{ ...value.snapshot.records('wording')[0], source: 'TEMPLATE', fallback_reason: 'NO_KEY' }];
    value.snapshot.snapshot!.records.analysis = [{ ...value.snapshot.records('analysis')[0], learned_status: 'MODEL_MISSING', learned_order: [], history_counts: null }];
    show(<InvestigationPage />, value);
    expect(screen.getByText(/Fixed text · NO_KEY/)).toBeTruthy();
    expect(screen.getByText(/MODEL_MISSING/)).toBeTruthy();
    expect(document.body.textContent).toContain('fit on earlier runs: unknown');
  });
  it('shows an empty investigation state without creating results', () => {
    const value = state(); value.snapshot.snapshot!.records.investigation = [];
    show(<InvestigationPage />, value);
    expect(screen.getByText('No investigation has been saved for this run yet.')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });
  it.each(['stale', 'offline', 'connecting'] as const)('blocks commands when the connection is %s', status => {
    const value = state(); value.live.status = status;
    show(<InvestigationPage />, value);
    const button = screen.getByRole('button', { name: 'Run analysis again' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true); fireEvent.click(button);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('shows a scheduled survey without warning areas and preserves separate analysis history', () => {
    const value = state();
    const original = value.snapshot.records('analysis')[0];
    value.snapshot.snapshot!.records.analysis = [original, { ...original, id: 'second-analysis', explanation_text: 'Updated after the sample.' }];
    value.snapshot.snapshot!.records.investigation = [{ ...investigation, trigger_id: null, survey_id: 'survey',
      state: 'ANALYSED', warned_area_ids: [], analysis_ids: [original.id, 'second-analysis'] }];
    show(<InvestigationPage />, value);
    expect(screen.getByText('Scheduled survey')).toBeTruthy();
    expect(screen.getAllByRole('article')).toHaveLength(2);
    expect(screen.getByText(/Who was warned: No areas/)).toBeTruthy();
  });
  it('sends the displayed revision and reloads after stale rejection', async () => {
    const value = state();
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ status: 'REJECTED', reason_code: 'STALE_REVISION', reason_text: 'old revision' })));
    show(<InvestigationPage />, value);
    fireEvent.click(screen.getByRole('button', { name: 'Run analysis again' }));
    await waitFor(() => expect(value.snapshot.reload).toHaveBeenCalledOnce());
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
    expect(body).toMatchObject({ command_type: 'run_analysis', expected_revision: investigation.revision,
      run_id: run.id, payload: { investigation_id: investigation.id } });
    expect(screen.getByRole('alert').textContent).toContain('Someone already changed this');
  });
});

describe('mission supervision', () => {
  it('schedules a human-directed survey when the planner has no useful measurement', async()=>{
    const value=state();value.snapshot.snapshot!.records.mission=[];
    value.snapshot.snapshot!.records.proposal=[{...value.snapshot.records('proposal')[0],result:'NO_USEFUL_MEASUREMENT',best_action_id:null,cheaper_action_id:null}];
    show(<MissionsPage/>,value);
    fireEvent.change(screen.getByLabelText('Survey reason'),{target:{value:'Independent teaching survey'}});
    fireEvent.click(screen.getByRole('button',{name:'Schedule independent survey'}));
    await waitFor(()=>expect(fetch).toHaveBeenCalledOnce());
    expect(JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body))).toMatchObject({command_type:'schedule_survey',run_id:run.id,expected_revision:null,payload:{action_id:'SURVEY-DRONE-0-100-35',reason:'Independent teaching survey'}});
  });

  it('renders rejected null-target actions with the saved rejection rather than inventing coordinates', () => {
    const value=state();
    const saved=value.snapshot.records('proposal')[0];
    value.snapshot.snapshot!.records.proposal=[{...saved,actions:[...saved.actions,{action_id:'A-BKG-DEPOT',kind:'BACKGROUND',vehicle:'DRONE',target:null,score:null,complete_minutes:null,feasible:false,rejection_reason:'No public target is available'}]}] as unknown as RecordTypes['proposal'][];
    show(<MissionsPage/>,value);
    expect(screen.getByText(/No public target is available/)).toBeTruthy();
    expect(screen.getByText(/Target unavailable/)).toBeTruthy();
  });

  it.each(['EXPIRED', 'REJECTED'] as const)('proposes a replacement for a %s mission using the latest saved basis', async ended => {
    const value = state();
    const oldProposal = value.snapshot.records('proposal')[0];
    const latest = { ...oldProposal, id: 'latest-proposal', analysis_id: 'latest-analysis', revision: 3 };
    value.snapshot.snapshot!.records.proposal = [oldProposal, latest];
    value.snapshot.snapshot!.records.investigation = [{ ...value.snapshot.records('investigation')[0],
      analysis_ids: [oldProposal.analysis_id, latest.analysis_id] }];
    value.snapshot.snapshot!.records.mission = [{ ...value.snapshot.records('mission')[0], state: ended }];
    show(<MissionsPage />, value);
    fireEvent.click(screen.getByRole('button', { name: 'Propose recommended mission' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
    expect(body).toMatchObject({ command_type: 'propose_mission', expected_revision: 3,
      payload: { proposal_id: latest.id, action_id: latest.best_action_id } });
    expect(screen.getByRole('heading', { name: new RegExp(`Mission state.*${ended}`) })).toBeTruthy();
  });
  it('does not offer a replacement for an active mission', () => {
    show(<MissionsPage />);
    expect(screen.queryByRole('button', { name: 'Propose recommended mission' })).toBeNull();
  });
  it('does not replace another investigations expired mission', () => {
    const value = state();
    value.snapshot.snapshot!.records.mission = [{ ...value.snapshot.records('mission')[0], state: 'EXPIRED' }];
    value.snapshot.snapshot!.records.investigation = [{ ...value.snapshot.records('investigation')[0], analysis_ids: ['different-analysis'] }];
    show(<MissionsPage />, value);
    expect(screen.queryByRole('button', { name: 'Propose recommended mission' })).toBeNull();
  });
  it.each(['stale', 'offline'] as const)('keeps replacement proposals read-only while %s', status => {
    const value = state(); value.live.status = status;
    value.snapshot.snapshot!.records.mission = [{ ...value.snapshot.records('mission')[0], state: 'EXPIRED' }];
    show(<MissionsPage />, value);
    const button = screen.getByRole('button', { name: 'Propose recommended mission' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true); fireEvent.click(button);
    expect(fetch).not.toHaveBeenCalled();
  });
  it.each(['PENDING', 'NO_USEFUL_MEASUREMENT', 'FUTURE_RESULT'])('does not show or submit recommended actions for result %s even with leftover action ids', result => {
    const value = state();
    value.snapshot.snapshot!.records.mission = [];
    value.snapshot.snapshot!.records.proposal = [{ ...value.snapshot.records('proposal')[0], result }] as unknown as RecordTypes['proposal'][];
    show(<MissionsPage />, value);
    expect(screen.queryByRole('heading', { name: /Recommended measurement|Cheaper useful option/ })).toBeNull();
    const button = screen.getByRole('button', { name: 'Propose recommended mission' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.click(button);
    expect(fetch).not.toHaveBeenCalled();
    expect(document.body.textContent).not.toContain('A useful measurement is available');
  });
  it('shows a saved survey mission without inventing a proposal association', () => {
    const value = state('supervisor');
    value.snapshot.snapshot!.records.mission = [{ ...value.snapshot.records('mission')[0], proposal_id: null, survey_id: 'survey-id' }];
    show(<MissionsPage />, value);
    expect(screen.getByText('Scheduled survey mission; no analysis proposal is attached.')).toBeTruthy();
    expect(screen.queryByText('The proposal referenced by this mission is unavailable.')).toBeNull();
    expect(screen.queryByRole('heading', { name: /Recommended measurement/ })).toBeNull();
    expect(screen.getByRole('heading', { name: /Mission state/ })).toBeTruthy();
  });
  it.each(['missing', 'infeasible', 'no recommendation'])('does not submit a mission for a %s recommended action', invalid => {
    const value = state();
    value.snapshot.snapshot!.records.mission = [];
    const saved = value.snapshot.records('proposal')[0];
    value.snapshot.snapshot!.records.proposal = [{ ...saved, result: 'BEST_ACTION',
      best_action_id: invalid === 'missing' ? 'missing-action' : invalid === 'no recommendation' ? null : saved.best_action_id,
      actions: saved.actions.map(action => ({ ...action, feasible: false })),
    }];
    show(<MissionsPage />, value);
    const button = screen.getByRole('button', { name: 'Propose recommended mission' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(screen.getByRole('alert').textContent).toContain('missing or marked infeasible');
    fireEvent.click(button);
    expect(fetch).not.toHaveBeenCalled();
  });
  it('submits only the saved feasible recommendation and proposal revision', async () => {
    const value = state();
    value.snapshot.snapshot!.records.mission = [];
    const saved = value.snapshot.records('proposal')[0];
    show(<MissionsPage />, value);
    fireEvent.click(screen.getByRole('button', { name: 'Propose recommended mission' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
    expect(body).toMatchObject({ command_type: 'propose_mission', expected_revision: saved.revision,
      run_id: run.id, payload: { proposal_id: saved.id, action_id: saved.best_action_id } });
  });
  it.each([null, undefined, NaN, Infinity, '712'])('keeps an unavailable or invalid expiry %s unknown and prevents approval', expiry => {
    const value = state('supervisor');
    value.snapshot.snapshot!.records.mission = [{ ...value.snapshot.records('mission')[0], expires_s: expiry }] as unknown as RecordTypes['mission'][];
    show(<MissionsPage />, value);
    expect((screen.getByRole('button', { name: 'Approve mission' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Approval window: unknown')).toBeTruthy();
    expect(document.body.textContent).not.toContain('NaN');
    fireEvent.click(screen.getByRole('button', { name: 'Approve mission' }));
    expect(fetch).not.toHaveBeenCalled();
  });
  it('does not replace a missions missing proposal with an unrelated recommendation', () => {
    const value = state('supervisor');
    value.snapshot.snapshot!.records.proposal = [{ ...value.snapshot.records('proposal')[0], id: 'unrelated-proposal' }];
    show(<MissionsPage />, value);
    expect(screen.getByText('The proposal referenced by this mission is unavailable.')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: /Recommended measurement/ })).toBeNull();
    expect(screen.getByRole('heading', { name: /Mission state/ })).toBeTruthy();
  });
  it('does not propose another investigations recommendation when no mission exists', () => {
    const value = state();
    value.snapshot.snapshot!.records.mission = [];
    value.snapshot.snapshot!.records.proposal = [{ ...value.snapshot.records('proposal')[0], analysis_id: 'unrelated-analysis' }];
    show(<MissionsPage />, value);
    expect(screen.getByText('No proposal for the current investigation is available.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Propose recommended mission' })).toBeNull();
  });
  it('sends only the saved mission and dependency hash with its revision', async () => {
    show(<MissionsPage />, state('supervisor'));
    fireEvent.click(screen.getByRole('button', { name: 'Approve mission' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
    expect(body).toMatchObject({ command_type: 'approve_mission', expected_revision: mission.revision,
      payload: { mission_id: mission.id, dependency_hash: mission.dependency_hash } });
  });
  it('refuses analyst approval', () => {
    const value = state('analyst'); show(<MissionsPage />, value);
    expect((screen.getByRole('button', { name: 'Approve mission' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/Not allowed: sign in with the supervisor role/)).toBeTruthy();
  });
  it('blocks approval when the saved expiry has passed', () => {
    const value = state('supervisor');
    value.snapshot.snapshot!.records.mission = [{ ...value.snapshot.records('mission')[0], expires_s: 499 }];
    show(<MissionsPage />, value);
    expect((screen.getByRole('button', { name: 'Approve mission' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/0.0 simulation seconds remaining/)).toBeTruthy();
  });
  it('treats PENDING as a real result with no recommendation', () => {
    const value = state(); value.snapshot.snapshot!.records.mission = [];
    value.snapshot.snapshot!.records.proposal = [{ ...proposal, result: 'PENDING', best_action_id: null, cheaper_action_id: null }] as unknown as RecordTypes['proposal'][];
    show(<MissionsPage />, value);
    expect(screen.getByText(/Still working it out/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Propose recommended mission' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('station-session and pending command states', () => {
  for (const status of ['loading', 'error'] as const) {
    it.each(['analyst', 'supervisor', 'board', 'presenter'] as const)(`keeps cached %s roles read-only when session is ${status}`, role => {
      const value = state(role);
      value.session.status = status;
      const page = role === 'analyst' ? <InvestigationPage /> : role === 'supervisor' ? <MissionsPage />
        : role === 'board' ? <BoardPage /> : <ScenariosPage />;
      show(page, value);
      if (role === 'board') {
        fireEvent.change(screen.getByLabelText('Rationale'), { target: { value: 'More evidence needed.' } });
        fireEvent.change(screen.getByLabelText('Time interval'), { target: { value: 'Today' } });
        fireEvent.change(screen.getByLabelText('Area notice'), { target: { value: 'Review pending.' } });
      }
      const label = role === 'analyst' ? 'Run analysis again' : role === 'supervisor' ? 'Approve mission'
        : role === 'board' ? 'Save board decision' : 'Pause run';
      const button = screen.getByRole('button', { name: label }) as HTMLButtonElement;
      expect(button.disabled).toBe(true);
      fireEvent.click(button);
      expect(fetch).not.toHaveBeenCalled();
    });
  }
  it('does not trust cached capabilities after the station is signed out', () => {
    const value = state();
    value.session.session = { signed_in: false, roles: [], actor_label: 'anonymous', station_id: null };
    show(<InvestigationPage />, value);
    expect((screen.getByRole('button', { name: 'Run analysis again' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('disables a command while its receipt is pending and displays the saved result', async () => {
    let finish: (value: Response) => void = () => undefined;
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const value = state();
    show(<InvestigationPage />, value);
    const button = screen.getByRole('button', { name: 'Run analysis again' }) as HTMLButtonElement;
    fireEvent.click(button);
    await waitFor(() => expect(button.disabled).toBe(true));
    expect(screen.getByText(/Command pending/)).toBeTruthy();
    fireEvent.click(button);
    expect(fetch).toHaveBeenCalledOnce();
    finish(new Response(JSON.stringify({ status: 'APPLIED', reason_code: null, record_refs: [] })));
    await waitFor(() => expect(value.snapshot.reload).toHaveBeenCalledOnce());
    await waitFor(() => expect(button.disabled).toBe(false));
    expect(screen.queryByText(/Command pending/)).toBeNull();
  });
});

describe('board and scenario commands', () => {
  it('mounts the server metric report on the board', () => {
    show(<BoardPage />, state('board'));
    expect(screen.getByRole('region', { name: 'Daily server metrics' })).toBeTruthy();
  });
  it('defaults to warned areas and refuses factory names in public text', () => {
    show(<BoardPage />, state('board'));
    expect((screen.getByRole('checkbox', { name: 'East colony' }) as HTMLInputElement).checked).toBe(true);
    fireEvent.change(screen.getByLabelText('Area notice'), { target: { value: 'S02 has a problem' } });
    expect(screen.getByRole('alert').textContent).toContain('must not name a source');
    expect((screen.getByRole('button', { name: 'Save board decision' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('submits a human board decision for a case with a finding', async () => {
    show(<BoardPage />, state('board'));
    fireEvent.change(screen.getByLabelText('Rationale'), { target: { value: 'More evidence is needed.' } });
    fireEvent.change(screen.getByLabelText('Time interval'), { target: { value: 'Today, 1 to 3' } });
    fireEvent.change(screen.getByLabelText('Area notice'), { target: { value: 'Investigation continues.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save board decision' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
    expect(body).toMatchObject({ command_type: 'record_board_decision', expected_revision: caseRecord.revision,
      payload: { case_id: caseRecord.id, notice: { area_ids: ['AREA-E'], status_text: 'Investigation continues.' } } });
  });
  it('blocks board decisions without a finding', () => {
    const value = state('board'); value.snapshot.snapshot!.records.finding = [];
    show(<BoardPage />, value);
    expect((screen.getByRole('button', { name: 'Save board decision' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('A finding must be recorded before the board can decide.')).toBeTruthy();
  });
  it('requires reset confirmation, sends no unsupported expected revision and offers no truth controls', async () => {
    show(<ScenariosPage />, state('presenter'));
    fireEvent.click(screen.getByRole('button', { name: 'Reset to a new run' }));
    expect(fetch).not.toHaveBeenCalled();
    const confirmation = screen.getByRole('region', { name: 'Confirm reset' });
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Confirm reset' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    const body = JSON.parse(String(vi.mocked(fetch).mock.calls[0][1]?.body));
    expect(body).toMatchObject({ command_type: 'reset_run', expected_revision: null, payload: { mode: 'BLIND' } });
    expect(screen.queryByRole('button', { name: /truth|wind|height/i })).toBeNull();
  });
  it('keeps interrupted investigation controls read-only while allowing a presenter reset', () => {
    const value = state(); value.snapshot.snapshot!.run = { ...value.snapshot.snapshot!.run!, state_reason: 'INTERRUPTED' };
    show(<InvestigationPage />, value);
    expect((screen.getByRole('button', { name: 'Run analysis again' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
