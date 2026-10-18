package fuelprint.core.run;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.RunEnded;
import fuelprint.api.RunPaused;
import fuelprint.api.RunResumed;
import fuelprint.api.RunStarted;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.CommittedEvent;
import fuelprint.core.command.EventsCommitted;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Moves the world after a {@code run} record change has committed, and publishes the run Spring events of CORE-API
 * section 4. It reacts to the saved run events, whoever saved them (run commands, start-up recovery, fake replay):
 * <ul>
 *   <li>{@code RUN_CREATED}: driver start, then {@link RunStarted}</li>
 *   <li>{@code RUN_PAUSED}: driver pause, then {@link RunPaused}</li>
 *   <li>{@code RUN_RESUMED}: driver resume (or start again after a restart), then {@link RunResumed}</li>
 *   <li>{@code RUN_RESET}, {@code RUN_COMPLETED}, {@code RUN_INTERRUPTED}: driver stop, then {@link RunEnded}</li>
 * </ul>
 * If the world fails to start, the run is saved PAUSED with reason {@code START_FAILED}.
 */
@Component
public class RunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycle.class);

    private final RunDriver driver;
    private final RecordStore store;
    private final ApplicationEventPublisher publisher;
    private final CommandBus bus;

    public RunLifecycle(RunDriver driver, RecordStore store, ApplicationEventPublisher publisher, CommandBus bus) {
        this.driver = driver;
        this.store = store;
        this.publisher = publisher;
        this.bus = bus;
    }

    @EventListener
    public void on(EventsCommitted committed) {
        for (CommittedEvent event : committed.events()) {
            if (!Runs.RUN.equals(event.event().recordType())) {
                continue;
            }
            ObjectNode body = event.record();
            String runId = event.event().recordId();
            switch (event.event().eventType()) {
                case "RUN_CREATED" -> start(runId, body, 0.0, true);
                case "RUN_PAUSED" -> {
                    driver.pause(runId);
                    publisher.publishEvent(new RunPaused(runId));
                }
                case "RUN_RESUMED" -> {
                    if (driver.isLoaded(runId)) {
                        driver.resume(runId);
                        publisher.publishEvent(new RunResumed(runId));
                    } else {
                        start(runId, body, body.path("sim_time_s").asDouble(0), false);
                    }
                }
                case "RUN_RESET" -> end(runId, RunEnded.Reason.RESET);
                case "RUN_COMPLETED" -> end(runId, RunEnded.Reason.COMPLETED);
                case "RUN_ABORTED" -> end(runId, RunEnded.Reason.ABORTED);
                case "RUN_INTERRUPTED" -> end(runId, RunEnded.Reason.INTERRUPTED);
                default -> {
                    // SENSOR_CREATED and others need no world change
                }
            }
        }
    }

    private void start(String runId, ObjectNode body, double simTimeS, boolean fresh) {
        var context = new fuelprint.api.RunContext(runId, body.path("preset_id").asText(), body.path("mode").asText(),
                body.path("seed").asLong());
        try {
            driver.start(context, simTimeS);
        } catch (RuntimeException e) {
            log.error("The world could not start run {}; saving it PAUSED", runId, e);
            StoredRecord run = store.get(Runs.RUN, runId).orElseThrow();
            bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, HandlerResult.builder()
                    .save(Runs.changeState(run, Runs.PAUSED, simTimeS, "START_FAILED"), "RUN_START_FAILED", simTimeS)
                    .build(), false);
            return;
        }
        if (fresh) {
            publisher.publishEvent(new RunStarted(runId, context.presetId(), context.mode()));
        } else {
            publisher.publishEvent(new RunResumed(runId));
        }
    }

    private void end(String runId, RunEnded.Reason reason) {
        try {
            driver.stop(runId);
        } catch (RuntimeException e) {
            log.error("Stopping run {} failed", runId, e);
        }
        publisher.publishEvent(new RunEnded(runId, reason));
    }

    /** All run records, oldest first (for tests and recovery). */
    List<StoredRecord> runs() {
        return store.listAll(Runs.RUN);
    }
}
