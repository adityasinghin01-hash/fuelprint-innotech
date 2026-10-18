package fuelprint.core.command;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Finds the session of an HTTP request, from its cookie. P3 adds the station, join and guest resolvers.
 * The command controller asks every resolver bean in order and uses the first answer; with no answer the
 * request is anonymous. A resolver can only return {@link Session#human} or {@link Session#anonymous}
 * sessions, so no request can ever become the automation actor.
 */
public interface SessionResolver {

    /** The session for this request, or empty if this resolver does not recognise it. */
    Optional<Session> resolve(HttpServletRequest request);
}
