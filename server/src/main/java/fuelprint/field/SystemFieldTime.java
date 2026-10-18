package fuelprint.field;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The real wall time: {@link System#nanoTime()} for durations and the core's wall {@link Clock} for UTC strings. */
@Component
@Profile("!stub")
public class SystemFieldTime implements FieldTime {

    private final Clock wallClock;

    public SystemFieldTime(Clock wallClock) {
        this.wallClock = wallClock;
    }

    @Override
    public long monoMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    @Override
    public Instant now() {
        return wallClock.instant();
    }
}
