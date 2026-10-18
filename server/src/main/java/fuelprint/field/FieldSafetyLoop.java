package fuelprint.field;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fuelprint.field.world.FieldWorld;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The wall-time safety loop: every {@value #PERIOD_MS} ms it runs {@link FieldWorld#safetyTick()} (dead man,
 * lease expiry, pairing expiry), also while the run is paused or the world loop is not running.
 * Off with {@code fp.field.safety-loop=false} (tests drive the tick themselves).
 */
@Component
@Profile("!stub")
public class FieldSafetyLoop {

    static final long PERIOD_MS = 50;
    private static final Logger log = LoggerFactory.getLogger(FieldSafetyLoop.class);

    private final FieldWorld world;
    private final boolean enabled;
    private ScheduledExecutorService executor;

    public FieldSafetyLoop(FieldWorld world, @Value("${fp.field.safety-loop:true}") boolean enabled) {
        this.world = world;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fp-field-safety");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                world.safetyTick();
            } catch (RuntimeException e) {
                log.error("Field safety tick failed", e);
            }
        }, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
