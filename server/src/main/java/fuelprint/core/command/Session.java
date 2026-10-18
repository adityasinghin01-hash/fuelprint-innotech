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

    private static final Session ANONYMOUS = new Session(null, ANONYMOUS_ACTOR, Set.of(), false, null);
    private static final Session AUTOMATION = new Session(null, AUTOMATION_ACTOR, Set.of(Role.AUTOMATION), true, null);

    private final String sessionId;
    private final String actorLabel;
    private final Set<Role> roles;
    private final boolean automation;
    private final String stationId;

    private Session(String sessionId, String actorLabel, Set<Role> roles, boolean automation, String stationId) {
        this.sessionId = sessionId;
        this.actorLabel = actorLabel;
        this.roles = roles;
        this.automation = automation;
        this.stationId = stationId;
    }

    /**
     * A person's session. P3's {@code SessionService} makes these for stations, visitors and guests.
     *
     * @param sessionId  server-made secret id of the session (it is also the cookie value); 1 to 128 characters
     * @param actorLabel shown in receipts and records as the actor; 1 to 128 characters, not reserved
     * @param roles      at least one role, never {@link Role#AUTOMATION}
     * @throws IllegalArgumentException if a value is blank or reserved, or the roles include automation
     */
    public static Session human(String sessionId, String actorLabel, Set<Role> roles) {
        checkId(sessionId);
        checkLabel(actorLabel);
        checkRoles(roles);
        return new Session(sessionId, actorLabel, Collections.unmodifiableSet(EnumSet.copyOf(roles)), false, null);
    }

    /**
     * A laptop station's session. Its actor label is the station id (like {@code laptop-1}); for each command the
     * actor recorded is {@code <role>@<station>} (like {@code supervisor@laptop-1}), see {@link #actorFor}.
     */
    public static Session station(String sessionId, String stationId, Set<Role> roles) {
        checkId(sessionId);
        checkLabel(stationId);
        checkRoles(roles);
        return new Session(sessionId, stationId, Collections.unmodifiableSet(EnumSet.copyOf(roles)), false, stationId);
    }

    private static void checkId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new IllegalArgumentException("Session id must be 1 to 128 characters");
        }
    }

    private static void checkLabel(String actorLabel) {
        if (actorLabel == null || actorLabel.isBlank() || actorLabel.length() > 128) {
            throw new IllegalArgumentException("Actor label must be 1 to 128 characters");
        }
        String normalised = actorLabel.trim().toLowerCase(Locale.ROOT);
        if (normalised.equals(AUTOMATION_ACTOR) || normalised.equals(ANONYMOUS_ACTOR)) {
            throw new IllegalArgumentException("Actor label '" + actorLabel + "' is reserved");
        }
    }

    private static void checkRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("A signed-in session needs at least one role");
        }
        if (roles.contains(Role.AUTOMATION)) {
            throw new IllegalArgumentException("Only the server itself has the automation role");
        }
    }

    /** A request with no valid session: no session id, no roles, so every command is refused with NOT_ALLOWED. */
    public static Session anonymous() {
        return ANONYMOUS;
    }

    /** The server's own automation actor (no session id). Package-private: only {@link AutomationBus} uses it. */
    static Session automation() {
        return AUTOMATION;
    }

    /** Server-made secret id of this session (the cookie value; never log it); null for anonymous and automation. */
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

    /** The station id for a laptop session, or null. */
    public String stationId() {
        return stationId;
    }

    /**
     * The actor to record for a command that allows {@code allowed}: {@code <role>@<station>} for a station session
     * (the first held role that the command allows, in {@link Role} order), otherwise {@link #actorLabel()}.
     */
    public String actorFor(Set<Role> allowed) {
        if (stationId == null || allowed == null) {
            return actorLabel;
        }
        for (Role role : Role.values()) {
            if (roles.contains(role) && allowed.contains(role)) {
                return role.wire() + "@" + stationId;
            }
        }
        return actorLabel;
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
