package fuelprint.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.CoreSpringTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RecordStoreTest extends CoreSpringTest {

    @Autowired RecordStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventLog events;

    static String uuid() {
        return UUID.randomUUID().toString();
    }

    static ObjectNode fields(String text) {
        return JsonNodeFactory.instance.objectNode().put("text", text);
    }

    // 9
    @Test
    void savingTheSameRecordTwiceGivesRevisionsOneAndTwoBothReadable() {
        String id = uuid();
        String run = uuid();
        StoredRecord first = store.save(RecordChange.create("sample", id, run, fields("one")), "tester", null);
        StoredRecord second = store.save(RecordChange.next(first, fields("two")), "tester", null);

        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(store.get("sample", id, 1).orElseThrow().body().path("text").asText()).isEqualTo("one");
        assertThat(store.get("sample", id, 2).orElseThrow().body().path("text").asText()).isEqualTo("two");
        assertThat(store.get("sample", id).orElseThrow().revision()).isEqualTo(2);
        assertThat(store.revisions("sample", id)).extracting(StoredRecord::revision).containsExactly(1L, 2L);
        assertThat(store.list("sample", run)).singleElement().satisfies(r -> assertThat(r.revision()).isEqualTo(2));
    }

    // 9
    @Test
    void aRecordWithNullRunIdCanBeSavedAndListed() {
        String id = uuid();
        store.save(RecordChange.create("model_version_test", id, null, fields("not tied to a run")), "tester", null);

        assertThat(store.list("model_version_test", null)).extracting(StoredRecord::id).contains(id);
        assertThat(store.list("model_version_test", uuid())).isEmpty();
        StoredRecord saved = store.get("model_version_test", id).orElseThrow();
        assertThat(saved.runId()).isNull();
        assertThat(saved.body().has("run_id")).isTrue();
        assertThat(saved.body().get("run_id").isNull()).isTrue();
    }

    @Test
    void bodyHoldsTheCommonFieldsThenTheRecordsOwnFields() {
        String id = uuid();
        StoredRecord saved = store.save(RecordChange.create("sample", id, null, fields("x")), "analyst@laptop-1", null);
        ObjectNode body = saved.body();
        assertThat(body.fieldNames()).toIterable()
                .containsExactly("id", "run_id", "revision", "created_utc", "created_by", "text");
        assertThat(body.path("created_by").asText()).isEqualTo("analyst@laptop-1");
        assertThat(body.path("created_utc").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
    }

    @Test
    void listKeepsTheOrderRecordsWereFirstMade() {
        String run = uuid();
        String a = uuid();
        String b = uuid();
        StoredRecord firstA = store.save(RecordChange.create("ordered", a, run, fields("a1")), "t", null);
        store.save(RecordChange.create("ordered", b, run, fields("b1")), "t", null);
        store.save(RecordChange.next(firstA, fields("a2")), "t", null);
        assertThat(store.list("ordered", run)).extracting(StoredRecord::id).containsExactly(a, b);
    }

    @Test
    void aSaveBasedOnAnOldRevisionIsRefusedAndSavesNothing() {
        String id = uuid();
        StoredRecord first = store.save(RecordChange.create("sample", id, null, fields("one")), "t", null);
        store.save(RecordChange.next(first, fields("two")), "t", null);

        assertThatThrownBy(() -> store.save(RecordChange.next(first, fields("late")), "t", null))
                .isInstanceOf(StaleRevisionException.class);
        assertThatThrownBy(() -> store.save(RecordChange.create("sample", id, null, fields("dup")), "t", null))
                .isInstanceOf(StaleRevisionException.class);
        assertThat(store.latestRevision("sample", id)).isEqualTo(2);
    }

    @Test
    void aRecordCannotMoveToAnotherRun() {
        StoredRecord first = store.save(RecordChange.create("sample", uuid(), uuid(), fields("x")), "t", null);
        RecordChange moved = new RecordChange("sample", first.id(), uuid(), 1, fields("y"));
        assertThatThrownBy(() -> store.save(moved, "t", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlersCannotSetTheCommonFields() {
        ObjectNode sneaky = fields("x").put("revision", 99);
        assertThatThrownBy(() -> store.save(RecordChange.create("sample", uuid(), null, sneaky), "t", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDatabaseRefusesToUpdateOrDeleteSavedRows() {
        String id = uuid();
        StoredRecord saved = store.save(RecordChange.create("sample", id, null, fields("x")), "t", null);
        EventRow event = events.append("SAMPLE_SAVED", saved, null, "t", null);
        String commandId = uuid();
        jdbc.update("INSERT INTO commands (command_id, command_type, actor, automatic, payload, request_digest, status, "
                + "receipt_json, received_utc) VALUES (?, 't', 'tester', 0, '{}', 'd', 'REJECTED', '{}', 'now')", commandId);

        assertThatThrownBy(() -> jdbc.update("UPDATE records SET body = '{}' WHERE id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM records WHERE id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE events SET actor = 'x' WHERE cursor = ?", event.cursor()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM events WHERE cursor = ?", event.cursor()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE commands SET status = 'APPLIED' WHERE command_id = ?", commandId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM commands WHERE command_id = ?", commandId))
                .hasMessageContaining("append-only");
    }

    @Test
    void theDatabaseRefusesASkippedRevision() {
        String id = uuid();
        store.save(RecordChange.create("sample", id, null, fields("x")), "t", null);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO records (type, id, revision, run_id, created_utc, created_by, "
                + "body) VALUES ('sample', ?, 3, NULL, 'now', 't', '{}')", id))
                .hasMessageContaining("revision must follow");
    }
}
