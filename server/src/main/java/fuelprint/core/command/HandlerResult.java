package fuelprint.core.command;

import java.util.ArrayList;
import java.util.List;

import fuelprint.core.store.RecordChange;

/**
 * What an applied command saves: new record revisions and one event for each of them
 * (AGREEMENT section 3: an event every time a record is saved). The bus saves all of it, plus the
 * receipt, in one transaction, or nothing.
 *
 * @param records the new revisions, at most one per record
 * @param events  exactly one event per record in {@code records}
 */
public record HandlerResult(List<RecordChange> records, List<EventDraft> events) implements HandlerOutcome {

    public HandlerResult {
        records = List.copyOf(records);
        events = List.copyOf(events);
    }

    /** An applied command that saves nothing. */
    public static HandlerResult nothing() {
        return new HandlerResult(List.of(), List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects records with their events. */
    public static final class Builder {
        private final List<RecordChange> records = new ArrayList<>();
        private final List<EventDraft> events = new ArrayList<>();

        /** Saves {@code change} with an event of {@code eventType} at {@code simTimeS} (may be null). */
        public Builder save(RecordChange change, String eventType, Double simTimeS) {
            records.add(change);
            events.add(new EventDraft(eventType, change.type(), change.id(), simTimeS));
            return this;
        }

        public HandlerResult build() {
            return new HandlerResult(records, events);
        }
    }
}
