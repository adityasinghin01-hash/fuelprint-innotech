package fuelprint.core;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Wall time strings for {@code *_utc} fields: ISO 8601 with milliseconds, like {@code 2026-10-22T10:15:04.120Z}. */
public final class WallTime {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private WallTime() {
    }

    /** The current wall time of {@code clock} as an AGREEMENT section 2 string. */
    public static String now(Clock clock) {
        return FORMAT.format(clock.instant());
    }
}
