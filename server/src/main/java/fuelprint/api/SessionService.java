package fuelprint.api;

import java.util.Optional;
import java.util.Set;

import fuelprint.core.command.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sessions from the {@code fp_session} cookie (CORE-API section 2). Core implements it in P3; field calls
 * {@link #createVisitor} after a pairing code is redeemed. No method can ever return the automation session.
 */
public interface SessionService {

    /** The session of this request from its {@code fp_session} cookie, or empty if it has none or it expired. */
    Optional<Session> resolve(HttpServletRequest request);

    /** A laptop session for {@code stationId} with the given role names; used by {@code POST /api/session/station}. */
    Session createStation(String stationId, Set<String> roles);

    /** A phone session with only the role {@code visitor}; used by field after a pairing code is redeemed. */
    Session createVisitor(String visitorLabel);

    /** A phone session with only the role {@code guest}; used by {@code POST /api/session/guest}. */
    Session createGuest();

    /** Sets the {@code fp_session} cookie (HttpOnly, SameSite=Lax) for {@code session}. */
    void writeCookie(HttpServletResponse response, Session session);

    /** True if the request comes from this server's own origin; checked for sockets and POSTs. */
    boolean originAllowed(HttpServletRequest request);
}
