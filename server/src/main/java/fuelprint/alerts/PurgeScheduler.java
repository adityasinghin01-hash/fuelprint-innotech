package fuelprint.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.command.AutomationBus;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes phone numbers on time (AGREEMENT ground rule 10 and section 13): submits
 * {@code purge_subscriptions} as automation at local midnight (IST), and once at start-up for sign-ups whose
 * day has already ended. Set {@code fp.alerts.scheduler-enabled=false} to stop the timer (tests do).
 */
@Component
@Profile("!stub")
class PurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PurgeScheduler.class);

    private final AutomationBus automation;
    private final StoreView store;
    private final Clock clock;
    private final boolean enabled;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fp-alerts-midnight");
        thread.setDaemon(true);
        return thread;
    });

    PurgeScheduler(AutomationBus automation, StoreView store, Clock clock,
                   @Value("${fp.alerts.scheduler-enabled:true}") boolean enabled) {
        this.automation = automation;
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled) {
            return;
        }
        purgeExpiredAtStartup();
        scheduleNextMidnight();
    }

    /** Submits the start-up purge if any sign-up is ACTIVE but past its end of day. Returns true if it did. */
    boolean purgeExpiredAtStartup() {
        Instant now = clock.instant();
        boolean anyExpired = store.list(AlertRecords.SUBSCRIPTION, null).stream()
                .anyMatch((StoredRecord subscription) -> AlertRecords.isExpired(subscription, now));
        if (!anyExpired) {
            return false;
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("expired_only", true);
        automation.submit(PurgeSubscriptionsHandler.TYPE, payload);
        log.info("Start-up: purging phone numbers of expired sign-ups");
        return true;
    }

    /** The midnight purge: everything goes. */
    void purgeAtMidnight() {
        automation.submit(PurgeSubscriptionsHandler.TYPE, JsonNodeFactory.instance.objectNode());
        log.info("Local midnight: purging all phone numbers");
    }

    private void scheduleNextMidnight() {
        // A second past midnight, so the clock is surely in the new day.
        Duration wait = AlertTime.untilNextMidnight(clock.instant()).plusSeconds(1);
        if (timer.isShutdown()) {
            return;
        }
        timer.schedule(() -> {
            try {
                purgeAtMidnight();
            } catch (RuntimeException e) {
                log.error("The midnight purge could not be submitted ({})", e.getClass().getSimpleName());
            } finally {
                scheduleNextMidnight();
            }
        }, wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
