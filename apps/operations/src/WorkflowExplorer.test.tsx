import { cleanup, fireEvent, render, screen, act } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
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
import { WorkflowExplorer } from './WorkflowExplorer';
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


afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); });
describe('workflow explorer', () => {
  it('explains all stages without sending commands or treating selection as completion', () => {
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher);
    show(<WorkflowExplorer />);
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(screen.getByRole('heading', { name: 'Approve' })).toBeTruthy();
    expect(screen.getByText('Human supervisor')).toBeTruthy();
    expect(screen.getByText('PROPOSED')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Next step' }));
    expect(screen.getByRole('heading', { name: 'Measure' })).toBeTruthy();
    expect(screen.getByText('Not saved for this investigation')).toBeTruthy();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it('never borrows an analysis or mission from another investigation', () => {
    const value = state();
    value.snapshot.snapshot!.records.investigation = [{ ...value.snapshot.records('investigation')[0], analysis_ids: [], survey_id: null }];
    show(<WorkflowExplorer />, value);
    fireEvent.click(screen.getByRole('button', { name: 'Analyse' }));
    expect(screen.getByText('Not saved for this investigation')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(screen.getByText('Not saved for this investigation')).toBeTruthy();
  });
  it('shows unknown while loading and identifies stale saved data', () => {
    const value = state(); value.snapshot.status = 'loading'; value.live.status = 'offline';
    show(<WorkflowExplorer />, value);
    expect(screen.getByText(/Unknown .* snapshot unavailable/)).toBeTruthy();
    expect(screen.getByText(/Connection offline/)).toBeTruthy();
  });
  it('excludes records from another run even when their IDs match', () => {
    const value = state();
    value.snapshot.snapshot!.records.analysis = [{ ...value.snapshot.records('analysis')[0], run_id: 'other-run' }];
    show(<WorkflowExplorer />, value);
    fireEvent.click(screen.getByRole('button', { name: 'Analyse' }));
    expect(screen.getByText('Not saved for this investigation')).toBeTruthy();
  });
  it('supports flat view and rotation without changing records', () => {
    show(<WorkflowExplorer />);
    const slider = screen.getByRole('slider', { name: 'Rotate diagram' }) as HTMLInputElement;
    fireEvent.change(slider, { target: { value: '10' } }); expect(slider.value).toBe('10');
    fireEvent.click(screen.getByRole('button', { name: 'Flat view' })); expect(slider.disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '3D view' })); expect(slider.disabled).toBe(false);
  });
  it('plays only on request, pauses and stops at the final stage', () => {
    vi.useFakeTimers(); show(<WorkflowExplorer />);
    act(() => { vi.advanceTimersByTime(5000); });
    expect(screen.getByRole('heading', { name: 'Observe' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Play walkthrough' }));
    act(() => { vi.advanceTimersByTime(4500); });
    expect(screen.getByRole('heading', { name: 'Analyse' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Pause walkthrough' }));
    act(() => { vi.advanceTimersByTime(9000); });
    expect(screen.getByRole('heading', { name: 'Analyse' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Board' }));
    fireEvent.click(screen.getByRole('button', { name: 'Play walkthrough' }));
    for (let i = 0; i < 8; i++) act(() => { vi.advanceTimersByTime(4500); });
    expect(screen.getByRole('heading', { name: 'Board' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Play walkthrough' })).toBeTruthy();
  });
  it('respects reduced motion preferences', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    show(<WorkflowExplorer />);
    expect((screen.getByRole('button', { name: 'Play walkthrough' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
