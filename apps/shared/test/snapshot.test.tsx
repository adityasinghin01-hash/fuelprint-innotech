import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LiveProvider, useSnapshot } from '../src/hooks';
import { configureServer } from '../src/http';
import { LiveConnection } from '../src/live';
import { event, FakeSocket, hello } from './fakeSocket';

function Sensors() {
  const snap = useSnapshot();
  if (snap.status !== 'ready') return <p>loading</p>;
  return (
    <ul>
      {snap.records('sensor').map((s) => <li key={s.id}>{s.label} r{s.revision} {s.status}</li>)}
      <li>run {snap.snapshot?.run_id}</li>
    </ul>
  );
}

const snapshotBody = (runId: string, cursor: number, sensors: unknown[]) => ({
  schema_version: 'fp/1', server_epoch: 'epoch-1', mode: 'replay', cursor, run_id: runId,
  run: { id: runId, revision: 1, state: 'RUNNING' }, records: { sensor: sensors }, latest_readings: [],
});

describe('useSnapshot', () => {
  beforeEach(() => {
    FakeSocket.all = [];
    configureServer('http://host:8080');
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    configureServer(null);
  });

  it('loads the snapshot, applies newer live revisions and follows a new run', async () => {
    const responses = [
      snapshotBody('run-1', 10, [{ id: 's1', revision: 1, label: 'N1', status: 'OK', run_id: 'run-1' }]),
      snapshotBody('run-2', 30, [{ id: 's9', revision: 1, label: 'N1', status: 'OK', run_id: 'run-2' }]),
    ];
    const fetched: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      fetched.push(url);
      return new Response(JSON.stringify(responses.shift()), { status: 200 });
    }));
    const connection = new LiveConnection({ socketFactory: FakeSocket.factory });
    render(<LiveProvider connection={connection}><Sensors /></LiveProvider>);
    await waitFor(() => expect(screen.getByText(/N1 r1 OK/)).toBeTruthy());
    expect(fetched[0]).toBe('http://host:8080/api/snapshot');

    act(() => {
      FakeSocket.last().open();
      FakeSocket.last().push(hello(10));
      FakeSocket.last().push(event(11, { id: 's1', revision: 2, label: 'N1', status: 'FAULT', run_id: 'run-1' }));
      FakeSocket.last().push(event(12, { id: 's1', revision: 1, label: 'N1', status: 'OLD', run_id: 'run-1' }));
    });
    await waitFor(() => expect(screen.getByText(/N1 r2 FAULT/)).toBeTruthy());

    act(() => {
      FakeSocket.last().push(event(20, { id: 'run-2', revision: 1, state: 'RUNNING' },
        { record_type: 'run', event_type: 'RUN_CREATED', record_id: 'run-2', run_id: 'run-2' }));
    });
    await waitFor(() => expect(screen.getByText(/run run-2/)).toBeTruthy());
    expect(fetched).toHaveLength(2);
  });

  it('keeps only the newest reading per sensor and channel, however many events arrive', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      ...snapshotBody('run-1', 0, []), latest_readings: [],
    }), { status: 200 })));
    let snapshotNow: ReturnType<typeof useSnapshot> | null = null;
    function Probe() {
      snapshotNow = useSnapshot();
      return <p>{snapshotNow.status}</p>;
    }
    const connection = new LiveConnection({ socketFactory: FakeSocket.factory });
    render(<LiveProvider connection={connection}><Probe /></LiveProvider>);
    await waitFor(() => expect(screen.getByText('ready')).toBeTruthy());
    const started = Date.now();
    act(() => {
      FakeSocket.last().open();
      FakeSocket.last().push(hello(0));
      for (let i = 1; i <= 4000; i++) {
        FakeSocket.last().push(event(i, { id: `r${i}`, revision: 1, sensor_or_vehicle_id: `s${i % 14}`, channel: 'SO2_ppb',
          sim_time_s: i, value: i, run_id: 'run-1' }, { record_type: 'reading' }));
      }
    });
    expect(Date.now() - started).toBeLessThan(3000);
    const latest = snapshotNow!.snapshot!.latest_readings;
    expect(latest).toHaveLength(14);
    expect(Math.max(...latest.map((r) => r.sim_time_s))).toBe(4000);
    expect(snapshotNow!.snapshot!.records.reading).toBeUndefined();
  });
});
