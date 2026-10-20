import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveConnection } from '@fp/shared';

import { App } from './App';

class FakeSocket {
  static last: FakeSocket;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((m: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  constructor(readonly url: string) {
    FakeSocket.last = this;
  }
  push(m: unknown) {
    this.onmessage?.({ data: JSON.stringify(m) });
  }
  close() {
    this.readyState = 3;
    this.onclose?.();
  }
}

const env = (type: string, payload: unknown, sim = 12.5) => ({
  schema_version: 'fp/1', message_type: type, run_id: 'run-1', server_epoch: 'e', cursor: 3, sim_time_s: sim,
  server_time_utc: '2026-10-22T10:00:00.000Z', payload,
});
let signedIn = false;
let snapshotRecords: Record<string, unknown[]> = {};
let runState = 'RUNNING';

describe('operations skeleton top bar', () => {
  beforeEach(() => {
    signedIn = false;
    snapshotRecords = {};
    runState = 'RUNNING';
    window.history.replaceState(null, '', '/operations/');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const body = url.includes('/api/snapshot') ? { cursor: 3, run_id: 'run-1', records: snapshotRecords, latest_readings: [],
        run: { id: 'run-1', revision: 1, preset_id: 'SC1-NIGHT-PLUME', state: runState } }
        : url.includes('/api/session/stations') ? [] : url.includes('/api/session')
          ? { signed_in: signedIn, roles: signedIn ? ['presenter'] : [], station_id: 'laptop-1' } : [];
      return new Response(JSON.stringify(body), { status: 200 });
    }));
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  function analysed(result: string | null, autoPropose?: boolean) {
    snapshotRecords = {
      investigation: [{ id: 'investigation-1', state: 'ANALYSED', analysis_ids: ['analysis-1'] }],
      proposal: result ? [{ id: 'proposal-1', analysis_id: 'analysis-1', result, best_action_id: result === 'BEST_ACTION' ? 'action-1' : null }] : [],
      automation_settings: autoPropose === undefined ? [] : [{ id: 'settings', auto_propose: autoPropose }],
    };
  }

  it.each([
    ['BEST_ACTION', true, 'Automation can propose the recommended mission.'],
    ['BEST_ACTION', false, 'Automatic proposals are off. An analyst can propose the recommended measurement.'],
    ['BEST_ACTION', undefined, 'Automation settings are unavailable.'],
    ['PENDING', true, 'The planner is still working it out. No mission is recommended yet.'],
    ['NO_USEFUL_MEASUREMENT', true, 'No measurement would help right now.'],
    [null, true, 'No recommended measurement is available yet.'],
  ] as const)('guides the next action from saved planner result %s and auto-propose %s', async (result, enabled, expected) => {
    analysed(result, enabled);
    const connection = new LiveConnection({ socketFactory: u => new FakeSocket(u) as unknown as WebSocket });
    render(<App connection={connection} />);
    await waitFor(() => expect(document.querySelector('.ops-next')?.textContent).toContain(expected));
  });

  it('does not use another investigations proposal for next-action guidance', async () => {
    analysed(null, true);
    snapshotRecords.proposal = [{ id: 'unrelated', analysis_id: 'another-analysis', result: 'BEST_ACTION', best_action_id: 'action' }];
    const connection = new LiveConnection({ socketFactory: u => new FakeSocket(u) as unknown as WebSocket });
    render(<App connection={connection} />);
    await waitFor(() => expect(document.querySelector('.ops-next')?.textContent).toContain('No recommended measurement is available yet.'));
    expect(document.querySelector('.ops-next')?.textContent).not.toContain('Automation can propose');
  });

  it.each(['EXPIRED', 'REJECTED'])('offers a new proposal for a saved %s mission instead of flight guidance', async state => {
    analysed('BEST_ACTION', true);
    snapshotRecords.investigation = [{ id: 'investigation-1', state: 'MISSION_APPROVED', analysis_ids: ['analysis-1'] }];
    snapshotRecords.mission = [{ id: 'mission-1', proposal_id: 'proposal-1', state }];
    const connection = new LiveConnection({ socketFactory: u => new FakeSocket(u) as unknown as WebSocket });
    render(<App connection={connection} />);
    await waitFor(() => expect(document.querySelector('.ops-next')?.textContent).toContain('propose a new measurement'));
    expect(document.querySelector('.ops-next')?.textContent).not.toContain('can fly');
  });

  it.each(['COMPLETED', 'ABORTED'])('offers saved-record review for an ended %s run', async state => {
    analysed('BEST_ACTION', true);
    runState = state;
    const connection = new LiveConnection({ socketFactory: u => new FakeSocket(u) as unknown as WebSocket });
    render(<App connection={connection} />);
    await waitFor(() => expect(document.querySelector('.ops-next')?.textContent).toContain('This run has ended.'));
    expect(document.querySelector('.ops-next')?.textContent).not.toContain('Automation can propose');
  });

  it('makes presenter controls read-only after three seconds and restores them after reconnect', async () => {
    signedIn = true;
    window.history.replaceState(null, '', '/operations/scenarios');
    const connection = new LiveConnection({ socketFactory: u => new FakeSocket(u) as unknown as WebSocket });
    render(<App connection={connection} />);
    const heartbeat = () => {
      FakeSocket.last.onopen?.();
      FakeSocket.last.push(env('hello', { mode: 'live', after: null, reload_snapshot: false, kept_events: 1 }));
      FakeSocket.last.push(env('tick', { wind: { from_deg: 270, speed_m_s: 3 }, time_scale: 1, latest_readings: [], vehicles: [] }));
    };
    act(heartbeat);
    const pause = () => screen.getByRole('button', { name: 'Pause run' }) as HTMLButtonElement;
    await waitFor(() => expect(pause().disabled).toBe(false));
    act(() => { vi.advanceTimersByTime(3100); });
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Stale/);
    expect(pause().disabled).toBe(true);
    const first = FakeSocket.last;
    act(() => first.close());
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Offline/);
    expect(pause().disabled).toBe(true);
    act(() => { vi.advanceTimersByTime(550); });
    expect(FakeSocket.last).not.toBe(first);
    act(heartbeat);
    await waitFor(() => expect(pause().disabled).toBe(false));
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Live/);
  });

  it('shows connecting, live, stale and offline', async () => {
    const connection = new LiveConnection({ socketFactory: (u) => new FakeSocket(u) as unknown as WebSocket });
    render(<App connection={connection} />);
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Connecting/);
    act(() => {
      FakeSocket.last.onopen?.();
      FakeSocket.last.push(env('hello', { mode: 'replay', after: null, reload_snapshot: false, kept_events: 1 }));
      FakeSocket.last.push(env('tick', { wind: { from_deg: 270, speed_m_s: 3 }, time_scale: 1, latest_readings: [],
        vehicles: [{ vehicle_id: 'v', label: 'DRONE-1', kind: 'DRONE', pose: { east: 0, north: 0, up: 12, yaw_deg: 0 },
          velocity: { east: 0, north: 0, up: 0 }, inlet: { east: 0, north: 0, up: 11.6 }, energy_wh: 60, stage: 'OUTBOUND',
          lease_holder: null, pilot: 'AUTOPILOT' }] }));
    });
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Live/);
    expect(screen.getByTestId('drone').textContent).toMatch(/height 12.0 m/);
    expect(screen.getByTestId('sim-time').textContent).toMatch(/12.5 s/);
    await waitFor(() => expect(screen.getByTestId('run-state').textContent).toMatch(/SC1-NIGHT-PLUME \(RUNNING\)/));
    expect(screen.queryByTestId('stale-banner')).toBeNull();

    act(() => {
      vi.advanceTimersByTime(3100);
    });
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Stale/);
    expect(screen.getByTestId('stale-banner')).toBeTruthy();

    act(() => FakeSocket.last.close());
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Offline/);
  });
});
