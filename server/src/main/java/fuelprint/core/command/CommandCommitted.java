package fuelprint.core.command;

import java.util.List;

import fuelprint.core.store.EventRow;

/**
 * Spring application event published by the {@link CommandBus} after a command's transaction has
 * committed (never before). Published for every stored receipt: APPLIED ones carry their saved events,
 * REJECTED ones carry none. P3 broadcasts the events on the live socket. Other packages normally listen
 * for {@link fuelprint.api.RecordSaved} instead (CORE-API section 1); this one is for the core.
 *
 * <p>It deliberately has no payload, so private values can never be broadcast from here. Listeners that
 * need data read the saved records through {@code receipt.recordRefs()}. A listener that wants to change
 * something calls {@link AutomationBus#submit}; it never saves records itself.
 *
 * @param commandId   the command
 * @param commandType its type
 * @param runId       its run, or null
 * @param receipt     the stored receipt
 * @param events      the saved events in cursor order (empty when rejected)
 */
public record CommandCommitted(String commandId, String commandType, String runId, Receipt receipt,
                               List<EventRow> events) {

    public CommandCommitted {
        events = List.copyOf(events);
    }
}
