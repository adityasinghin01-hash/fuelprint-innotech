package fuelprint.core.live;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.ServerMode;
import fuelprint.core.WallTime;
import fuelprint.core.command.CommittedEvent;
import fuelprint.core.command.EventsCommitted;
import fuelprint.core.privacy.PhoneGuard;
import fuelprint.core.store.EventLog;
import fuelprint.core.store.EventRow;
import fuelprint.core.store.RecordStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The {@code /ws/live} hub (AGREEMENT section 3). Every message uses the envelope
 * {@code schema_version, message_type, run_id, server_epoch, cursor, sim_time_s, server_time_utc, payload}.
 *
 * <ul>
 *   <li>{@code hello} first, with the {@code server_epoch} made at start-up. Its payload says
 *       {@code reload_snapshot: true} when {@code after} is older than the kept window (or newer than the server
 *       knows); the browser then reloads the snapshot.</li>
 *   <li>Then the events after {@code after}, read from the database, then live events, in cursor order, with no gap
 *       and no repeat. Each event payload carries the event fields plus {@code automatic} and the saved
 *       {@code record}. Only committed events are ever sent.</li>
 *   <li>{@code tick}s, 10 per second, from the world loop or the replay.</li>
 * </ul>
 * With {@code run_id}, a client gets that run's events, events of records not tied to a run, and every
 * {@code run} record event (so it learns about a reset). Each client has a bounded queue: a newer tick replaces an
 * unsent one, events are never dropped, and a client that falls more than {@code fp.live.max-queued-events}
 * behind is closed (it reconnects with {@code after=} and catches up from the database).
 */
@Component
public class LiveHub {

    public static final String SCHEMA_VERSION = "fp/1";

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

