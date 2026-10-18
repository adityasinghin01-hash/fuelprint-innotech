package fuelprint.core.command;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who is sending a command: an actor label (like {@code supervisor@laptop-1}) and the roles it holds.
 *
 * <p>A session from a request is made with {@link #human} or {@link #anonymous}; neither can ever be the
 * {@code automation} actor or hold the {@code automation} role. Only {@link AutomationBus} (same package)
 * can make the automation session (AGREEMENT section 3: no cookie, PIN or payload can claim it).
 */
public final class Session {

    public static final String AUTOMATION_ACTOR = "automation";
    public static final String ANONYMOUS_ACTOR = "anonymous";

    private static final Session ANONYMOUS = new Session(ANONYMOUS_ACTOR, Set.of(), false);
    private static final Session AUTOMATION = new Session(AUTOMATION_ACTOR, Set.of(Role.AUTOMATION), true);

    private final String actor;
    private final Set<Role> roles;
    private final boolean automation;

    private Session(String actor, Set<Role> roles, boolean automation) {
        this.actor = actor;
        this.roles = roles;
        this.automation = automation;
    }

    /**
     * A person's session. P3 makes these from station, join and guest cookies.
     *
     * @throws IllegalArgumentException if the actor label is blank or reserved, or the roles include automation
     */
    public static Session human(String actor, Set<Role> roles) {
        if (actor == null || actor.isBlank() || actor.length() > 128) {
            throw new IllegalArgumentException("Actor label must be 1 to 128 characters");
        }
        String normalised = actor.trim().toLowerCase(Locale.ROOT);
        if (normalised.equals(AUTOMATION_ACTOR) || normalised.equals(ANONYMOUS_ACTOR)) {
            throw new IllegalArgumentException("Actor label '" + actor + "' is reserved");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("A signed-in session needs at least one role");
        }
        if (roles.contains(Role.AUTOMATION)) {
            throw new IllegalArgumentException("Only the server itself has the automation role");
        }
        return new Session(actor, Collections.unmodifiableSet(EnumSet.copyOf(roles)), false);
    }

    /** A request with no valid session: no roles, so every command is refused with NOT_ALLOWED. */
    public static Session anonymous() {
        return ANONYMOUS;
    }

    /** The server's own automation actor. Package-private: only {@link AutomationBus} uses it. */
    static Session automation() {
        return AUTOMATION;
    }

    public String actor() {
        return actor;
    }

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

    @Override
    public String toString() {
        return "Session[" + actor + " " + roles + "]";
    }
}
