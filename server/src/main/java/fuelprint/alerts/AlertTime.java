package fuelprint.alerts;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import fuelprint.core.WallTime;

/** Local time of the event: Kundli is on Indian Standard Time. A "day" ends at local midnight. */
final class AlertTime {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private AlertTime() {
    }

    static Instant startOfLocalDay(Instant now) {
        return localDate(now).atStartOfDay(IST).toInstant();
    }

    static Instant endOfLocalDay(Instant now) {
        return localDate(now).plusDays(1).atStartOfDay(IST).toInstant();
    }

    static Duration untilNextMidnight(Instant now) {
        return Duration.between(now, endOfLocalDay(now));
    }

    private static LocalDate localDate(Instant now) {
        return now.atZone(IST).toLocalDate();
    }

    /** An AGREEMENT section 2 wall time string for any instant. */
    static String utc(Instant instant) {
        return WallTime.now(java.time.Clock.fixed(instant, ZoneOffset.UTC));
    }
}
