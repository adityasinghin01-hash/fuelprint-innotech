// A WebSocket stand-in for tests: records URLs, lets the test open, send messages to and close each socket.
export class FakeSocket {
  static all: FakeSocket[] = [];
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((m: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor(readonly url: string) {
    FakeSocket.all.push(this);
  }

  static factory = (url: string) => new FakeSocket(url) as unknown as WebSocket;

  static last(): FakeSocket {
    return FakeSocket.all[FakeSocket.all.length - 1];
  }

  open() {
    this.readyState = 1;
    this.onopen?.();
  }

  push(message: unknown) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  close() {
    this.readyState = 3;
    this.onclose?.();
  }
}

export function envelope(type: string, cursor: number, payload: unknown, extra: Record<string, unknown> = {}) {
  return {
    schema_version: 'fp/1', message_type: type, run_id: 'run-1', server_epoch: 'epoch-1', cursor, sim_time_s: 1,
    server_time_utc: '2026-10-22T10:15:04.120Z', payload, ...extra,
  };
}

export function hello(cursor: number, reload = false, epoch = 'epoch-1') {
  return envelope('hello', cursor, { mode: 'replay', after: null, reload_snapshot: reload, kept_events: 20000 },
    { server_epoch: epoch });
}

export function event(cursor: number, record: Record<string, unknown>, extra: Record<string, unknown> = {}) {
  return envelope('event', cursor, {
    cursor, event_type: 'RECORD_SAVED', record_type: 'sensor', record_id: record.id, revision: record.revision,
    run_id: 'run-1', sim_time_s: 1, wall_utc: '2026-10-22T10:15:04.120Z', actor: 'automation', automatic: true,
    record, ...extra,
  });
}

export function tick(n: number) {
  return envelope('tick', 0, { wind: { from_deg: 270, speed_m_s: 3 }, time_scale: 1, vehicles: [], latest_readings: [], n });
}
