import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { configureServer } from '../src/http';
import { LiveConnection, STALE_AFTER_MS, isReadOnly } from '../src/live';
import { event, FakeSocket, hello, tick } from './fakeSocket';

describe('LiveConnection', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.all = [];
    configureServer('http://192.168.1.20:8080');
  });
  afterEach(() => {
    vi.useRealTimers();
    configureServer(null);
  });

  it('goes live on hello, follows the event cursor and ignores repeats', () => {
    const live = new LiveConnection({ socketFactory: FakeSocket.factory }).open();
    expect(FakeSocket.last().url).toBe('ws://192.168.1.20:8080/ws/live');
    const seen: number[] = [];
    live.onEvent((e) => seen.push(e.cursor));
    FakeSocket.last().open();
    FakeSocket.last().push(hello(10));
    expect(live.current.status).toBe('live');
    expect(live.current.mode).toBe('replay');
    FakeSocket.last().push(event(11, { id: 'a', revision: 1 }));
    FakeSocket.last().push(event(11, { id: 'a', revision: 1 }));
    FakeSocket.last().push(event(12, { id: 'b', revision: 1 }));
    expect(seen).toEqual([11, 12]);
    expect(live.current.cursor).toBe(12);
    live.close();
  });

  it('turns stale after 3 seconds without a message and live again when data returns', () => {
    const live = new LiveConnection({ socketFactory: FakeSocket.factory }).open();
    FakeSocket.last().open();
    FakeSocket.last().push(hello(0));
    vi.advanceTimersByTime(STALE_AFTER_MS - 100);
    FakeSocket.last().push(tick(1));
    vi.advanceTimersByTime(STALE_AFTER_MS - 100);
    expect(live.current.status).toBe('live');
    vi.advanceTimersByTime(200);
    expect(live.current.status).toBe('stale');
    expect(isReadOnly(live.current.status)).toBe(true);
    FakeSocket.last().push(tick(2));
    expect(live.current.status).toBe('live');
    expect(live.current.tick?.payload.wind?.from_deg).toBe(270);
    live.close();
  });

  it('reconnects by itself and asks only for events after the last cursor', () => {
    const live = new LiveConnection({ socketFactory: FakeSocket.factory, runId: 'run-1' }).open();
    FakeSocket.last().open();
    FakeSocket.last().push(hello(0));
    FakeSocket.last().push(event(41, { id: 'a', revision: 1 }));
    FakeSocket.last().close();
    expect(live.current.status).toBe('offline');
    expect(FakeSocket.all).toHaveLength(1);
    vi.advanceTimersByTime(600);
    expect(FakeSocket.all).toHaveLength(2);
    expect(FakeSocket.last().url).toBe('ws://192.168.1.20:8080/ws/live?run_id=run-1&after=41');
    FakeSocket.last().close();
    vi.advanceTimersByTime(600);
    expect(FakeSocket.all).toHaveLength(2); // backs off: next try after 1 s
    vi.advanceTimersByTime(500);
    expect(FakeSocket.all).toHaveLength(3);
    live.close();
    vi.advanceTimersByTime(10_000);
    expect(FakeSocket.all).toHaveLength(3);
  });

  it('asks the screen to reload the snapshot when the cursor is too old or the server restarted', () => {
    const live = new LiveConnection({ socketFactory: FakeSocket.factory, after: 3 }).open();
    const reasons: string[] = [];
    live.onReloadNeeded((r) => reasons.push(r));
    FakeSocket.last().open();
    FakeSocket.last().push(hello(9000, true));
    expect(reasons).toHaveLength(1);
    expect(live.current.cursor).toBe(9000);
    FakeSocket.last().push(hello(9001, false, 'epoch-2'));
    expect(reasons).toHaveLength(2);
    live.close();
  });
});
