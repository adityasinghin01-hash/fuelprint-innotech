package fuelprint.api;

/** Who is flying a vehicle (AGREEMENT section 3 tick and section 6 {@code mission.pilot}). */
public enum Pilot {
    /** Nobody; the vehicle holds still. */
    NONE,
    /** A paired visitor phone. */
    VISITOR,
    /** The field operator on the keyboard. */
    OPERATOR,
    /** The server's autopilot along the approved route. */
    AUTOPILOT
}
