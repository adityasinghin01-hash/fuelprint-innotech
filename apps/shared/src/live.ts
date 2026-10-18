// The /ws/live connection without React: reconnect with after=<last cursor>, the 3-second stale rule, and
// listeners for hello, tick and event messages. LiveProvider wraps it for React.
import { wsUrl } from './http';
import type { EventMessage, HelloMessage, LiveMessage, TickMessage, Uuid } from './types';

/** connecting: never had data yet. live: data in the last 3 s. stale: no data for 3 s (read only). offline: socket closed. */
export type LiveStatus = 'connecting' | 'live' | 'stale' | 'offline';

/** AGREEMENT section 3: no message for 3 seconds means stale. */
export const STALE_AFTER_MS = 3000;

export interface LiveState {
  status: LiveStatus;
  serverEpoch: Uuid | null;
  mode: 'live' | 'replay' | null;
  /** The last event cursor seen (reconnects ask for after= this). */
  cursor: number | null;
  /** The latest tick (moving things), or null before the first one. */
  tick: TickMessage | null;
  /** Wall ms of the last message of any kind. */
  lastMessageMs: number | null;
  /** How many times the socket has (re)opened. */
  connects: number;
}

export interface LiveOptions {
  /** Only this run's events (plus run-independent ones). Default: every event. */
  runId?: Uuid | null;
  /** Start after this cursor (for example the snapshot's cursor). */
  after?: number | null;
  /** WebSocket constructor (tests pass a fake). */
  socketFactory?: (url: string) => WebSocket;
  /** Reconnect delays in ms (default 500, then doubling up to 5000). */
  minDelayMs?: number;
  maxDelayMs?: number;
}

type Listener<T> = (value: T) => void;

export class LiveConnection {
  private socket: WebSocket | null = null;
  private state: LiveState;
  private readonly options: LiveOptions;
  private staleTimer: ReturnType<typeof setTimeout> | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private delay: number;
  private closedByUser = false;
  private readonly stateListeners = new Set<Listener<LiveState>>();
  private readonly eventListeners = new Set<Listener<EventMessage>>();
  private readonly tickListeners = new Set<Listener<TickMessage>>();
  private readonly helloListeners = new Set<Listener<HelloMessage>>();
  private readonly reloadListeners = new Set<Listener<string>>();

  constructor(options: LiveOptions = {}) {
    this.options = options;
    this.delay = options.minDelayMs ?? 500;
    this.state = {
      status: 'connecting', serverEpoch: null, mode: null, cursor: options.after ?? null, tick: null,
      lastMessageMs: null, connects: 0,
    };
  }

  get current(): LiveState {
    return this.state;
  }

  /** Opens the socket (and keeps reopening it until close()). */
  open(): this {
    this.closedByUser = false;
    this.connect();
    return this;
  }

  close(): void {
    this.closedByUser = true;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState <= 1) socket.close(1000, 'screen closed');
    this.update({ status: 'offline' });
  }

  /** Continue after this cursor on the next (re)connect, for example after loading a snapshot. */
  setCursor(cursor: number): void {
    this.update({ cursor });
  }

  onState(listener: Listener<LiveState>): () => void {
    return this.add(this.stateListeners, listener);
  }
  onEvent(listener: Listener<EventMessage>): () => void {
    return this.add(this.eventListeners, listener);
  }
  onTick(listener: Listener<TickMessage>): () => void {
    return this.add(this.tickListeners, listener);
  }
  onHello(listener: Listener<HelloMessage>): () => void {
    return this.add(this.helloListeners, listener);
  }
  /** Called when the screen must reload its snapshot (cursor too old, or the server restarted). */
  onReloadNeeded(listener: Listener<string>): () => void {
    return this.add(this.reloadListeners, listener);
  }

  private add<T>(set: Set<Listener<T>>, listener: Listener<T>): () => void {
    set.add(listener);
    return () => set.delete(listener);
  }

  private url(): string {
    const params = new URLSearchParams();
    if (this.options.runId) params.set('run_id', this.options.runId);
    if (this.state.cursor !== null) params.set('after', String(this.state.cursor));
    const query = params.toString();
    return wsUrl('/ws/live') + (query ? `?${query}` : '');
  }

  private connect(): void {
    if (this.closedByUser) return;
    let socket: WebSocket;
    try {
      socket = this.options.socketFactory ? this.options.socketFactory(this.url()) : new WebSocket(this.url());
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      this.delay = this.options.minDelayMs ?? 500;
      this.update({ connects: this.state.connects + 1 });
      this.armStale();
    };
    socket.onmessage = (message: MessageEvent) => this.receive(String(message.data));
    socket.onerror = () => {
      // onclose follows; nothing else to do here
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.clearStale();
      this.update({ status: 'offline' });
      this.scheduleReconnect();
    };
  }

  private scheduleReconnect(): void {
    if (this.closedByUser) return;
    const wait = this.delay;
    this.delay = Math.min(this.delay * 2, this.options.maxDelayMs ?? 5000);
    this.retryTimer = setTimeout(() => this.connect(), wait);
  }

  private receive(text: string): void {
    let message: LiveMessage;
    try {
      message = JSON.parse(text) as LiveMessage;
    } catch {
      return;
    }
    const now = Date.now();
    this.armStale();
    if (message.message_type === 'hello') {
      const hello = message as HelloMessage;
      const epochChanged = this.state.serverEpoch !== null && this.state.serverEpoch !== hello.server_epoch;
      this.update({ status: 'live', serverEpoch: hello.server_epoch, mode: hello.payload.mode, lastMessageMs: now });
      this.helloListeners.forEach((l) => l(hello));
      if (hello.payload.reload_snapshot) {
        this.update({ cursor: hello.cursor });
        this.reloadListeners.forEach((l) => l('The server no longer has the missed events.'));
      } else if (epochChanged) {
        this.reloadListeners.forEach((l) => l('The server restarted.'));
      }
      return;
    }
    if (message.message_type === 'event') {
      const event = message as EventMessage;
      if (this.state.cursor !== null && event.cursor <= this.state.cursor) return; // already seen
      this.update({ status: 'live', cursor: event.cursor, lastMessageMs: now });
      this.eventListeners.forEach((l) => l(event));
      return;
    }
    if (message.message_type === 'tick') {
      const tick = message as TickMessage;
      this.update({ status: 'live', tick, lastMessageMs: now });
      this.tickListeners.forEach((l) => l(tick));
    }
  }

  private armStale(): void {
    this.clearStale();
    this.staleTimer = setTimeout(() => {
      if (this.state.status === 'live' || this.state.status === 'connecting') this.update({ status: 'stale' });
    }, STALE_AFTER_MS);
  }

  private clearStale(): void {
    if (this.staleTimer) clearTimeout(this.staleTimer);
    this.staleTimer = null;
  }

  private clearTimers(): void {
    this.clearStale();
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  private update(change: Partial<LiveState>): void {
    this.state = { ...this.state, ...change };
    this.stateListeners.forEach((l) => l(this.state));
  }
}

/** True when buttons that change something must be disabled (anything but fresh live data). */
export function isReadOnly(status: LiveStatus): boolean {
  return status !== 'live';
}
