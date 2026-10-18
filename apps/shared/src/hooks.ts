// React hooks over the live connection and the read API.
import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';

import { receiptProblem, sendCommand } from './commands';
import type { SendOptions } from './commands';
import { getJson, sendJson } from './http';
import { LiveConnection } from './live';
import type { LiveOptions, LiveState } from './live';
import type {
  AnyRecord, AutomationLogEntry, EventMessage, ReadingRecord, Receipt, RecordType, RecordTypes, SessionInfo, Snapshot,
  Uuid,
} from './types';

// ---------------------------------------------------------------- live

interface LiveContextValue {
  connection: LiveConnection;
}

const LiveContext = createContext<LiveContextValue | null>(null);

export interface LiveProviderProps extends LiveOptions {
  children?: ReactNode;
  /** Use this connection instead of making one (tests). */
  connection?: LiveConnection;
}

/** Opens one /ws/live connection for the whole app. Put it at the top of the app. */
export function LiveProvider(props: LiveProviderProps) {
  const { children, connection: given, ...options } = props;
  const ref = useRef<LiveConnection | null>(null);
  if (!ref.current) ref.current = given ?? new LiveConnection(options);
  useEffect(() => {
    const connection = ref.current!;
    connection.open();
    return () => connection.close();
  }, []);
  const value = useMemo(() => ({ connection: ref.current! }), []);
  return createElement(LiveContext.Provider, { value }, children);
}

function useConnection(): LiveConnection {
  const value = useContext(LiveContext);
  if (!value) throw new Error('useLive needs a <LiveProvider> above it');
  return value.connection;
}

/** The live state (status, latest tick, cursor). Re-renders on every message, about 10 times a second. */
export function useLive(): LiveState & { connection: LiveConnection } {
  const connection = useConnection();
  const [state, setState] = useState<LiveState>(connection.current);
  useEffect(() => connection.onState(setState), [connection]);
  return { ...state, connection };
}

/** Only the status (re-renders when it changes, not on every tick). */
export function useLiveStatus(): LiveState['status'] {
  const connection = useConnection();
  const [status, setStatus] = useState(connection.current.status);
  useEffect(() => connection.onState((s) => setStatus(s.status)), [connection]);
  return status;
}

/** Calls {@code listener} for every live event (record saved). */
export function useLiveEvents(listener: (event: EventMessage) => void): void {
  const connection = useConnection();
  const latest = useRef(listener);
  latest.current = listener;
  useEffect(() => connection.onEvent((e) => latest.current(e)), [connection]);
}

// ---------------------------------------------------------------- snapshot

export interface SnapshotState {
  status: 'loading' | 'ready' | 'error';
  snapshot: Snapshot | null;
  error: string | null;
  /** The latest revision of every record of a type in the snapshot (oldest first). */
  records<T extends RecordType>(type: T): RecordTypes[T][];
  /** One record by id, or undefined. */
  record<T extends RecordType>(type: T, id: Uuid | null | undefined): RecordTypes[T] | undefined;
  reload(): void;
}

function upsert(snapshot: Snapshot, record: AnyRecord, type: string): Snapshot {
  if (type === 'reading') {
    // Readings arrive once per second per channel for the whole run. Keep only the newest per sensor and channel
    // (latest_readings), never the whole history: copying it on every event would make the screen fall behind.
    const reading = record as ReadingRecord;
    const key = (r: ReadingRecord) => `${r.sensor_or_vehicle_id}/${r.channel}`;
    const latest = snapshot.latest_readings;
    const index = latest.findIndex((r) => key(r) === key(reading));
    if (index >= 0 && latest[index].sim_time_s >= reading.sim_time_s) return snapshot;
    const next = index >= 0 ? latest.map((r, i) => (i === index ? reading : r)) : [...latest, reading];
    return { ...snapshot, latest_readings: next };
  }
  const list = (snapshot.records[type] ?? []) as AnyRecord[];
  const index = list.findIndex((r) => r.id === record.id);
  if (index >= 0 && list[index].revision >= record.revision) return snapshot;
  const next = index >= 0 ? list.map((r, i) => (i === index ? record : r)) : [...list, record];
  const run = type === 'run' && snapshot.run && snapshot.run.id === record.id ? (record as Snapshot['run']) : snapshot.run;
  return { ...snapshot, run, records: { ...snapshot.records, [type]: next } };
}

/**
 * Everything current about one run (default: the current run), kept up to date by live events. When the presenter
 * resets and runId is not fixed, it follows the new run by itself.
 */
