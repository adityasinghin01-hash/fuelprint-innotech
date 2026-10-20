package fuelprint.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;

/**
 * Lane B handoff (B4, for Lane A placement in server/src/test/java/fuelprint/ai/): aerosol media lab results can never
 * become a fuel-training lesson. Uses only the public LearningRules eligibility over plain saved records; no model is
 * trained, loaded or fabricated. Intended command: ./mvnw -q -Dtest=AerosolLessonIsolationTest test
 */
class AerosolLessonIsolationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String RUN = "11111111-1111-4111-8111-111111111111", CASE = "22222222-2222-4222-8222-222222222222",
            FINDING = "33333333-3333-4333-8333-333333333333", MEDIA = "44444444-4444-4444-8444-444444444444",
            MLAB = "55555555-5555-4555-8555-555555555555", SPEC = "66666666-6666-4666-8666-666666666666",
            LAB = "77777777-7777-4777-8777-777777777777", MEV = "88888888-8888-4888-8888-888888888888",
            EV = "99999999-9999-4999-8999-999999999999";

    static final class Mem implements StoreView {
        final Map<String, TreeMap<Long, StoredRecord>> rows = new HashMap<>();
        void put(String type, String id, long rev, ObjectNode body) {
            body.put("id", id).put("revision", rev).put("run_id", RUN).put("created_utc", "2026-10-20T00:00:00.000Z").put("created_by", "x@y");
            rows.computeIfAbsent(type + "/" + id, k -> new TreeMap<>()).put(rev, new StoredRecord(type, id, rev, RUN, "2026-10-20T00:00:00.000Z", "x@y", body));
        }
        public Optional<StoredRecord> get(String type, String id) {
            var m = rows.get(type + "/" + id); return m == null || m.isEmpty() ? Optional.empty() : Optional.of(m.lastEntry().getValue());
        }
        public Optional<StoredRecord> get(String type, String id, long revision) {
            var m = rows.get(type + "/" + id); return m == null ? Optional.empty() : Optional.ofNullable(m.get(revision));
        }
        public List<StoredRecord> list(String type, String runId) { return listAll(type).stream().filter(r -> Objects.equals(r.runId(), runId)).collect(Collectors.toList()); }
        public List<StoredRecord> listAll(String type) {
            return rows.entrySet().stream().filter(e -> e.getKey().startsWith(type + "/")).map(e -> e.getValue().lastEntry().getValue())
                    .sorted(Comparator.comparing(StoredRecord::id)).collect(Collectors.toList());
        }
        public long latestRevision(String type, String id) { var m = rows.get(type + "/" + id); return m == null || m.isEmpty() ? 0 : m.lastKey(); }
    }

    static ObjectNode ref(String type, String id, long rev) { return JSON.createObjectNode().put("type", type).put("id", id).put("revision", rev); }

    /** A sealed SUPPORTED_WITHIN_SCOPE case citing a passing, USABLE aerosol media lab result. */
    static Mem aerosolOnly() {
        Mem m = new Mem();
        m.put("media", MEDIA, 1, JSON.createObjectNode().put("case_id", CASE).put("material", "SYNTHETIC_UNCLASSIFIED_AEROSOL")
                .put("method_id", "TEACH-AEROSOL-S-1").put("location_label", "Teaching fence line"));
        ObjectNode lab = JSON.createObjectNode().put("media_id", MEDIA).put("method_id", "TEACH-AEROSOL-S-1").put("qc_status", "PASS").put("result_use", "USABLE");
        lab.set("media_ref", ref("media", MEDIA, 1)); lab.set("case_ref", ref("case", CASE, 1)); lab.set("custody_ref", ref("media_custody_event", MEV, 1));
        m.put("media_lab_result", MLAB, 1, lab);
        ObjectNode f = JSON.createObjectNode().put("case_id", CASE).put("outcome", "SUPPORTED_WITHIN_SCOPE");
        f.putArray("cited_refs").add(ref("media_lab_result", MLAB, 1));
        m.put("finding", FINDING, 1, f);
        ObjectNode c = JSON.createObjectNode().put("state", "SEALED");
        c.set("finding_ref", ref("finding", FINDING, 1));
        c.putArray("media_ids").add(MEDIA); c.putArray("media_lab_result_ids").add(MLAB);
        m.put("case", CASE, 1, c);
        return m;
    }

    static JsonNode row(ArrayNode lessons) {
        assertThat(lessons).hasSize(1);
        return lessons.get(0);
    }

    @Test void aerosolOnlySupportedCaseIsSkippedNotALesson() {
        JsonNode r = row(LearningRules.lessons(aerosolOnly()));
        assertThat(r.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(r.path("site_id").isNull()).isTrue();
        assertThat(r.path("reason").asText()).isEqualTo("No cited PASS lab with exact same-case specimen lineage; not a lesson.");
    }

    @Test void mixedFuelAndAerosolCaseTakesItsLabelOnlyFromTheFuelSpecimen() {
        Mem m = aerosolOnly();
        m.put("specimen", SPEC, 1, JSON.createObjectNode().put("case_id", CASE).put("site_id", "S02"));
        ObjectNode lab = JSON.createObjectNode().put("specimen_id", SPEC).put("qc_status", "PASS");
        lab.set("specimen_ref", ref("specimen", SPEC, 1)); lab.set("case_ref", ref("case", CASE, 1)); lab.set("custody_ref", ref("custody_event", EV, 1));
        m.put("lab_result", LAB, 1, lab);
        ObjectNode f = (ObjectNode) m.get("finding", FINDING, 1).orElseThrow().body();
        ((ArrayNode) f.path("cited_refs")).add(ref("lab_result", LAB, 1));
        m.put("finding", FINDING, 1, f);
        JsonNode r = row(LearningRules.lessons(m));
        assertThat(r.path("status").asText()).isEqualTo("ELIGIBLE");
        assertThat(r.path("site_id").asText()).isEqualTo("S02");
        assertThat(r.path("lab_ref").path("type").asText()).isEqualTo("lab_result");
        assertThat(r.path("lab_ref").path("id").asText()).isEqualTo(LAB);
    }

    @Test void aMediaResultMisfiledAsALabResultCitationStillYieldsNoLabel() {
        // Even if a finding cited the aerosol result's id under the fuel type, no fuel record exists with that id.
        Mem m = aerosolOnly();
        ObjectNode f = (ObjectNode) m.get("finding", FINDING, 1).orElseThrow().body();
        ((ArrayNode) f.path("cited_refs")).add(ref("lab_result", MLAB, 1));
        m.put("finding", FINDING, 1, f);
        JsonNode r = row(LearningRules.lessons(m));
        assertThat(r.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(r.path("site_id").isNull()).isTrue();
    }
}
