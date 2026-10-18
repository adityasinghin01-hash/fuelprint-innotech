package fuelprint.core.command;

import java.util.List;

/**
 * Core-internal Spring event, published on the command thread after every commit that saved events (commands,
 * the world loop, fake replay), in cursor order. The live socket broadcasts from it.
 */
public record EventsCommitted(List<CommittedEvent> events) {

    public EventsCommitted {
        events = List.copyOf(events);
    }
}
