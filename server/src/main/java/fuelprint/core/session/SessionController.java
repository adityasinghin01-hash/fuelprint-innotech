package fuelprint.core.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session endpoints owned by the core (CORE-API section 2): station login with a PIN, guest sessions for
 * {@code /alerts/}, "who am I", the station list for the login form, and logout. {@code /api/session/join} belongs
 * to field.
 */
@RestController
public class SessionController {

    /** Guests: at most 10 sessions per minute per IP (brief K6). */
    static final int GUEST_PER_MINUTE = 10;
    /** Station logins: at most 10 tries per minute per IP, to slow PIN guessing. */
    static final int STATION_TRIES_PER_MINUTE = 10;

    private final CookieSessionService sessions;
    private final StationDirectory stations;
    private final RateLimiter guestLimit = new RateLimiter(GUEST_PER_MINUTE, 60_000, System::currentTimeMillis);
    private final RateLimiter stationLimit = new RateLimiter(STATION_TRIES_PER_MINUTE, 60_000, System::currentTimeMillis);

    public SessionController(CookieSessionService sessions, StationDirectory stations) {
        this.sessions = sessions;
        this.stations = stations;
    }

    /** Who am I: {@code signed_in}, {@code actor_label}, {@code station_id}, {@code roles}. Never the secret. */
    @GetMapping("/api/session")
    public ObjectNode whoAmI(HttpServletRequest request) {
        return describe(sessions.resolve(request).orElse(Session.anonymous()));
    }

    /** The stations for the login form, without their PINs. */
    @GetMapping("/api/session/stations")
    public List<Map<String, Object>> stationList() {
        return stations.stations().stream().map(s -> Map.<String, Object>of(
                "station_id", s.stationId(), "name", s.name(),
                "roles", s.roles().stream().map(Role::wire).sorted().toList())).collect(Collectors.toList());
    }

    /** Laptop login: {@code {"station_id": "laptop-1", "pin": "...."}}. Sets the {@code fp_session} cookie. */
    @PostMapping("/api/session/station")
    public ResponseEntity<ObjectNode> station(@RequestBody(required = false) JsonNode body, HttpServletRequest request,
                                              HttpServletResponse response) {
        if (!stationLimit.tryAcquire(request.getRemoteAddr())) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many login tries. Wait a minute.");
        }
        String stationId = body == null ? null : body.path("station_id").asText(null);
        String pin = body == null ? null : body.path("pin").asText(null);
        Optional<StationDirectory.Station> station = stations.check(stationId, pin);
        if (station.isEmpty()) {
            return problem(HttpStatus.UNAUTHORIZED, "WRONG_PIN", "That station and PIN do not match.");
        }
        Session session = sessions.createStation(station.get().stationId(),
                station.get().roles().stream().map(Role::wire).collect(Collectors.toSet()));
        sessions.writeCookie(response, session);
        return ResponseEntity.ok(describe(session));
    }

    /** A guest session (role {@code guest}) for the {@code /alerts/} page. Rate limited per IP. */
    @PostMapping("/api/session/guest")
    public ResponseEntity<ObjectNode> guest(HttpServletRequest request, HttpServletResponse response) {
        Optional<Session> existing = sessions.resolve(request);
        if (existing.isPresent() && existing.get().roles().contains(Role.GUEST)) {
            return ResponseEntity.ok(describe(existing.get()));
        }
        if (!guestLimit.tryAcquire(request.getRemoteAddr())) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many sign-ups from this network. Wait a minute.");
        }
        Session session = sessions.createGuest();
        sessions.writeCookie(response, session);
        return ResponseEntity.ok(describe(session));
    }

    /** Logout: ends this session and clears the cookie. */
    @DeleteMapping("/api/session")
    public ObjectNode logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        return describe(Session.anonymous());
    }

    static ObjectNode describe(Session session) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("signed_in", session.isSignedIn());
        out.put("actor_label", session.actorLabel());
        out.put("station_id", session.stationId());
        var roles = out.putArray("roles");
        session.roles().stream().map(Role::wire).sorted().forEach(roles::add);
        return out;
    }

    static ResponseEntity<ObjectNode> problem(HttpStatus status, String code, String text) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("reason_code", code);
        body.put("reason_text", text);
        return ResponseEntity.status(status).body(body);
    }
}
