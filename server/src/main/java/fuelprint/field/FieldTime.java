package fuelprint.field;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Wall time for the field package. Leases, the dead man and pairing codes run on wall time
 * (R3 doc 03: "All actuator/physical time is wall time"; a paused run does not pause lease expiry).
 * Tests replace the bean with a manual clock.
 */
public interface FieldTime {

    DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** A monotonic millisecond counter (never goes back). */
    long monoMillis();

    /** The wall time now. */
    Instant now();

    /** {@code instant} as an AGREEMENT section 2 string, like {@code 2026-10-22T10:15:04.120Z}. */
    static String format(Instant instant) {
        return UTC.format(instant);
    }
}
