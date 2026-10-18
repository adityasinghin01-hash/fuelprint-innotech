package fuelprint.core.command;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Test-only session resolver: {@code X-Test-Actor} and {@code X-Test-Roles} (comma separated) headers.
 * It builds sessions with {@link Session#human}, so even it cannot produce the automation actor.
 * P3 replaces this with real station, join and guest cookies.
 */
@Component
@Order(0)
public class TestHeaderSessionResolver implements SessionResolver {

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
        return Optional.of(Session.human(actor, parsed));
    }
}
