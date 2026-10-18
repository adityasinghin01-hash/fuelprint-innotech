package fuelprint.alerts;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.GatewayMode;
import fuelprint.api.SendResult;
import fuelprint.api.SendStatus;
import fuelprint.api.SmsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one SMS (AGREEMENT section 13).
 *
 * <p><b>LOG_ONLY</b> (default, and always in tests): writes one log line with the masked number and the
 * text, sends nothing.
 *
 * <p><b>REAL</b>: the open-source "SMS Gateway for Android" app (github.com/capcom6/android-sms-gateway)
 * in Local Server mode. As documented in its README ("Local Server") and at docs.sms-gate.app, one message
 * is
 * <pre>
 * POST http://&lt;device_local_ip&gt;:8080/message
 * Authorization: Basic base64(username:password)
 * Content-Type: application/json
 * {"textMessage":{"text":"..."},"phoneNumbers":["+919812345678"]}
 * </pre>
 * The gateway answers {@code 202 Accepted} when it queued the message. One request is sent per recipient
 * so each recipient is counted on its own. A failed send (no answer, timeout, any answer except 2xx) is
 * tried again twice, then counted as failed. Connect and read timeouts are 5 seconds each try.
 *
 * <p>The number is never logged, stored or returned.
 */
final class AlertsSmsGateway implements SmsGateway {

    static final int MAX_ATTEMPTS = 3; // 1 try + 2 retries
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(AlertsSmsGateway.class);

    private final SmsSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final long retryDelayMs;

    AlertsSmsGateway(SmsSettings settings, ObjectMapper mapper, long retryDelayMs) {
        this.settings = settings;
        this.mapper = mapper;
        this.retryDelayMs = retryDelayMs;
        // HTTP/1.1 only: the phone app is a small plain HTTP server, so no h2c upgrade attempt.
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    }

    GatewayMode mode() {
        return settings.mode();
    }

    @Override
    public SendResult send(String phoneE164, String text) {
        if (settings.mode() == GatewayMode.LOG_ONLY) {
            log.info("SMS (LOG_ONLY, not sent) to {}: {}", PhoneNumbers.mask(phoneE164), text);
            return new SendResult(SendStatus.LOGGED_ONLY, GatewayMode.LOG_ONLY, 1, null);
        }
        if (!settings.ready()) {
            log.warn("SMS gateway is REAL but its settings are incomplete; nothing was sent");
            return new SendResult(SendStatus.FAILED, GatewayMode.REAL, 0, "GATEWAY_NOT_CONFIGURED");
        }
        HttpRequest request;
        try {
            request = buildRequest(phoneE164, text);
        } catch (RuntimeException e) {
            log.warn("SMS request could not be built ({})", e.getClass().getSimpleName());
            return new SendResult(SendStatus.FAILED, GatewayMode.REAL, 0, "REQUEST_NOT_BUILT");
        }
        String reason = "UNKNOWN";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            reason = tryOnce(request);
            if (reason == null) {
                log.info("SMS to {} accepted by the gateway (attempt {})", PhoneNumbers.mask(phoneE164), attempt);
                return new SendResult(SendStatus.SENT, GatewayMode.REAL, attempt, null);
            }
            log.warn("SMS to {} failed on attempt {} of {}: {}", PhoneNumbers.mask(phoneE164), attempt, MAX_ATTEMPTS,
                    reason);
            if (attempt < MAX_ATTEMPTS && !pause()) {
                return new SendResult(SendStatus.FAILED, GatewayMode.REAL, attempt, "INTERRUPTED");
            }
        }
        return new SendResult(SendStatus.FAILED, GatewayMode.REAL, MAX_ATTEMPTS, reason);
    }

    /** The documented local-server request for one recipient. */
    private HttpRequest buildRequest(String phoneE164, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("textMessage").put("text", text);
        body.putArray("phoneNumbers").add(phoneE164);
        String credentials = settings.username() + ":" + settings.password();
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(settings.messageUri())
                .timeout(TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    /** One try. Returns null on success, otherwise a short reason without any private value. */
    private String tryOnce(HttpRequest request) {
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300 ? null : "HTTP_" + status;
        } catch (HttpTimeoutException e) {
            return "TIMEOUT";
        } catch (IOException e) {
            return "NO_CONNECTION";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
    }

    private boolean pause() {
        if (retryDelayMs <= 0) {
            return !Thread.currentThread().isInterrupted();
        }
        try {
            Thread.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
