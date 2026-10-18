package fuelprint.core.command;

import java.util.List;

import fuelprint.core.store.RecordRef;

/**
 * The answer to one command (AGREEMENT section 3, version 2).
 *
 * @param receiptId  UUID of this receipt
 * @param commandId  the command it answers
 * @param status     APPLIED, REJECTED, QUEUED or INTERRUPTED
 * @param reasonCode why it was refused (see {@link ReasonCode}), null when applied
 * @param reasonText plain words for the screen, null when applied
 * @param recordRefs the record revisions the command saved
 * @param actor      who sent it, from the session; {@code automation} for automatic steps
 * @param automatic  true only when the actor is {@code automation}
 */
public record Receipt(String receiptId, String commandId, Status status, String reasonCode, String reasonText,
                      List<RecordRef> recordRefs, String actor, boolean automatic) {

    /** Receipt status values. */
    public enum Status { APPLIED, REJECTED, QUEUED, INTERRUPTED }

    public Receipt {
        recordRefs = recordRefs == null ? List.of() : List.copyOf(recordRefs);
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }
}