export function useSnapshot(runId?: Uuid | null): SnapshotState {
  const connection = useConnection();
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [status, setStatus] = useState<SnapshotState['status']>('loading');
  const [error, setError] = useState<string | null>(null);
  const [generation, setGeneration] = useState(0);
  const buffer = useRef<EventMessage[]>([]);
  const loaded = useRef<Snapshot | null>(null);

  const reload = useCallback(() => setGeneration((g) => g + 1), []);

  useEffect(() => {
    let cancelled = false;
    setStatus((s) => (s === 'ready' ? s : 'loading'));
    const query = runId ? `?run_id=${encodeURIComponent(runId)}` : '';
    getJson<Snapshot>(`/api/snapshot${query}`)
      .then((loadedSnapshot) => {
        if (cancelled) return;
        let next = loadedSnapshot;
        for (const event of buffer.current) {
          if (event.cursor > next.cursor && event.payload.record && belongs(next, event)) {
            next = upsert(next, event.payload.record, event.payload.record_type);
          }
        }
        buffer.current = [];
        loaded.current = next;
        setSnapshot(next);
        setStatus('ready');
        setError(null);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setStatus('error');
        setError(e.message || 'The snapshot could not be loaded.');
      });
    return () => {
      cancelled = true;
    };
  }, [runId, generation]);

  useEffect(() => {
    const offEvent = connection.onEvent((event) => {
      const current = loaded.current;
      if (!current) {
        buffer.current.push(event);
        return;
      }
      const p = event.payload;
      if (!runId && p.record_type === 'run' && p.event_type === 'RUN_CREATED' && p.record_id !== current.run_id) {
        reload(); // a new run started: follow it
        return;
      }
      if (p.record && belongs(current, event) && event.cursor > current.cursor) {
        const next = upsert(current, p.record, p.record_type);
        if (next !== current) {
          loaded.current = next;
          setSnapshot(next);
        }
      }
    });
    const offReload = connection.onReloadNeeded(() => reload());
    return () => {
      offEvent();
      offReload();
    };
  }, [connection, runId, reload]);

  return useMemo(() => ({
    status, snapshot, error, reload,
    records: <T extends RecordType>(type: T) => ((snapshot?.records[type] ?? []) as RecordTypes[T][]),
    record: <T extends RecordType>(type: T, id: Uuid | null | undefined) =>
      id ? ((snapshot?.records[type] ?? []) as RecordTypes[T][]).find((r) => r.id === id) : undefined,
  }), [status, snapshot, error, reload]);
}

function belongs(snapshot: Snapshot, event: EventMessage): boolean {
  const runId = event.payload.run_id;
  return runId === null || runId === snapshot.run_id || event.payload.record_type === 'run';
}

// ---------------------------------------------------------------- session

export interface SessionState {
  status: 'loading' | 'ready' | 'error';
  session: SessionInfo | null;
  error: string | null;
  login(stationId: string, pin: string): Promise<SessionInfo>;
  logout(): Promise<void>;
  refresh(): void;
  /** True if the signed-in station holds this role. */
  can(role: string): boolean;
}

/** Who is signed in at this screen (from the fp_session cookie). */
export function useSession(): SessionState {
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [status, setStatus] = useState<SessionState['status']>('loading');
  const [error, setError] = useState<string | null>(null);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let cancelled = false;
    getJson<SessionInfo>('/api/session')
      .then((s) => {
        if (!cancelled) {
          setSession(s);
          setStatus('ready');
        }
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setStatus('error');
          setError(e.message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [generation]);

  const login = useCallback(async (stationId: string, pin: string) => {
    const s = await sendJson<SessionInfo>('/api/session/station', { station_id: stationId, pin });
    setSession(s);
    setStatus('ready');
    return s;
  }, []);
  const logout = useCallback(async () => {
    const s = await sendJson<SessionInfo>('/api/session', undefined, 'DELETE');
    setSession(s);
  }, []);
  const refresh = useCallback(() => setGeneration((g) => g + 1), []);

  return {
    status, session, error, login, logout, refresh,
    can: (role: string) => Boolean(session?.roles.includes(role as never)),
  };
}

// ---------------------------------------------------------------- automation log

/** The automation log (receipts the automation actor got), newest first; refreshed when an automatic event arrives. */
export function useAutomationLog(runId?: Uuid | null, limit = 100) {
  const connection = useConnection();
  const [entries, setEntries] = useState<AutomationLogEntry[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const load = useCallback(() => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (runId) params.set('run_id', runId);
    getJson<AutomationLogEntry[]>(`/api/automation-log?${params}`)
      .then((list) => {
        setEntries(list);
        setStatus('ready');
      })
      .catch(() => setStatus('error'));
  }, [runId, limit]);

  useEffect(() => {
    load();
    const off = connection.onEvent((event) => {
      if (!event.payload.automatic || timer.current) return;
      timer.current = setTimeout(() => {
        timer.current = null;
        load();
      }, 500);
    });
    return () => {
      off();
      if (timer.current) clearTimeout(timer.current);
      timer.current = null;
    };
  }, [connection, load]);

  return { entries, status, reload: load };
}

// ---------------------------------------------------------------- commands

export interface CommandState {
  pending: boolean;
  receipt: Receipt | null;
  /** Plain words if the last command was refused, else null. */
  problem: string | null;
  send<P extends object>(type: string, payload: P, expectedRevision?: number | null, options?: SendOptions): Promise<Receipt>;
}

/** sendCommand with pending and refused states for a button or form. */
export function useCommand(): CommandState {
  const [pending, setPending] = useState(false);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const send = useCallback(async <P extends object>(type: string, payload: P, expectedRevision: number | null = null,
    options?: SendOptions) => {
    setPending(true);
    try {
      const result = await sendCommand(type, payload, expectedRevision, options);
      setReceipt(result);
      return result;
    } finally {
      setPending(false);
    }
  }, []);
  return { pending, receipt, problem: receipt ? receiptProblem(receipt) : null, send };
}
