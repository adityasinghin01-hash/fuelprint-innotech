package fuelprint.core.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.api.ServerMode;
import fuelprint.core.command.CommittedEvent;
import fuelprint.core.command.EventsCommitted;
import fuelprint.core.store.EventLog;
import fuelprint.core.store.EventRow;
import fuelprint.core.store.RecordStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** The per-client queue: superseded ticks are dropped, events never; a client that stays slow is closed. */
class LiveHubQueueTest {

    static LiveHub hub(int maxQueued) {
        EventLog log = mock(EventLog.class);
        when(log.lastCursor()).thenReturn(0L);
        ServerMode mode = mock(ServerMode.class);
        return new LiveHub(log, mock(RecordStore.class), new ObjectMapper(), Clock.systemUTC(), mode, maxQueued, 1000);
    }

    static EventsCommitted event(long cursor) {
        EventRow row = new EventRow(cursor, "TEST", "test", "id-" + cursor, 1, null, null, "now", "automation", null);
        return new EventsCommitted(List.of(new CommittedEvent(row, JsonNodeFactory.instance.objectNode(), true)));
    }

    @Test
    void aSlowClientLosesOldTicksButNoEvent() throws Exception {
        LiveHub hub = hub(1000);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        CountDownLatch release = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS); // the network is stuck until released
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());
        hub.connect(session, null, null);

        for (int i = 1; i <= 50; i++) {
            hub.broadcastTick(null, (double) i, JsonNodeFactory.instance.objectNode().put("n", i));
            if (i % 5 == 0) {
                hub.on(event(i));
            }
        }
        release.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && sent.stream().noneMatch(s -> s.contains("\"n\":50"))) {
            Thread.sleep(10);
        }
        List<String> events = sent.stream().filter(s -> s.contains("\"message_type\":\"event\"")).toList();
        List<String> ticks = sent.stream().filter(s -> s.contains("\"message_type\":\"tick\"")).toList();
        assertThat(events).hasSize(10);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i)).contains("\"cursor\":" + (i + 1) * 5 + ",");
        }
        assertThat(ticks.size()).as("superseded ticks dropped").isLessThan(5);
        assertThat(ticks.get(ticks.size() - 1)).contains("\"n\":50");
    }

    @Test
    void aClientThatFallsTooFarBehindIsClosed() throws Exception {
        LiveHub hub = hub(20);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        CountDownLatch never = new CountDownLatch(1);
        doAnswer(inv -> {
            never.await(2, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any());
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(inv -> {
            closed.countDown();
            return null;
        }).when(session).close(any(CloseStatus.class));
        hub.connect(session, null, null);
        for (int i = 1; i <= 40; i++) {
            hub.on(event(i));
        }
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(hub.clientCount()).isZero();
        never.countDown();
    }

    @Test
    void aBacklogOfEventsNeverStarvesTheLatestTick() throws Exception {
        LiveHub hub = hub(100_000);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        CountDownLatch gate = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            gate.await(5, TimeUnit.SECONDS);
            Thread.sleep(1); // a slow client: 1 ms per message
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());
        hub.connect(session, null, null);
        for (int i = 1; i <= 3000; i++) {
            hub.on(event(i)); // 3000 events waiting
        }
        hub.broadcastTick(null, 1.0, JsonNodeFactory.instance.objectNode().put("n", 1));
        gate.countDown();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && sent.stream().noneMatch(s -> s.contains("\"message_type\":\"tick\""))) {
            Thread.sleep(5);
        }
        int firstTickAt = 0;
        for (; firstTickAt < sent.size(); firstTickAt++) {
            if (sent.get(firstTickAt).contains("\"message_type\":\"tick\"")) {
                break;
            }
        }
        assertThat(firstTickAt).as("the tick is sent within the first few messages, not after 3000 events")
                .isLessThan(10);
    }

    @Test
    void aCatchUpLongerThanTheLiveQueueLimitDoesNotCloseTheClient() throws Exception {
        EventLog log = mock(EventLog.class);
        when(log.lastCursor()).thenReturn(300L);
        List<EventRow> all = new java.util.ArrayList<>();
        for (long c = 1; c <= 300; c++) {
            all.add(new EventRow(c, "TEST", "test", "id-" + c, 1, null, null, "now", "automation", null));
        }
        when(log.after(any(Long.class), any(Integer.class))).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            return all.stream().filter(e -> e.cursor() > after).limit((int) inv.getArgument(1)).toList();
        });
        RecordStore store = mock(RecordStore.class);
        when(store.get(any(), any(), any(Long.class))).thenReturn(java.util.Optional.empty());
        LiveHub hub = new LiveHub(log, store, new ObjectMapper(), Clock.systemUTC(), mock(ServerMode.class), 50, 1000);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        List<String> sent = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            sent.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());

        hub.connect(session, null, 0L);

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && sent.stream().filter(s -> s.contains("\"event\"")).count() < 300) {
            Thread.sleep(10);
        }
        assertThat(sent.stream().filter(s -> s.contains("\"message_type\":\"event\"")).count()).isEqualTo(300);
        assertThat(hub.clientCount()).isEqualTo(1);
    }
}
