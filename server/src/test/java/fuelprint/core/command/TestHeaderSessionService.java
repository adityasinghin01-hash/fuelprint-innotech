package fuelprint.core.command;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import fuelprint.api.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * Test-only {@link SessionService}: reads {@code X-Test-Actor} and {@code X-Test-Roles} (comma separated)
 * headers. It builds sessions with {@link Session#human}, so even it cannot produce the automation actor.
 * P3 provides the real cookie-based service; the create and cookie methods are not needed by P1 tests.
 */
@Component
public class TestHeaderSessionService implements SessionService {

    public static final String ACTOR = "X-Test-Actor";
    public static final String ROLES = "X-Test-Roles";

    @Override
    public Optional<Session> resolve(HttpServletRequest request) {
        String actor = request.getHeader(ACTOR);
        String roles = request.getHeader(ROLES);
        if (actor == null || roles == null) {
            return Optional.empty();
        }
        Set<Role> parsed = EnumSet.noneOf(Role.class);
        Arrays.stream(roles.split(",")).map(String::trim).forEach(wire -> parsed.add(Role.fromWire(wire)
                .orElseThrow(() -> new IllegalArgumentException("Unknown role " + wire))));
        return Optional.of(Session.human("test-" + actor.hashCode(), actor, parsed));
    }

    @Override
    public Session createStation(String stationId, Set<String> roles) {
        throw new UnsupportedOperationException("test service");
    }

    @Override
    public Session createVisitor(String visitorLabel) {
        throw new UnsupportedOperationException("test service");
    }

    @Override
    public Session createGuest() {
        throw new UnsupportedOperationException("test service");
    }

    @Override
    public void writeCookie(HttpServletResponse response, Session session) {
        throw new UnsupportedOperationException("test service");
    }

    @Override
    public boolean originAllowed(HttpServletRequest request) {
        return true;
    }
}
