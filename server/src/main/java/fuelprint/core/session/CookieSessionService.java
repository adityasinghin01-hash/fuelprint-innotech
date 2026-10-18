package fuelprint.core.session;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import fuelprint.api.SessionService;
import fuelprint.core.WallTime;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The core {@link SessionService} (CORE-API section 2). The {@code fp_session} cookie holds a 256-bit random
 * secret, which is also {@link Session#sessionId()}; the database keeps only its SHA-256, so sessions survive a
 * restart. Stations get their configured roles, visitors only {@code visitor}, guests only {@code guest}. No
 * session can ever be the automation actor (the {@link Session} factories and the table both refuse it).
 */
@Component
public class CookieSessionService implements SessionService {

    public static final String COOKIE = "fp_session";
    static final long STATION_MS = 12L * 3600_000;
    static final long VISITOR_MS = 2L * 3600_000;
    static final long GUEST_MS = 12L * 3600_000;
    private static final Pattern LABEL = Pattern.compile("[a-z0-9][a-z0-9_@.-]{0,63}");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> allowedOrigins;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Session session, long expiresMs) {
    }

    public CookieSessionService(JdbcTemplate jdbc, Clock clock,
                                @Value("${fp.allowed-origins:}") List<String> allowedOrigins) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.allowedOrigins = allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Override
    public Optional<Session> resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                return resolveToken(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** The live session for a cookie value. */
    public Optional<Session> resolveToken(String token) {
        if (token == null || token.length() < 20 || token.length() > 128) {
            return Optional.empty();
        }
        String hash = hash(token);
        long now = clock.millis();
        Cached cached = cache.get(hash);
        if (cached != null) {
            if (cached.expiresMs() > now) {
                return Optional.of(cached.session());
            }
            cache.remove(hash);
        }
        List<Session> found = jdbc.query("SELECT kind, actor_label, station_id, roles, expires_ms FROM sessions "
                        + "WHERE token_hash = ? AND revoked = 0 AND expires_ms > ?",
                (rs, n) -> {
                    Set<Role> roles = parseRoles(rs.getString("roles"));
                    Session session = "STATION".equals(rs.getString("kind"))
                            ? Session.station(token, rs.getString("station_id"), roles)
                            : Session.human(token, rs.getString("actor_label"), roles);
                    cache.put(hash, new Cached(session, rs.getLong("expires_ms")));
                    return session;
                }, hash, now);
        return found.stream().findFirst();
    }

    @Override
    public Session createStation(String stationId, Set<String> roles) {
        if (stationId == null || !LABEL.matcher(stationId).matches()) {
            throw new IllegalArgumentException("Station id must be lower case letters, digits and dashes");
        }
        Set<Role> parsed = EnumSet.noneOf(Role.class);
        for (String role : roles) {
            parsed.add(Role.fromWire(role).orElseThrow(() -> new IllegalArgumentException("Unknown role " + role)));
        }
        Session session = Session.station(newToken(), stationId, parsed);
        save(session, "STATION", stationId, STATION_MS);
        return session;
    }

    @Override
    public Session createVisitor(String visitorLabel) {
        if (visitorLabel == null || !LABEL.matcher(visitorLabel).matches()) {
            throw new IllegalArgumentException("Visitor label must be lower case letters, digits and dashes");
        }
        Session session = Session.human(newToken(), visitorLabel, Set.of(Role.VISITOR));
        save(session, "VISITOR", null, VISITOR_MS);
        return session;
    }

    @Override
    public Session createGuest() {
        String label = "guest-" + HexFormat.of().formatHex(randomBytes(2));
        Session session = Session.human(newToken(), label, Set.of(Role.GUEST));
        save(session, "GUEST", null, GUEST_MS);
        return session;
    }

    @Override
    public void writeCookie(HttpServletResponse response, Session session) {
        Long expires = jdbc.query("SELECT expires_ms FROM sessions WHERE token_hash = ?",
                (rs, n) -> rs.getLong(1), hash(session.sessionId())).stream().findFirst().orElse(null);
        long maxAgeS = expires == null ? 0 : Math.max(0, (expires - clock.millis()) / 1000);
        ResponseCookie cookie = ResponseCookie.from(COOKIE, session.sessionId())
                .httpOnly(true).sameSite("Lax").path("/").maxAge(maxAgeS).build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    /** Ends the session of this request (if any) and clears the cookie. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        resolve(request).ifPresent(session -> {
            String hash = hash(session.sessionId());
            jdbc.update("UPDATE sessions SET revoked = 1 WHERE token_hash = ?", hash);
            cache.remove(hash);
        });
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, "").httpOnly(true).sameSite("Lax").path("/")
                .maxAge(0).build().toString());
    }

    /**
     * Same-origin check for POSTs and socket handshakes. A request without {@code Origin} (not a browser, or a
     * same-origin GET) is allowed; an {@code Origin} must match the {@code Host} the request was sent to, or be
     * listed in {@code fp.allowed-origins}.
     */
    @Override
    public boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        String normalised = origin.trim().toLowerCase(Locale.ROOT);
        if (allowedOrigins.contains(normalised)) {
            return true;
        }
        String host = request.getHeader("Host");
        if (host == null || normalised.equals("null")) {
            return false;
        }
        try {
            URI uri = URI.create(normalised);
            if (uri.getHost() == null) {
                return false;
            }
            String originHost = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            String requestHost = host.trim().toLowerCase(Locale.ROOT);
            return originHost.equals(requestHost) || withDefaultPort(originHost, uri.getScheme()).equals(withDefaultPort(requestHost, uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String withDefaultPort(String hostPort, String scheme) {
        if (hostPort.contains(":") && !hostPort.startsWith("[")) {
            return hostPort;
        }
        return hostPort + ":" + ("https".equals(scheme) ? 443 : 80);
    }

    private void save(Session session, String kind, String stationId, long lifetimeMs) {
        String roles = session.roles().stream().map(Role::wire).sorted().collect(Collectors.joining(","));
        jdbc.update("INSERT INTO sessions (token_hash, kind, actor_label, station_id, roles, created_utc, expires_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                hash(session.sessionId()), kind, session.actorLabel(), stationId, roles, WallTime.now(clock),
                clock.millis() + lifetimeMs);
    }

    private static Set<Role> parseRoles(String roles) {
        Set<Role> parsed = EnumSet.noneOf(Role.class);
        Arrays.stream(roles.split(",")).forEach(r -> Role.fromWire(r).ifPresent(parsed::add));
        return parsed;
    }

    private String newToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
