package fuelprint.core.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventLogTest extends CoreSpringTest {

    @Autowired CommandBus bus;
    @Autowired EventLog events;

    // 10
    @Test
    void eventCursorsStrictlyIncrease() {
        Session analyst = Session.human("analyst@laptop-1", Set.of(Role.ANALYST));
        long before = events.lastCursor();
        List<Long> cursors = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String noteId = UUID.randomUUID().toString();
            Command command = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "test_auto_note", null,
                    null, JsonNodeFactory.instance.objectNode().put("note_id", noteId).put("text", "n" + i));
            Receipt receipt = bus.send(command, analyst);
            assertThat(receipt.applied()).isTrue();
            cursors.add(events.forRecord("test_note", noteId).get(0).cursor());
        }
        for (int i = 1; i < cursors.size(); i++) {
            assertThat(cursors.get(i)).isGreaterThan(cursors.get(i - 1));
        }
        assertThat(cursors.get(0)).isGreaterThan(before);

        List<EventRow> after = events.after(before, 1000);
        for (int i = 1; i < after.size(); i++) {
            assertThat(after.get(i).cursor()).isGreaterThan(after.get(i - 1).cursor());
        }
        assertThat(after).extracting(EventRow::cursor).containsAll(cursors);
        assertThat(events.lastCursor()).isGreaterThanOrEqualTo(cursors.get(cursors.size() - 1));
    }

    @Test
    void anEventCarriesTheContractFields() {
        Session analyst = Session.human("analyst@laptop-1", Set.of(Role.ANALYST));
        String noteId = UUID.randomUUID().toString();
        Command command = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "test_auto_note", null,
                null, JsonNodeFactory.instance.objectNode().put("note_id", noteId).put("text", "x"));
        bus.send(command, analyst);

        EventRow event = events.forRecord("test_note", noteId).get(0);
        assertThat(event.eventType()).isEqualTo("TEST_NOTE_SAVED");
        assertThat(event.recordType()).isEqualTo("test_note");
        assertThat(event.recordId()).isEqualTo(noteId);
        assertThat(event.revision()).isEqualTo(1);
        assertThat(event.simTimeS()).isNull();
        assertThat(event.wallUtc()).endsWith("Z");
        assertThat(event.actor()).isEqualTo("analyst@laptop-1");
        assertThat(event.commandId()).isEqualTo(command.commandId());
    }
}
