package fuelprint.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fuelprint.FuelPrintApplication;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.CommandLog;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.EventLog;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Test 12: everything saved survives closing and reopening the same database file. */
class RestartTest {

    static ConfigurableApplicationContext start(Path dataDir) {
        return new SpringApplicationBuilder(FuelPrintApplication.class)
                .web(WebApplicationType.NONE)
                // Command-line arguments win over application.properties (builder .properties() would not).
                .run("--fp.data-dir=" + dataDir, "--spring.main.banner-mode=off");
    }

    @Test
    void dataSurvivesARestart() {
        Path dataDir = CoreSpringTest.TempFolders.create("fp-restart-");
        Session analyst = Session.human("s-test", "analyst@laptop-1", Set.of(Role.ANALYST));
        String noteId = UUID.randomUUID().toString();
        String looseId = UUID.randomUUID().toString();
        Command command = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "test_auto_note", null,
                0L, JsonNodeFactory.instance.objectNode().put("note_id", noteId).put("text", "before restart"));
        byte[] blob = "sealed bytes".getBytes();

        Receipt firstReceipt;
        long firstCursor;
        String sha;
        try (ConfigurableApplicationContext first = start(dataDir)) {
            assertThat(first.getBean(fuelprint.core.db.DataDirectory.class).root())
                    .isEqualTo(dataDir.toAbsolutePath().normalize());
            firstReceipt = first.getBean(CommandBus.class).send(command, analyst);
            first.getBean(RecordStore.class).save(RecordChange.create("loose", looseId, null,
                    JsonNodeFactory.instance.objectNode().put("k", 1)), "tester", null);
            firstCursor = first.getBean(EventLog.class).lastCursor();
            sha = first.getBean(BlobStore.class).put(blob);
        }
        assertThat(firstReceipt.applied()).isTrue();

        try (ConfigurableApplicationContext second = start(dataDir)) {
            RecordStore store = second.getBean(RecordStore.class);
            StoredRecord note = store.get("test_note", noteId).orElseThrow();
            assertThat(note.revision()).isEqualTo(1);
            assertThat(note.body().path("text").asText()).isEqualTo("before restart");
            assertThat(store.list("loose", null)).extracting(StoredRecord::id).containsExactly(looseId);
            assertThat(second.getBean(CommandLog.class).receipt(command.commandId())).contains(firstReceipt);
            assertThat(second.getBean(BlobStore.class).get(sha)).isEqualTo(blob);

            // Resending after the restart still returns the first receipt and changes nothing.
            CommandBus bus = second.getBean(CommandBus.class);
            assertThat(bus.send(command, analyst)).isEqualTo(firstReceipt);
            assertThat(store.revisions("test_note", noteId)).hasSize(1);

            // Cursors keep going up after the restart.
            String otherId = UUID.randomUUID().toString();
            bus.send(new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "test_auto_note", null, null,
                    JsonNodeFactory.instance.objectNode().put("note_id", otherId).put("text", "after")), analyst);
            assertThat(second.getBean(EventLog.class).forRecord("test_note", otherId).get(0).cursor())
                    .isGreaterThan(firstCursor);
        }
    }
}
