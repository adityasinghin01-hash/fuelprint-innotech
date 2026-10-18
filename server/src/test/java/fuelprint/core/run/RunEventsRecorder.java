package fuelprint.core.run;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import fuelprint.api.RunEnded;
import fuelprint.api.RunPaused;
import fuelprint.api.RunResumed;
import fuelprint.api.RunStarted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Test listener: every run Spring event, in order. */
@Component
public class RunEventsRecorder {

    public static final List<Object> EVENTS = new CopyOnWriteArrayList<>();

    @EventListener
    public void started(RunStarted event) {
        EVENTS.add(event);
    }

    @EventListener
    public void paused(RunPaused event) {
        EVENTS.add(event);
    }

    @EventListener
    public void resumed(RunResumed event) {
        EVENTS.add(event);
    }

    @EventListener
    public void ended(RunEnded event) {
        EVENTS.add(event);
    }
}
