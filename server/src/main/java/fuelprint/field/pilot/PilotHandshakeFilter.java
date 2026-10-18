package fuelprint.field.pilot;

import java.io.IOException;
import java.security.Principal;
import java.util.Optional;
import java.util.Set;

import fuelprint.api.SessionService;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Checks the {@code /ws/pilot} handshake before the socket opens (CORE-API section 6): the request must come
 * from this server's own origin ({@link SessionService#originAllowed}) and carry a session
 * ({@link SessionService#resolve}) that may hold a lease (visitor, field_operator or supervisor). The session's
 * actor travels to {@link PilotSocket} as the handshake's user principal. Refused handshakes get 403 (or 503
 * with no session service) and no socket.
 */
public class PilotHandshakeFilter implements Filter {

    static final Set<Role> PILOT_ROLES = Set.of(Role.VISITOR, Role.FIELD_OPERATOR, Role.SUPERVISOR);
    private static final Logger log = LoggerFactory.getLogger(PilotHandshakeFilter.class);

    private final ObjectProvider<SessionService> sessions;

    public PilotHandshakeFilter(ObjectProvider<SessionService> sessions) {
        this.sessions = sessions;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        SessionService service = sessions.getIfAvailable();
        if (service == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "No session service");
            return;
        }
        Optional<Session> session;
        try {
            if (!service.originAllowed(request)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Wrong origin");
                return;
            }
            session = service.resolve(request);
        } catch (RuntimeException e) {
            log.warn("Session check for /ws/pilot failed ({})", e.getClass().getSimpleName());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not signed in");
            return;
        }
        if (session == null || session.isEmpty() || session.get().isAutomation()
                || !session.get().hasAnyRole(PILOT_ROLES)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not signed in as a pilot");
            return;
        }
        Principal principal = new PilotSocket.PilotPrincipal(session.get().actorLabel());
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }
        }, response);
    }
}
