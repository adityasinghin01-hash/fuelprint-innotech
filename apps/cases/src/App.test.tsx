import { act, render, screen, waitFor } from '@testing-library/react';
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

describe('cases skeleton top bar', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const body = url.includes('/api/snapshot') ? { cursor: 3, run_id: 'run-1', records: {}, latest_readings: [],
        run: { id: 'run-1', revision: 1, preset_id: 'SC1-NIGHT-PLUME', state: 'RUNNING' } }
        : url.includes('/api/session/stations') ? [] : url.includes('/api/session') ? { signed_in: false, roles: [] } : [];
      return new Response(JSON.stringify(body), { status: 200 });
    }));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('shows connecting, live, stale and offline', async () => {
    const connection = new LiveConnection({ socketFactory: (u) => new FakeSocket(u) as unknown as WebSocket });
    await act(async () => { render(<App connection={connection} />); });
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
