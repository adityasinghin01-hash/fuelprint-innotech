package fuelprint.core.run;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.api.WorldCapacityReached;
import fuelprint.core.command.AutomationBus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Turns science's public capacity event into a normal command; RunLifecycle stops the world after commit. */
@Component
public class WorldCapacityListener {

    private final AutomationBus automation;

    public WorldCapacityListener(AutomationBus automation) {
        this.automation = automation;
    }

    @EventListener
    public void on(WorldCapacityReached event) {
        automation.submit(RunCommands.COMPLETE, event.runId(), JsonNodeFactory.instance.objectNode()
                .put("run_id", event.runId()).put("reason", RunCommands.WORLD_CAPACITY));
    }
}
