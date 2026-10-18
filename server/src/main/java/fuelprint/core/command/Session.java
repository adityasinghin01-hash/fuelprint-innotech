package fuelprint.core.command;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who is sending a command (CORE-API section 2): a session id, an actor label (like
 * {@code supervisor@laptop-1}, {@code visitor-7f3a}, {@code guest-21c9}) and the roles it holds.
 *
 * <p>A session from a request is made with {@link #human} or {@link #anonymous}; neither can ever be the
 * {@code automation} actor or hold the {@code automation} role. Only {@link AutomationBus} (same package)
 * can make the automation session (AGREEMENT section 3: no cookie, PIN or payload can claim it).
 */
public final class Session {

    public static final String AUTOMATION_ACTOR = "automation";
    public static final String ANONYMOUS_ACTOR = "anonymous";

    private static final Session ANONYMOUS = new Session(null, ANONYMOUS_ACTOR, Set.of(), false);
    private static final Session AUTOMATION = new Session(null, AUTOMATION_ACTOR, Set.of(Role.AUTOMATION), true);

    private final String sessionId;
    private final String actorLabel;
    private final Set<Role> roles;
    private final boolean automation;

    private Session(String sessionId, String actorLabel, Set<Role> roles, boolean automation) {
        this.sessionId = sessionId;
        this.actorLabel = actorLabel;
        this.roles = roles;
        this.automation = automation;
    }

    /**
     * A person's session. P3's {@code SessionService} makes these for stations, visitors and guests.
     *
     * @param sessionId  server-made id of the session (not the cookie secret); 1 to 128 characters
     * @param actorLabel shown in receipts and records as the actor; 1 to 128 characters, not reserved
     * @param roles      at least one role, never {@link Role#AUTOMATION}
     * @throws IllegalArgumentException if a value is blank or reserved, or the roles include automation
     */
    public static Session human(String sessionId, String actorLabel, Set<Role> roles) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new IllegalArgumentException("Session id must be 1 to 128 characters");
        }
        if (actorLabel == null || actorLabel.isBlank() || actorLabel.length() > 128) {
            throw new IllegalArgumentException("Actor label must be 1 to 128 characters");
        }
        String normalised = actorLabel.trim().toLowerCase(Locale.ROOT);
        if (normalised.equals(AUTOMATION_ACTOR) || normalised.equals(ANONYMOUS_ACTOR)) {
            throw new IllegalArgumentException("Actor label '" + actorLabel + "' is reserved");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("A signed-in session needs at least one role");
        }
        if (roles.contains(Role.AUTOMATION)) {
            throw new IllegalArgumentException("Only the server itself has the automation role");
        }
        return new Session(sessionId, actorLabel, Collections.unmodifiableSet(EnumSet.copyOf(roles)), false);
    }

    /** A request with no valid session: no session id, no roles, so every command is refused with NOT_ALLOWED. */
    public static Session anonymous() {
        return ANONYMOUS;
    }

    /** The server's own automation actor (no session id). Package-private: only {@link AutomationBus} uses it. */
    static Session automation() {
        return AUTOMATION;
    }

    /** Server-made id of this session; null for anonymous requests and for automation. */
    public String sessionId() {
        return sessionId;
    }

    /** The actor label that receipts, events and {@code created_by} show. */
    public String actorLabel() {
        return actorLabel;
    }

    /** The roles this session holds (empty when anonymous). */
    public Set<Role> roles() {
        return roles;
    }

    /** True only for the server's own automation actor. */
    public boolean isAutomation() {
        return automation;
    }

    /** True if the session holds at least one role. */
    public boolean isSignedIn() {
        return !roles.isEmpty();
    }

    /** True if the session holds at least one of {@code allowed}. */
    public boolean hasAnyRole(Set<Role> allowed) {
        for (Role role : roles) {
            if (allowed.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /** Actor and roles only; the session id is left out so it never reaches a log line. */
    @Override
    public String toString() {
        return "Session[" + actorLabel + " " + roles + "]";
    }
}
