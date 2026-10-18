package fuelprint.alerts;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the phone running "SMS Gateway for Android" in Local Server mode. It records
 * every request and answers with a status chosen by the test.
 */
final class MockGatewayServer implements AutoCloseable {

    record Seen(String method, String path, String authorization, String contentType, String body) {
    }

    final List<Seen> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private volatile Function<Seen, Integer> answer = seen -> 202;
    private volatile long delayMs;

    MockGatewayServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "mock-sms-gateway");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen seen = new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body);
            requests.add(seen);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = answer.apply(seen);
            byte[] reply = ("{\"id\":\"mock\",\"state\":\"Pending\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, reply.length);
                exchange.getResponseBody().write(reply);
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void answerWith(int status) {
        answer = seen -> status;
    }

    void answerWith(Function<Seen, Integer> function) {
        answer = function;
    }

    void delay(long millis) {
        delayMs = millis;
    }

    void reset() {
        requests.clear();
        delayMs = 0;
        answer = seen -> 202;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
