package fuelprint.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A headless {@code /ws/live} client for tests: collects every message (raw text and parsed). */
public final class LiveClient implements WebSocket.Listener, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final List<String> raw = new CopyOnWriteArrayList<>();
    public final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    private final StringBuilder partial = new StringBuilder();
    private WebSocket socket;
    public volatile Integer closeCode;

    public static LiveClient connect(int port, String query) throws Exception {
        LiveClient client = new LiveClient();
        client.socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/live" + (query == null ? "" : "?" + query)), client)
                .get(10, TimeUnit.SECONDS);
        return client;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            raw.add(text);
            try {
                messages.add(MAPPER.readTree(text));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeCode = statusCode;
        return null;
    }

    public List<JsonNode> ofType(String type) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode message : messages) {
            if (type.equals(message.path("message_type").asText())) {
                out.add(message);
            }
        }
        return out;
    }

    /** Waits up to {@code seconds} until some message matches. */
    public JsonNode await(Predicate<JsonNode> condition, double seconds) throws InterruptedException {
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < deadline) {
            for (JsonNode message : messages) {
                if (condition.test(message)) {
                    return message;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No matching live message within " + seconds + " s; got " + messages.size());
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }
}
