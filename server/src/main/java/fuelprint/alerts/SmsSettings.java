package fuelprint.alerts;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.GatewayMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The SMS settings from {@code server/config/sms.json} (never committed; {@code sms.example.json} is).
 * The default is LOG_ONLY with a daily cap of 80. A missing, unreadable or unclear file means LOG_ONLY, so a
 * mistake can never send a real SMS. The password never appears in {@link #toString()} or a log line.
 */
final class SmsSettings {

    static final int DEFAULT_DAILY_CAP = 80;
    private static final Logger log = LoggerFactory.getLogger(SmsSettings.class);

    private final GatewayMode mode;
    private final String gatewayUrl;
    private final String username;
    private final String password;
    private final int dailyCap;

    SmsSettings(GatewayMode mode, String gatewayUrl, String username, String password, int dailyCap) {
        this.mode = mode == null ? GatewayMode.LOG_ONLY : mode;
        this.gatewayUrl = gatewayUrl;
        this.username = username;
        this.password = password;
        this.dailyCap = dailyCap < 0 ? DEFAULT_DAILY_CAP : dailyCap;
    }

    static SmsSettings logOnly() {
        return new SmsSettings(GatewayMode.LOG_ONLY, null, null, null, DEFAULT_DAILY_CAP);
    }

    static SmsSettings logOnly(int dailyCap) {
        return new SmsSettings(GatewayMode.LOG_ONLY, null, null, null, dailyCap);
    }

    /** A REAL gateway with these settings (tests use this with a local mock server). */
    static SmsSettings real(String gatewayUrl, String username, String password, int dailyCap) {
        return new SmsSettings(GatewayMode.REAL, gatewayUrl, username, password, dailyCap);
    }

    /** Reads {@code file}; LOG_ONLY with the default cap if it does not exist or cannot be understood. */
    static SmsSettings load(Path file, ObjectMapper mapper) {
        if (file == null || !Files.isRegularFile(file)) {
            log.info("No SMS settings file; SMS gateway mode is LOG_ONLY (nothing is sent)");
            return logOnly();
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            String modeText = text(root, "mode");
            GatewayMode mode = "REAL".equals(modeText == null ? null : modeText.toUpperCase(Locale.ROOT))
                    ? GatewayMode.REAL : GatewayMode.LOG_ONLY;
            if (modeText != null && !modeText.equalsIgnoreCase("REAL") && !modeText.equalsIgnoreCase("LOG_ONLY")) {
                log.warn("SMS settings: unknown mode; using LOG_ONLY");
            }
            int cap = root.path("daily_cap").isIntegralNumber() ? root.path("daily_cap").asInt() : DEFAULT_DAILY_CAP;
            SmsSettings settings = new SmsSettings(mode, text(root, "gateway_url"), text(root, "username"),
                    text(root, "password"), cap);
            log.info("SMS gateway mode is {} with a daily cap of {}", settings.mode, settings.dailyCap);
            return settings;
        } catch (IOException | RuntimeException e) {
            log.warn("SMS settings file cannot be read ({}); using LOG_ONLY", e.getClass().getSimpleName());
            return logOnly();
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    GatewayMode mode() {
        return mode;
    }

    int dailyCap() {
        return dailyCap;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    /**
     * The address to POST a message to. The settings may give the phone's address ({@code http://ip:8080})
     * or the full endpoint ({@code http://ip:8080/message}); the documented local-server endpoint is
     * {@code /message}.
     */
    URI messageUri() {
        if (gatewayUrl == null) {
            return null;
        }
        try {
            String base = gatewayUrl.endsWith("/") ? gatewayUrl.substring(0, gatewayUrl.length() - 1) : gatewayUrl;
            URI uri = URI.create(base.endsWith("/message") ? base : base + "/message");
            return uri.getScheme() != null && uri.getHost() != null
                    && (uri.getScheme().equals("http") || uri.getScheme().equals("https")) ? uri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True if this gateway can be used: LOG_ONLY always, REAL only with an address, a user name and a password. */
    boolean ready() {
        return mode == GatewayMode.LOG_ONLY || (messageUri() != null && username != null && password != null);
    }

    @Override
    public String toString() {
        return "SmsSettings[mode=" + mode + ", dailyCap=" + dailyCap + "]";
    }
}