    private final String serverEpoch = UUID.randomUUID().toString();
    private final EventLog eventLog;
    private final RecordStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ServerMode mode;
    private final int maxQueuedEvents;
    private final long keptEvents;
    private final AtomicLong lastCursor = new AtomicLong(-1);
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService senders = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "fp-live-send");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong ticksSent = new AtomicLong();

    public LiveHub(EventLog eventLog, RecordStore store, ObjectMapper mapper, Clock clock, ServerMode mode,
                   @Value("${fp.live.max-queued-events:5000}") int maxQueuedEvents,
                   @Value("${fp.live.kept-events:20000}") long keptEvents) {
        this.eventLog = eventLog;
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
        this.mode = mode;
        this.maxQueuedEvents = maxQueuedEvents;
        this.keptEvents = keptEvents;
    }

    /** UUID made when this server started; it changes on every restart. */
    public String serverEpoch() {
        return serverEpoch;
    }

    /** The highest committed cursor. */
    public long cursor() {
        long known = lastCursor.get();
        if (known < 0) {
            lastCursor.compareAndSet(-1, eventLog.lastCursor());
            known = lastCursor.get();
        }
        return known;
    }

    /** Number of clients connected now. */
    public int clientCount() {
        return clients.size();
    }

    /** Ticks handed to clients so far (for health checks and tests). */
    public long ticksSent() {
        return ticksSent.get();
    }

    // --- messages ---

    ObjectNode envelope(String type, String runId, long cursor, Double simTimeS, ObjectNode payload) {
        ObjectNode env = JsonNodeFactory.instance.objectNode();
        env.put("schema_version", SCHEMA_VERSION);
        env.put("message_type", type);
        env.put("run_id", runId);
        env.put("server_epoch", serverEpoch);
        env.put("cursor", cursor);
        if (simTimeS == null) {
            env.putNull("sim_time_s");
        } else {
            env.put("sim_time_s", simTimeS);
        }
        env.put("server_time_utc", WallTime.now(clock));
        env.set("payload", payload);
        return env;
    }

    /** The event envelope for one committed event. */
    ObjectNode eventMessage(EventRow event, ObjectNode record, boolean automatic) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("cursor", event.cursor());
        payload.put("event_type", event.eventType());
        payload.put("record_type", event.recordType());
        payload.put("record_id", event.recordId());
        payload.put("revision", event.revision());
        payload.put("run_id", event.runId());
        if (event.simTimeS() == null) {
            payload.putNull("sim_time_s");
        } else {
            payload.put("sim_time_s", event.simTimeS());
        }
        payload.put("wall_utc", event.wallUtc());
        payload.put("actor", event.actor());
        payload.put("automatic", automatic);
        payload.set("record", record);
        return envelope("event", event.runId(), event.cursor(), event.simTimeS(), payload);
    }

    String write(ObjectNode message, String where) {
        try {
            return PhoneGuard.mask(mapper.writeValueAsString(message), where);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a live message", e);
        }
    }

    // --- broadcasting ---

    @EventListener
    public void on(EventsCommitted committed) {
        for (CommittedEvent item : committed.events()) {
            EventRow event = item.event();
            lastCursor.accumulateAndGet(event.cursor(), Math::max);
            if (clients.isEmpty()) {
                continue;
            }
            Outgoing message = new Outgoing(event.cursor(), event.runId(), event.recordType(),
                    write(eventMessage(event, item.record(), item.automatic()), "a live event"));
            for (Client client : clients) {
                client.offerLiveEvent(message);
            }
        }
    }

    /** Sends a tick to every client (replacing any tick it has not sent yet). Not saved. */
    public void broadcastTick(String runId, Double simTimeS, ObjectNode payload) {
        if (clients.isEmpty()) {
            return;
        }
        String text = write(envelope("tick", runId, cursor(), simTimeS, payload), "a tick");
        for (Client client : clients) {
            client.offerTick(text);
        }
    }

    // --- clients ---

    /**
     * Registers a new socket client: hello, the database replay after {@code after} (null = no replay), then live.
     *
     * @param runFilter only this run's events (plus run-independent and run record events); null for all
     */
    void connect(WebSocketSession session, String runFilter, Long after) {
        Client client = new Client(session, runFilter);
        clients.add(client); // buffers live events from now on
        long now = cursor();
        boolean reload = after != null && (after > now || now - after > keptEvents);
        ObjectNode hello = JsonNodeFactory.instance.objectNode();
        hello.put("mode", mode.isReplay() ? "replay" : "live");
        if (after == null) {
            hello.putNull("after");
        } else {
            hello.put("after", after);
        }
        hello.put("reload_snapshot", reload);
        hello.put("kept_events", keptEvents);
        client.offerDirect(write(envelope("hello", runFilter, now, null, hello), "hello"));

        long replayedTo = after == null || reload ? now : after;
        if (after != null && !reload) {
            long from = after;
            while (true) {
                List<EventRow> page = eventLog.after(from, 500);
                if (page.isEmpty()) {
                    break;
                }
                for (EventRow event : page) {
                    if (!client.wants(event.runId(), event.recordType())) {
                        continue;
                    }
                    ObjectNode record = store.get(event.recordType(), event.recordId(), event.revision())
                            .map(r -> r.body()).orElse(null);
                    boolean automatic = fuelprint.core.command.Session.AUTOMATION_ACTOR.equals(event.actor());
                    client.offerDirect(write(eventMessage(event, record, automatic), "a replayed event"));
                }
                from = page.get(page.size() - 1).cursor();
                replayedTo = from;
            }
        }
        client.goLive(replayedTo);
    }

    void disconnect(WebSocketSession session) {
        clients.removeIf(c -> c.session == session);
    }

    @PreDestroy
    void shutdown() {
        senders.shutdownNow();
        for (Client client : clients) {
            client.close(CloseStatus.GOING_AWAY);
        }
    }

    /** A message on its way to clients. */
    record Outgoing(long cursor, String runId, String recordType, String text) {
    }

    /** One connected socket with its own queue and a single sender at a time. */
    final class Client {
        final WebSocketSession session;
        final String runFilter;
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private final List<Outgoing> buffered = new ArrayList<>();
        private final AtomicReference<String> pendingTick = new AtomicReference<>();
        private final AtomicBoolean draining = new AtomicBoolean();
        private int liveQueued;
        private boolean live;
        private long liveFrom;
        private volatile boolean closed;

        Client(WebSocketSession session, String runFilter) {
            this.session = session;
            this.runFilter = runFilter;
        }

        boolean wants(String runId, String recordType) {
            return runFilter == null || runId == null || runFilter.equals(runId) || "run".equals(recordType);
        }

        void offerLiveEvent(Outgoing message) {
            if (!wants(message.runId(), message.recordType())) {
                return;
            }
            synchronized (this) {
                if (!live) {
                    buffered.add(message);
                    return;
                }
                if (message.cursor() <= liveFrom) {
                    return;
                }
                enqueue(message.text());
            }
            drain();
        }

        /** Hello and catch-up replay: not counted against the live limit (replay is bounded by kept_events). */
        void offerDirect(String text) {
            synchronized (this) {
                queue.addLast(text);
            }
            drain();
        }

        void goLive(long replayedTo) {
            synchronized (this) {
                liveFrom = replayedTo;
                for (Outgoing message : buffered) {
                    if (message.cursor() > replayedTo) {
                        enqueue(message.text());
                        liveFrom = message.cursor();
                    }
                }
                buffered.clear();
                live = true;
            }
            drain();
        }

        void offerTick(String text) {
            synchronized (this) {
                if (!live) {
                    return;
                }
            }
            pendingTick.set(text);
            drain();
        }

        /** Caller holds the monitor. */
        private void enqueue(String text) {
            if (liveQueued >= maxQueuedEvents) {
                log.warn("Live client is too slow ({} events waiting); closing it so it catches up on reconnect",
                        queue.size());
                queue.clear();
                liveQueued = 0;
                closed = true;
                senders.execute(() -> close(new CloseStatus(4008, "too slow; reconnect with after=")));
                return;
            }
            queue.addLast(text);
            liveQueued++;
        }

        private void drain() {
            if (closed || !draining.compareAndSet(false, true)) {
                return;
            }
            try {
                senders.execute(this::sendAll);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                draining.set(false);
            }
        }

        private void sendAll() {
            try {
                while (!closed) {
                    // The newest tick goes first, then one queued message: a client with a long event backlog still
                    // sees the world move (events are never dropped, only superseded ticks are).
                    String tick = pendingTick.getAndSet(null);
                    String next;
                    synchronized (this) {
                        next = queue.pollFirst();
                        if (next != null && liveQueued > 0) {
                            liveQueued--;
                        }
                    }
                    if (tick == null && next == null) {
                        break;
                    }
                    if (tick != null) {
                        session.sendMessage(new TextMessage(tick));
                        ticksSent.incrementAndGet();
                    }
                    if (next != null) {
                        session.sendMessage(new TextMessage(next));
                    }
                }
            } catch (IOException | RuntimeException e) {
                closed = true;
                clients.remove(this);
                close(CloseStatus.SESSION_NOT_RELIABLE);
                return;
            } finally {
                draining.set(false);
            }
            boolean more;
            synchronized (this) {
                more = !queue.isEmpty();
            }
            if ((more || pendingTick.get() != null) && !closed) {
                drain();
            }
        }

        void close(CloseStatus status) {
            clients.remove(this);
            try {
                if (session.isOpen()) {
                    session.close(status);
                }
            } catch (IOException | RuntimeException ignored) {
                // already gone
            }
        }
    }
}
