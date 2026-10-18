package fuelprint.core.command;

import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.EventRow;

/**
 * One committed event with the record revision it announces.
 *
 * @param event     the saved event row
 * @param record    the saved record body (a private copy)
 * @param automatic true when the actor is {@code automation}
 */
public record CommittedEvent(EventRow event, ObjectNode record, boolean automatic) {
}
