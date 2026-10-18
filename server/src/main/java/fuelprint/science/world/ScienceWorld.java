package fuelprint.science.world;

import java.util.Optional;

import fuelprint.api.WindNow;
import fuelprint.api.WorldClock;
import fuelprint.api.WorldCapacityReached;
import fuelprint.science.truth.EmissionPlan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The real {@link WorldClock}: owns the puff world of the current run. The scenario setup swaps the world in
 * when a run starts. Before a run is loaded, time does not move.
 */
@Component
@Profile("!stub")
public class ScienceWorld implements WorldClock {

    private final ApplicationEventPublisher events;
    private PuffWorld world;
    private EmissionPlan plan;
    private String runId;
    private boolean capacityPublished;

    public ScienceWorld(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** Loads a fresh world for a run. */
    public synchronized void load(String runId, PuffWorld world, EmissionPlan plan) {
        this.runId = runId;
        this.world = world;
        this.plan = plan;
        this.capacityPublished = false;
        applyDue();
    }

    public synchronized void unload(String runId) {
        if (runId != null && runId.equals(this.runId)) {
            this.world = null;
            this.plan = null;
            this.runId = null;
        }
    }

    private void applyDue() {
        if (plan == null) {
            return;
        }
        for (EmissionPlan.Change c : plan.due(world.timeS())) {
            world.setEmission(c.sourceId(), c.so2GramsPerSecond(), c.co2GramsPerSecond());
        }
    }

    @Override
    public synchronized void step(double dtS) {
        if (world == null) {
            return;
        }
        double remaining = dtS;
        // Walk tick by tick so a scheduled emission change lands on its own tick.
        while (remaining >= PuffWorld.DT - 1e-9 && world.capacityReached().isEmpty()) {
            applyDue();
            double advanced = world.step(PuffWorld.DT);
            if (advanced <= 0) {
                break;
            }
            remaining -= PuffWorld.DT;
        }
        Optional<PuffWorld.Capacity> cap = world.capacityReached();
        if (cap.isPresent() && !capacityPublished) {
            capacityPublished = true;
            events.publishEvent(new WorldCapacityReached(runId, cap.get().name(), world.timeS(), world.puffCount()));
        }
    }

    @Override
    public synchronized WindNow wind() {
        return world == null ? new WindNow(270, 3.0) : world.wind();
    }

    /** The loaded world, if a run is loaded. Science-internal. */
    public synchronized Optional<PuffWorld> current() {
        return Optional.ofNullable(world);
    }

    public synchronized Optional<String> runId() {
        return Optional.ofNullable(runId);
    }

    /** True when the live envelope is used up and the world has stopped. */
    public synchronized boolean capacityReached() {
        return world != null && world.capacityReached().isPresent();
    }
}
