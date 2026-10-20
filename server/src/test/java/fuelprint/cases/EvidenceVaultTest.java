package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Task 6 exact-byte oracles. Every expected byte string, length and hash below is written out literally or
 * computed with the JDK's own MessageDigest; nothing is derived with CanonicalJson / EvidenceVault.
 */
class EvidenceVaultTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BS = "\\";
    static final String T = "2026-10-19T10:00:00.000Z";
    static final String SEALED_AT = "2026-10-20T00:00:00.000Z";
    static final String RUN = "11111111-1111-4111-8111-111111111111", CASE = "22222222-2222-4222-8222-222222222222",
            FINDING = "33333333-3333-4333-8333-333333333333", SCREEN = "44444444-4444-4444-8444-444444444444",
            MISSION = "55555555-5555-4555-8555-555555555555", ATTEMPT = "66666666-6666-4666-8666-666666666666",
            SURVEY = "77777777-7777-4777-8777-777777777777", INV = "88888888-8888-4888-8888-888888888888",
            SPEC = "99999999-9999-4999-8999-999999999999", EV1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            EV2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", LAB = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            OTHER = "dddddddd-dddd-4ddd-8ddd-dddddddddddd", EXPORT = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    static final String L1 = "Unsigned integrity record only: matching hashes show these stored bytes are unchanged since sealing, not who made them, when, or that any measurement is true.";
    static final String L2 = "Synthetic teaching evidence only; no physical measurement, authenticity or legal suitability is assessed.";

    @TempDir Path dir;
    BlobStore blobs;

    @BeforeEach void blobStore() { blobs = new BlobStore(dir.resolve("blobs")); }

    static String q(String s) { return s.replace('\'', '"'); }
    static String sha(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String path(String type, String id, long rev) { return "payload/records/" + type + "/" + id + "-r" + rev + ".json"; }
    static JsonNode parse(String s) { try { return JSON.readTree(s); } catch (IOException e) { throw new IllegalStateException(e); } }
    static JsonNode parse(byte[] b) { try { return JSON.readTree(b); } catch (IOException e) { throw new IllegalStateException(e); } }
    long blobFiles() throws IOException {
        try (Stream<Path> s = Files.list(dir.resolve("blobs"))) { return s.filter(p -> !p.getFileName().toString().startsWith(".")).count(); }
    }

    // =====================================================================================================
    // 1. Canonical serializer: literal UTF-8 byte oracles
    // =====================================================================================================

    @Test void codexLiteralVectorBytesLengthHexAndHash() {
        // Independent vector prepared by Codex (task-6-codex-literal-byte-oracles.json).
        JsonNode input = parse(q("{'z':1,'a':{'y':2,'x':'\u00b5g'},'arr':[{'b':false,'a':null},3]}"));
        byte[] actual = CanonicalJson.bytes(input);
        assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo(q("{'a':{'x':'\u00b5g','y':2},'arr':[{'a':null,'b':false},3],'z':1}"));
        assertThat(HexFormat.of().formatHex(actual)).isEqualTo("7b2261223a7b2278223a22c2b567222c2279223a327d2c22617272223a5b7b2261223a6e756c6c2c2262223a66616c73657d2c335d2c227a223a317d");
        assertThat(actual).hasSize(60);
        assertThat(sha(actual)).isEqualTo("943648d4050bd8bd0fb9b09883b799d29b121142519f0668bbf78689c4020ec7");
    }

    @Test void keysSortByUtf16CodeUnitNotByUtf8BytesAndSortRecursivelyInsideArrays() {
        ObjectNode o = JSON.createObjectNode();
        // Insertion order is deliberately scrambled. UTF-16 order: B < a < b < list < e-acute < U+D83D(emoji lead) < U+FF5E.
        o.put("b", 1).put("\uff5e", 6).put("a", 2).put("\ud83d\ude00", 5).put("\u00e9", 4).put("B", 3);
        ArrayNode arr = JSON.createArrayNode();
        arr.addObject().put("y", 1).put("x", 2);
        o.set("list", arr);
        assertThat(CanonicalJson.text(o)).isEqualTo(q("{'B':3,'a':2,'b':1,'list':[{'x':2,'y':1}],'\u00e9':4,'\ud83d\ude00':5,'\uff5e':6}"));
    }

    @Test void arrayOrderIsKeptAndContainersAndScalarsAreCompact() {
        assertThat(CanonicalJson.text(parse("[3, 1,\n 2, [ ], { }, null, true, false]"))).isEqualTo("[3,1,2,[],{},null,true,false]");
        assertThat(CanonicalJson.text(parse("{ \"k\" : [ { \"b\" : 1 , \"a\" : [ ] } ] }"))).isEqualTo("{\"k\":[{\"a\":[],\"b\":1}]}");
        assertThat(CanonicalJson.text(parse("\"\""))).isEqualTo("\"\"");
        assertThat(CanonicalJson.text(parse("{}"))).isEqualTo("{}");
    }

    @Test void stringEscapingIsMinimalAndEverythingElseIsRawUtf8() {
        String value = "a\"b" + BS + "c/\b\f\n\r\t\u0001\u001f\u007f\u2028\u00e9\ud83d\ude00";
        TextNode node = TextNode.valueOf(value);
        String expected = "\"a" + BS + "\"b" + BS + BS + "c/" + BS + "b" + BS + "f" + BS + "n" + BS + "r" + BS + "t"
                + BS + "u0001" + BS + "u001f" + "\u007f\u2028\u00e9\ud83d\ude00\"";
        assertThat(CanonicalJson.bytes(node)).isEqualTo(utf8(expected));
        // Raw UTF-8: e-acute is 2 bytes (c3 a9), the emoji 4 bytes (f0 9f 98 80), U+2028 3 bytes (e2 80 a8).
        assertThat(HexFormat.of().formatHex(CanonicalJson.bytes(TextNode.valueOf("\u00e9\ud83d\ude00\u2028"))))
                .isEqualTo("22" + "c3a9" + "f09f9880" + "e280a8" + "22");
    }

    @Test void objectKeysUseTheSameEscaping() {
        ObjectNode o = JSON.createObjectNode();
        o.put("k\"\n", 1);
        assertThat(CanonicalJson.text(o)).isEqualTo("{\"k" + BS + "\"" + BS + "n\":1}");
    }

    @Test void numbersWriteTheirExactDecimalValueWithoutExponentOrTrailingZeros() throws Exception {
        assertThat(CanonicalJson.text(parse("[0,-0.0,0.0,1.5,50.0,1e2,12345678901234567890,-7,0.1,1E-7,2.50,100]")))
                .isEqualTo("[0,0,0,1.5,50,100,12345678901234567890,-7,0.1,0.0000001,2.5,100]");
        ObjectMapper exact = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        assertThat(CanonicalJson.text(exact.readTree("[20.0000000000000001,1e-30,12.3400]")))
                .isEqualTo("[20.0000000000000001,0.000000000000000000000000000001,12.34]");
        assertThat(CanonicalJson.text(DoubleNode.valueOf(42.5))).isEqualTo("42.5");
        assertThat(CanonicalJson.text(LongNode.valueOf(Long.MIN_VALUE))).isEqualTo("-9223372036854775808");
        assertThat(CanonicalJson.text(DecimalNode.valueOf(new java.math.BigDecimal("1E+3")))).isEqualTo("1000");
    }

    @Test void serialisationIsRepeatableAndDoesNotChangeItsInput() {
        JsonNode input = parse(q("{'b':[2,1],'a':{'d':1,'c':2}}"));
        String before = input.toString();
        assertThat(CanonicalJson.bytes(input)).isEqualTo(CanonicalJson.bytes(input));
        assertThat(input.toString()).isEqualTo(before);
    }

    @Test void legalExtremeDecimalEvidenceHasBoundedExactCanonicalBytes() {
        var input = JSON.createArrayNode().add(new java.math.BigDecimal("1e-1000"))
                .add(new java.math.BigDecimal("1e1000"));
        assertThat(CanonicalJson.text(input)).isEqualTo("[1E-1000,1E+1000]");
    }

    @ParameterizedTest @ValueSource(strings={"Call +1 (202) 555-0123", "Call +91 96123 45678", "Call ＋９１９６１２３４５６７８"})
    void historicalContactTextIsRefusedBeforeAnyEvidenceBlobIsWritten(String contact) throws Exception {
        Mem m = base();
        m.edit("screening_record", SCREEN, 1, b -> ((ArrayNode)b.path("reasons")).add(contact));
        assertThatThrownBy(() -> seal(m)).isInstanceOfSatisfying(EvidenceVault.Refusal.class,
                r -> assertThat(r.code).isEqualTo(EvidenceVault.FORBIDDEN));
        assertThat(blobFiles()).isZero();
    }

    @Test void valuesWithNoUtf8OrNoFiniteFormAreRefusedExplicitly() {
        for (JsonNode bad : List.<JsonNode>of(DoubleNode.valueOf(Double.NaN), DoubleNode.valueOf(Double.POSITIVE_INFINITY),
                DoubleNode.valueOf(Double.NEGATIVE_INFINITY), FloatNode.valueOf(Float.NaN),
                TextNode.valueOf("\ud83d"), TextNode.valueOf("x\ude00"), TextNode.valueOf("\ude00\ud83d"),
                BinaryNode.valueOf(new byte[] {1}), MissingNode.getInstance(),
                new POJONode("x"))) {
            assertThatThrownBy(() -> CanonicalJson.bytes(bad)).as(bad.toString()).isInstanceOf(CanonicalJson.Unsupported.class);
        }
        ObjectNode loneKey = JSON.createObjectNode();
        loneKey.put("\ud83d", 1);
        assertThatThrownBy(() -> CanonicalJson.bytes(loneKey)).isInstanceOf(CanonicalJson.Unsupported.class);
        ObjectNode holder = JSON.createObjectNode();
        holder.set("n", DoubleNode.valueOf(Double.NaN));
        assertThatThrownBy(() -> CanonicalJson.bytes(holder)).isInstanceOf(CanonicalJson.Unsupported.class);
    }

    @Test void nestingIsBoundedButOrdinaryDepthWorks() {
        ArrayNode shallow = JSON.createArrayNode();
        ArrayNode cursor = shallow;
        for (int i = 0; i < 40; i++) cursor = cursor.addArray();
        assertThat(CanonicalJson.text(shallow)).isEqualTo("[".repeat(41) + "]".repeat(41));
        ArrayNode deep = JSON.createArrayNode();
        cursor = deep;
        for (int i = 0; i < 300; i++) cursor = cursor.addArray();
        assertThatThrownBy(() -> CanonicalJson.bytes(deep)).isInstanceOf(CanonicalJson.Unsupported.class);
    }

    // =====================================================================================================
    // 2. Fixtures: a small exact record graph written as literal canonical text
    // =====================================================================================================

    static final String INV_C = q("{'created_by':'automation','created_utc':'" + T + "','id':'" + INV + "','revision':1,'run_id':'" + RUN + "','state':'EVIDENCE_BACK'}");
    static final String SURVEY_C = q("{'created_by':'presenter@t','created_utc':'" + T + "','id':'" + SURVEY + "','investigation_id':'" + INV + "','revision':1,'run_id':'" + RUN + "'}");
    static final String MISSION_C = q("{'created_by':'presenter@t','created_utc':'" + T + "','id':'" + MISSION + "','revision':1,'run_id':'" + RUN + "','state':'COMPLETE','survey_id':'" + SURVEY + "'}");
    static final String ATTEMPT_C = q("{'created_by':'presenter@t','created_utc':'" + T + "','id':'" + ATTEMPT + "','mission_id':'" + MISSION + "','revision':1,'run_id':'" + RUN + "','state':'COMPLETE'}");
    static final String SCREEN_C = q("{'attempt_id':'" + ATTEMPT + "','channel':'SO2_ppb','created_by':'presenter@t','created_utc':'" + T + "','decision':'VALID','id':'" + SCREEN
            + "','mission_id':'" + MISSION + "','named_use':'SYNTHETIC_ONLINE_SCREENING','reasons':['WITHIN_TEACHING_SCOPE'],'revision':1,'run_id':'" + RUN + "','summary':{'mean_enhancement_ppb':null}}");
    static final String FINDING_C = q("{'alternatives':[],'case_id':'" + CASE + "','case_ref':{'id':'" + CASE + "','revision':2,'type':'case'},'cited_refs':[{'id':'" + SCREEN
            + "','revision':1,'type':'screening_record'}],'created_by':'case_reviewer@cases','created_utc':'" + T + "','id':'" + FINDING
            + "','legal_suitability':'NOT_ASSESSED','limitations':['Synthetic observation only'],'origin':'SYNTHETIC_TEACHING','prior_finding_ref':null,'reviewed_by':'case_reviewer@cases','revision':1,'run_id':'" + RUN + "'}");
    static final String CASE_LIMIT = "Synthetic teaching evidence only; air screening does not establish material identity, plume causation or legal suitability.";
    static final String CASE_C = q("{'created_by':'case_reviewer@cases','created_utc':'" + T + "','finding_ref':{'id':'" + FINDING + "','revision':1,'type':'finding'},'id':'" + CASE
            + "','investigation_id':'" + INV + "','limitations':['" + CASE_LIMIT + "'],'origin':'SYNTHETIC_TEACHING','purpose':'Teaching investigation only','revision':3,'run_id':'" + RUN
            + "','screening_record_ids':['" + SCREEN + "'],'screening_refs':[{'id':'" + SCREEN + "','revision':1,'type':'screening_record'}],'state':'FINDING'}");
    /** The same case revision as stored by the database: scrambled key order and whitespace. Canonical form must be CASE_C. */
    static final String CASE_STORED = q("{ 'state':'FINDING', 'screening_refs':[ {'type':'screening_record','revision':1,'id':'" + SCREEN + "'} ], 'screening_record_ids':['" + SCREEN
            + "'], 'run_id':'" + RUN + "', 'revision':3, 'purpose':'Teaching investigation only', 'origin':'SYNTHETIC_TEACHING', 'limitations':['" + CASE_LIMIT + "'], 'investigation_id':'" + INV
            + "', 'id':'" + CASE + "', 'finding_ref':{'type':'finding','revision':1,'id':'" + FINDING + "'}, 'created_utc':'" + T + "', 'created_by':'case_reviewer@cases' }");

    /** In-memory read view with the same get/list semantics as the real store. */
    static final class Mem implements StoreView {
        final Map<String, TreeMap<Long, StoredRecord>> rows = new HashMap<>();
        void put(String type, String id, long rev, String json) { put(type, id, rev, (ObjectNode) parse(json)); }
        void put(String type, String id, long rev, ObjectNode body) {
            StoredRecord r = new StoredRecord(type, id, rev, body.path("run_id").isNull() ? null : body.path("run_id").asText(), body.path("created_utc").asText(), body.path("created_by").asText(), body);
            rows.computeIfAbsent(type + "/" + id, k -> new TreeMap<>()).put(rev, r);
        }
        void remove(String type, String id) { rows.remove(type + "/" + id); }
        void edit(String type, String id, long rev, Consumer<ObjectNode> change) {
            ObjectNode body = get(type, id, rev).orElseThrow().body();
            change.accept(body);
            put(type, id, rev, body);
        }
        public Optional<StoredRecord> get(String type, String id) {
            var m = rows.get(type + "/" + id);
            return m == null || m.isEmpty() ? Optional.empty() : Optional.of(m.lastEntry().getValue());
        }
        public Optional<StoredRecord> get(String type, String id, long revision) {
            var m = rows.get(type + "/" + id);
            return m == null ? Optional.empty() : Optional.ofNullable(m.get(revision));
        }
        public List<StoredRecord> list(String type, String runId) {
            return listAll(type).stream().filter(r -> Objects.equals(r.runId(), runId)).collect(Collectors.toList());
        }
        public List<StoredRecord> listAll(String type) {
            return rows.entrySet().stream().filter(e -> e.getKey().startsWith(type + "/")).map(e -> e.getValue().lastEntry().getValue())
                    .sorted(Comparator.comparing(StoredRecord::id)).collect(Collectors.toList());
        }
        public long latestRevision(String type, String id) { var m = rows.get(type + "/" + id); return m == null || m.isEmpty() ? 0 : m.lastKey(); }
    }

    /** The base graph: case r3 (FINDING) pinning finding r1, one screening record and its survey lineage. */
    Mem base() {
        Mem m = new Mem();
        m.put("investigation", INV, 1, INV_C); m.put("survey", SURVEY, 1, SURVEY_C); m.put("mission", MISSION, 1, MISSION_C);
        m.put("attempt", ATTEMPT, 1, ATTEMPT_C); m.put("screening_record", SCREEN, 1, SCREEN_C);
        m.put("finding", FINDING, 1, FINDING_C); m.put("case", CASE, 3, CASE_STORED);
        return m;
    }

    static final String SPEC1 = q("{'case_id':'" + CASE + "','created_by':'inspector_demo@c','created_utc':'" + T + "','current_custody_event_id':'" + EV1 + "','current_holder':'inspector_demo@c','custody_exceptions':[],'id':'" + SPEC
            + "','mass_mg':100.0,'material':'SYNTHETIC_COAL_COKE_LIKE','method_id':'TEACH-FUEL-S-1','origin':'SYNTHETIC_TEACHING','revision':1,'run_id':'" + RUN + "','seal_state':'INTACT','site_id':'S02'}");
    static final String EV1_B = q("{'created_by':'inspector_demo@c','created_utc':'" + T + "','event_time_utc':'" + T + "','exceptions':[],'from_holder':'inspector_demo@c','id':'" + EV1
            + "','previous_event_id':null,'recorded_by':'inspector_demo@c','recorded_utc':'" + T + "','revision':1,'run_id':'" + RUN + "','seal_state':'INTACT','specimen_id':'" + SPEC + "','to_holder':'inspector_demo@c'}");
    static final String CASE2 = q("{'created_by':'case_reviewer@cases','created_utc':'" + T + "','id':'" + CASE + "','investigation_id':'" + INV + "','origin':'SYNTHETIC_TEACHING','revision':2,'run_id':'" + RUN + "','state':'LAB'}");

    /** Extended graph: specimen (2 revisions), two custody steps, a lab result pinned to older case/specimen revisions. */
    Mem withSpecimen(boolean exception) {
        Mem m = base();
        m.put("case", CASE, 2, CASE2);
        m.put("specimen", SPEC, 1, SPEC1);
        ObjectNode s2 = (ObjectNode) parse(SPEC1);
        s2.put("revision", 2).put("current_custody_event_id", EV2).put("current_holder", "lab_demo@lab").put("seal_state", exception ? "BROKEN" : "INTACT");
        ((ArrayNode) s2.path("custody_exceptions")).removeAll();
        if (exception) ((ArrayNode) s2.path("custody_exceptions")).add("BROKEN_SEAL");
        m.put("specimen", SPEC, 2, s2);
        m.put("custody_event", EV1, 1, EV1_B);
        ObjectNode e2 = (ObjectNode) parse(EV1_B);
        e2.put("id", EV2).put("previous_event_id", EV1).put("from_holder", "inspector_demo@c").put("to_holder", "lab_demo@lab").put("seal_state", exception ? "BROKEN" : "INTACT");
        if (exception) ((ArrayNode) e2.path("exceptions")).add("BROKEN_SEAL");
        m.put("custody_event", EV2, 1, e2);
        ObjectNode lab = JSON.createObjectNode();
        lab.put("id", LAB).put("run_id", RUN).put("revision", 1).put("created_utc", T).put("created_by", "lab_demo@lab").put("specimen_id", SPEC).put("qc_status", "PASS").put("origin", "SYNTHETIC_TEACHING");
        lab.set("specimen_ref", ref("specimen", SPEC, 1)); lab.set("case_ref", ref("case", CASE, 2)); lab.set("custody_ref", ref("custody_event", EV1, 1));
        lab.putObject("computed").put("percent_w_w", 2.0);
        m.put("lab_result", LAB, 1, lab);
        m.edit("case", CASE, 3, c -> { c.putArray("specimen_ids").add(SPEC); c.putArray("lab_result_ids").add(LAB); });
        m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).add(ref("lab_result", LAB, 1)));
        return m;
    }

    static ObjectNode ref(String type, String id, long rev) { return JSON.createObjectNode().put("type", type).put("id", id).put("revision", rev); }
    EvidenceVault.Sealed seal(Mem m) { return new EvidenceVault(blobs).seal(m, m.get("case", CASE, 3).orElseThrow(), SEALED_AT); }

    // =====================================================================================================
    // 3. Sealing: exact stored bytes, manifest literal, atomicity
    // =====================================================================================================

    record Expect(String path, String text) {}

    @Test void baseGraphSealsToTheLiteralMemberBytesAndLiteralManifest() throws Exception {
        List<Expect> members = new ArrayList<>(List.of(
                new Expect(path("attempt", ATTEMPT, 1), ATTEMPT_C), new Expect(path("case", CASE, 3), CASE_C),
                new Expect(path("finding", FINDING, 1), FINDING_C), new Expect(path("investigation", INV, 1), INV_C),
                new Expect(path("mission", MISSION, 1), MISSION_C), new Expect(path("screening_record", SCREEN, 1), SCREEN_C),
                new Expect(path("survey", SURVEY, 1), SURVEY_C)));
        members.sort(Comparator.comparing(Expect::path));
        var sealed = seal(base());

        StringBuilder list = new StringBuilder();
        for (Expect e : members) {
            if (list.length() > 0) list.append(',');
            list.append(q("{'length':" + utf8(e.text()).length + ",'path':'" + e.path() + "','sha256':'" + sha(utf8(e.text())) + "'}"));
        }
        String manifest = q("{'case_id':'" + CASE + "','case_revision':3,'created_utc':'" + SEALED_AT + "','limitations':['" + L1 + "','" + L2
                + "','Synthetic observation only','Custody documentation status: NOT_APPLICABLE.'],'members':[") + list + q("],'schema':'fp.evidence/1'}");
        assertThat(new String(sealed.manifestBytes(), StandardCharsets.UTF_8)).isEqualTo(manifest);
        assertThat(sealed.manifestSha256()).isEqualTo(sha(utf8(manifest)));
        assertThat(blobs.get(sealed.manifestSha256())).isEqualTo(utf8(manifest));
        assertThat(sealed.members()).hasSize(members.size());
        for (int i = 0; i < members.size(); i++) {
            Expect e = members.get(i);
            var m = sealed.members().get(i);
            assertThat(m.path()).isEqualTo(e.path());
            assertThat(m.length()).isEqualTo(utf8(e.text()).length);
            assertThat(m.sha256()).isEqualTo(sha(utf8(e.text())));
            assertThat(blobs.get(m.sha256())).as(e.path()).isEqualTo(utf8(e.text()));
        }
        assertThat(blobFiles()).isEqualTo(members.size() + 1);
        assertThat(sealed.custody().path("status").asText()).isEqualTo("NOT_APPLICABLE");
    }

    @Test void exactStoredRevisionsAreSealedNotLaterOnes() throws Exception {
        Mem m = base();
        var sealed = seal(m);
        // A later revision of a referenced record appears AFTER the seal. The sealed pack keeps the old bytes.
        m.edit("screening_record", SCREEN, 1, b -> b.put("decision", "REJECTED"));
        ObjectNode later = (ObjectNode) parse(SCREEN_C);
        later.put("revision", 2).put("decision", "REJECTED");
        m.put("screening_record", SCREEN, 2, later);
        var screening = sealed.members().stream().filter(x -> x.path().contains("/screening_record/")).findFirst().orElseThrow();
        assertThat(new String(blobs.get(screening.sha256()), StandardCharsets.UTF_8)).isEqualTo(SCREEN_C);
    }

    @Test void specimenCustodyLabAndPinnedOlderRevisionsAreAllMembers() {
        var sealed = seal(withSpecimen(true));
        Set<String> paths = sealed.members().stream().map(EvidenceVault.Member::path).collect(Collectors.toCollection(TreeSet::new));
        assertThat(paths).containsExactlyInAnyOrder(path("attempt", ATTEMPT, 1), path("case", CASE, 2), path("case", CASE, 3),
                path("finding", FINDING, 1), path("investigation", INV, 1), path("mission", MISSION, 1), path("screening_record", SCREEN, 1),
                path("survey", SURVEY, 1), path("specimen", SPEC, 1), path("specimen", SPEC, 2), path("custody_event", EV1, 1),
                path("custody_event", EV2, 1), path("lab_result", LAB, 1));
        assertThat(sealed.members()).extracting(EvidenceVault.Member::path).isSorted();
        var c = sealed.custody();
        assertThat(c.path("status").asText()).isEqualTo("LIMITED");
        assertThat(c.path("exceptions")).extracting(JsonNode::asText).containsExactly("BROKEN_SEAL");
        assertThat(c.path("seal_not_intact").asBoolean()).isTrue();
        assertThat(c.path("specimen_count").asInt()).isEqualTo(1);
        assertThat(c.path("event_count").asInt()).isEqualTo(2);
        assertThat(sealed.caseRef()).isEqualTo(ref("case", CASE, 3));
        assertThat(sealed.findingRef()).isEqualTo(ref("finding", FINDING, 1));
        assertThat(sealed.memberRefs()).hasSize(13);
    }

    @Test void intactCustodyIsPassAndNeverLimitedWithoutAnException() {
        assertThat(seal(withSpecimen(false)).custody().path("status").asText()).isEqualTo("PASS");
    }

    @Test void hashLikeAndUuidTextIsNotMistakenForAPhoneNumber() {
        Mem m = base();
        m.edit("investigation", INV, 1, b -> b.put("note", "0123456789012345678901234567890123456789012345678901234567890123").put("uuid_text", "12345678-9012-4345-8678-901234567890"));
        assertThat(seal(m).members()).isNotEmpty();
    }

    static Stream<Object[]> refusals() {
        List<Object[]> v = new ArrayList<>();
        v.add(new Object[] {"SCREENING_REVISION_MISSING", "EVIDENCE_MISSING"});
        v.add(new Object[] {"NO_SCREENING_REFS", "EVIDENCE_MISSING"});
        v.add(new Object[] {"MISSION_ABSENT", "EVIDENCE_MISSING"});
        v.add(new Object[] {"FINDING_ABSENT", "EVIDENCE_MISSING"});
        v.add(new Object[] {"FINDING_REF_MALFORMED", "EVIDENCE_MISSING"});
        v.add(new Object[] {"FOREIGN_RUN_MISSION", "EVIDENCE_MISSING"});
        v.add(new Object[] {"FINDING_OTHER_CASE", "EVIDENCE_INCOHERENT"});
        v.add(new Object[] {"FINDING_NEWER_REVISION", "EVIDENCE_INCOHERENT"});
        v.add(new Object[] {"LEGAL_ASSESSED", "EVIDENCE_INCOHERENT"});
        v.add(new Object[] {"NOT_SYNTHETIC", "EVIDENCE_INCOHERENT"});
        v.add(new Object[] {"OTHER_INVESTIGATION", "EVIDENCE_INCOHERENT"});
        v.add(new Object[] {"TWO_MISSION_BASES", "EVIDENCE_INCOHERENT"});
        v.add(new Object[] {"ATTEMPT_OTHER_MISSION", "EVIDENCE_INCOHERENT"});
        v.add(new Object[] {"CITED_NOTIFICATION", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"CITED_ALERT_SUBSCRIPTION", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"CITED_MODEL_VERSION", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"CITED_WORDING", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"CITED_CASE", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"CITED_TYPELESS", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"PHONE_PLUS91", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"PHONE_BARE", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"PHONE_KEY_NULL", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"PHONE_MASKED_KEY", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"TRUTH_KEY", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"TRAINING_LABEL_KEY", "EVIDENCE_FORBIDDEN"});
        v.add(new Object[] {"UPPERCASE_ID", "EVIDENCE_UNSAFE"});
        v.add(new Object[] {"NON_FINITE_NUMBER", "EVIDENCE_UNSAFE"});
        v.add(new Object[] {"MEMBER_TOO_LARGE", "EVIDENCE_UNSAFE"});
        return v.stream();
    }

    @ParameterizedTest @MethodSource("refusals")
    void unsafeMissingOrForbiddenEvidenceIsRefusedAndNothingIsStored(String variant, String code) throws Exception {
        Mem m = base();
        String other = OTHER;
        switch (variant) {
            case "SCREENING_REVISION_MISSING" -> m.edit("case", CASE, 3, c -> ((ObjectNode) c.path("screening_refs").get(0)).put("revision", 9));
            case "NO_SCREENING_REFS" -> m.edit("case", CASE, 3, c -> c.putArray("screening_refs"));
            case "MISSION_ABSENT" -> m.remove("mission", MISSION);
            case "FINDING_ABSENT" -> m.remove("finding", FINDING);
            case "FINDING_REF_MALFORMED" -> m.edit("case", CASE, 3, c -> c.putObject("finding_ref").put("type", "finding").put("id", FINDING).put("revision", "1"));
            case "FOREIGN_RUN_MISSION" -> m.edit("mission", MISSION, 1, b -> b.put("run_id", other));
            case "FINDING_OTHER_CASE" -> m.edit("finding", FINDING, 1, f -> f.put("case_id", other));
            case "FINDING_NEWER_REVISION" -> { ObjectNode f2 = (ObjectNode) parse(FINDING_C); f2.put("revision", 2); m.put("finding", FINDING, 2, f2); }
            case "LEGAL_ASSESSED" -> m.edit("finding", FINDING, 1, f -> f.put("legal_suitability", "ASSESSED"));
            case "NOT_SYNTHETIC" -> m.edit("case", CASE, 3, c -> c.put("origin", "PHYSICAL_BENCH"));
            case "OTHER_INVESTIGATION" -> m.edit("survey", SURVEY, 1, s -> s.put("investigation_id", other));
            case "TWO_MISSION_BASES" -> m.edit("mission", MISSION, 1, b -> b.put("proposal_id", other));
            case "ATTEMPT_OTHER_MISSION" -> m.edit("attempt", ATTEMPT, 1, b -> b.put("mission_id", other));
            case "CITED_NOTIFICATION" -> m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).add(ref("notification", OTHER, 1)));
            case "CITED_ALERT_SUBSCRIPTION" -> m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).add(ref("alert_subscription", OTHER, 1)));
            case "CITED_MODEL_VERSION" -> m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).add(ref("model_version", OTHER, 1)));
            case "CITED_WORDING" -> m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).add(ref("wording", OTHER, 1)));
            case "CITED_CASE" -> m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).add(ref("case", CASE, 3)));
            case "CITED_TYPELESS" -> m.edit("finding", FINDING, 1, f -> ((ArrayNode) f.path("cited_refs")).addObject().put("id", OTHER).put("revision", 1));
            case "PHONE_PLUS91" -> m.edit("screening_record", SCREEN, 1, s -> ((ArrayNode) s.path("reasons")).add("call +91 98765 43210 now"));
            case "PHONE_BARE" -> m.edit("screening_record", SCREEN, 1, s -> ((ArrayNode) s.path("reasons")).add("9876543210"));
            case "PHONE_KEY_NULL" -> m.edit("mission", MISSION, 1, b -> b.putNull("phone_number"));
            case "PHONE_MASKED_KEY" -> m.edit("mission", MISSION, 1, b -> b.put("phone_masked", "XXXXXX3210"));
            case "TRUTH_KEY" -> m.edit("investigation", INV, 1, b -> b.put("hidden_truth", "S02"));
            case "TRAINING_LABEL_KEY" -> m.edit("investigation", INV, 1, b -> b.put("training_label", "S02"));
            case "UPPERCASE_ID" -> {
                String upper = ATTEMPT.toUpperCase(Locale.ROOT).replace("66666666", "AAAAAAAA").replace("-4666-8666-", "-4AAA-8AAA-");
                ObjectNode a = (ObjectNode) parse(ATTEMPT_C); a.put("id", upper);
                m.put("attempt", upper, 1, a);
                m.edit("screening_record", SCREEN, 1, s -> s.put("attempt_id", upper));
            }
            case "NON_FINITE_NUMBER" -> m.edit("investigation", INV, 1, b -> b.set("bad", DoubleNode.valueOf(Double.NaN)));
            case "MEMBER_TOO_LARGE" -> m.edit("investigation", INV, 1, b -> b.put("blob", "x".repeat((1 << 20) + 10)));
            default -> throw new IllegalStateException(variant);
        }
        assertThatThrownBy(() -> seal(m)).as(variant).isInstanceOfSatisfying(EvidenceVault.Refusal.class, r -> {
            assertThat(r.code).isEqualTo(code);
            assertThat(r.getMessage()).doesNotContain("98765", "9876543210", "3210");
        });
        assertThat(blobFiles()).as("a refused seal must not leave a partial pack").isZero();
    }

    static Stream<String> specimenRefusals() {
        return Stream.of("CUSTODY_EVENT_ABSENT", "CUSTODY_LOOP", "CUSTODY_FOREIGN_SPECIMEN", "CUSTODY_UNCHAINED_EVENT", "NO_CURRENT_CUSTODY",
                "LAB_FOREIGN_SPECIMEN", "CITED_LAB_NOT_IN_CASE", "SPECIMEN_OTHER_CASE", "LAB_CASE_REF_OTHER_CASE", "LAB_PINNED_CASE_ABSENT");
    }

    @ParameterizedTest @MethodSource("specimenRefusals")
    void incoherentSpecimenCustodyOrLabLineageIsRefused(String variant) throws Exception {
        Mem m = withSpecimen(true);
        String expected = "EVIDENCE_INCOHERENT";
        switch (variant) {
            case "CUSTODY_EVENT_ABSENT" -> { m.remove("custody_event", EV1); expected = "EVIDENCE_MISSING"; }
            case "CUSTODY_LOOP" -> m.edit("custody_event", EV1, 1, e -> e.put("previous_event_id", EV2));
            case "CUSTODY_FOREIGN_SPECIMEN" -> m.edit("custody_event", EV2, 1, e -> e.put("specimen_id", OTHER));
            case "CUSTODY_UNCHAINED_EVENT" -> { ObjectNode e3 = (ObjectNode) parse(EV1_B); e3.put("id", OTHER); m.put("custody_event", OTHER, 1, e3); }
            case "NO_CURRENT_CUSTODY" -> { m.edit("specimen", SPEC, 2, s -> s.putNull("current_custody_event_id")); expected = "EVIDENCE_MISSING"; }
            case "LAB_FOREIGN_SPECIMEN" -> m.edit("lab_result", LAB, 1, l -> l.put("specimen_id", OTHER));
            case "CITED_LAB_NOT_IN_CASE" -> m.edit("case", CASE, 3, c -> c.putArray("lab_result_ids"));
            case "SPECIMEN_OTHER_CASE" -> m.edit("specimen", SPEC, 2, s -> s.put("case_id", OTHER));
            case "LAB_CASE_REF_OTHER_CASE" -> { m.put("case", OTHER, 1, CASE2.replace(CASE, OTHER)); m.edit("lab_result", LAB, 1, l -> l.set("case_ref", ref("case", OTHER, 1))); }
            case "LAB_PINNED_CASE_ABSENT" -> { m.rows.get("case/" + CASE).remove(2L); expected = "EVIDENCE_MISSING"; }
            default -> throw new IllegalStateException(variant);
        }
        String code = expected;
        assertThatThrownBy(() -> seal(m)).as(variant).isInstanceOfSatisfying(EvidenceVault.Refusal.class, r -> assertThat(r.code).isEqualTo(code));
        assertThat(blobFiles()).isZero();
    }

    @Test void blobStorageFailureIsAnExplicitRefusalNotACrash() throws Exception {
        // The blob folder is replaced by a plain file after construction: every write fails.
        Path folder = dir.resolve("blobs");
        try (Stream<Path> s = Files.list(folder)) { s.forEach(p -> p.toFile().delete()); }
        Files.delete(folder);
        Files.writeString(folder, "not a folder");
        assertThatThrownBy(() -> seal(base())).isInstanceOfSatisfying(EvidenceVault.Refusal.class, r -> assertThat(r.code).isEqualTo("EVIDENCE_STORAGE"));
    }

    // =====================================================================================================
    // 4. Verifier: located bytes against the ORIGINAL stored manifest expectations
    // =====================================================================================================

    /** The export record the seal handler is specified to save (exact field names). */
    static ObjectNode exportBody(byte[] manifest, String manifestLocator, List<EvidenceVault.Member> locators, JsonNode custody, String state) {
        ObjectNode b = JSON.createObjectNode();
        b.put("id", EXPORT).put("run_id", RUN).put("revision", 1).put("created_utc", T).put("created_by", "exporter@x");
        b.put("case_id", CASE).put("case_revision", 3).put("manifest_sha256", sha(manifest)).put("manifest_length", manifest.length);
        b.putObject("manifest").put("path", "manifest.json").put("length", manifest.length).put("sha256", manifestLocator);
        ArrayNode members = b.putArray("members");
        for (var m : locators) members.addObject().put("path", m.path()).put("length", m.length()).put("sha256", m.sha256());
        b.set("custody_summary", custody);
        b.put("state", state);
        return b;
    }
    static StoredRecord export(ObjectNode body) { return new StoredRecord("export", EXPORT, 1, RUN, T, "exporter@x", body); }
    StoredRecord exportOf(EvidenceVault.Sealed s) { return export(exportBody(s.manifestBytes(), s.manifestSha256(), s.members(), s.custody(), "SEALED")); }
    ObjectNode verify(StoredRecord export) { return new EvidenceVerifier(blobs).verify(export); }
    static List<String> list(JsonNode a) { List<String> l = new ArrayList<>(); a.forEach(n -> l.add(n.asText())); return l; }
    static JsonNode check(ObjectNode report, String path) {
        for (JsonNode c : report.path("member_checks")) if (path.equals(c.path("path").asText())) return c;
        throw new AssertionError("no member check for " + path);
    }
    /** Replaces the located bytes of one member with the same bytes with exactly one byte changed. */
    List<EvidenceVault.Member> swapped(EvidenceVault.Sealed s, String path) {
        List<EvidenceVault.Member> out = new ArrayList<>();
        for (var m : s.members()) {
            if (!m.path().equals(path)) { out.add(m); continue; }
            byte[] bytes = blobs.get(m.sha256()).clone();
            bytes[bytes.length / 2] ^= 1;
            out.add(new EvidenceVault.Member(m.path(), m.length(), blobs.put(bytes)));
        }
        return out;
    }

    static void assertConstantAxes(JsonNode axes) {
        assertThat(axes.fieldNames()).toIterable().containsExactly("integrity", "key_trust", "lineage", "trusted_time",
                "custody_documentation", "measurement_qualification", "legal_suitability");
        assertThat(axes.path("key_trust").asText()).isEqualTo("NOT_SIGNED");
        assertThat(axes.path("trusted_time").asText()).isEqualTo("UNVERIFIED");
        assertThat(axes.path("measurement_qualification").asText()).isEqualTo("SYNTHETIC_SCOPE_ONLY");
        assertThat(axes.path("legal_suitability").asText()).isEqualTo("NOT_ASSESSED");
    }

    @Test void intactPackPassesIntegrityAndLineageButNothingMoreIsClaimed() {
        var sealed = seal(withSpecimen(false));
        ObjectNode r = verify(exportOf(sealed));
        var axes = r.path("axes");
        assertConstantAxes(axes);
        assertThat(axes.path("integrity").asText()).isEqualTo("PASS");
        assertThat(axes.path("lineage").asText()).isEqualTo("PASS");
        assertThat(axes.path("custody_documentation").asText()).isEqualTo("PASS");
        assertThat(list(r.path("failed_members"))).isEmpty();
        assertThat(r.path("member_checks")).hasSize(sealed.members().size());
        for (JsonNode c : r.path("member_checks")) {
            assertThat(c.path("status").asText()).isEqualTo("PASS");
            assertThat(c.path("actual_sha256").asText()).isEqualTo(c.path("expected_sha256").asText());
            assertThat(c.path("actual_length").asLong()).isEqualTo(c.path("expected_length").asLong());
        }
        assertThat(r.path("manifest_check").path("status").asText()).isEqualTo("PASS");
        assertThat(String.join(" ", list(r.path("reasons")))).contains("unsigned").contains("not who made them");
    }

    @Test void custodyExceptionIsLimitedEvenWhenIntegrityPasses() {
        ObjectNode r = verify(exportOf(seal(withSpecimen(true))));
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("custody_documentation").asText()).isEqualTo("LIMITED");
        assertThat(r.path("axes").path("legal_suitability").asText()).isEqualTo("NOT_ASSESSED");
    }

    @Test void airScreeningOnlyPackIsNotApplicableForCustodyNeverPass() {
        assertThat(verify(exportOf(seal(base()))).path("axes").path("custody_documentation").asText()).isEqualTo("NOT_APPLICABLE");
    }

    @Test void verificationUsesStoredBytesNotTheLiveLatestRecords() {
        Mem live = withSpecimen(true);
        var sealed = seal(live);
        // Everything in the live store changes afterwards; the verifier cannot even see it.
        live.edit("case", CASE, 3, c -> c.put("state", "SEALED"));
        live.remove("screening_record", SCREEN);
        ObjectNode r = verify(exportOf(sealed));
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("PASS");
    }

    static Stream<String> everyMemberPath() {
        return Stream.of(path("attempt", ATTEMPT, 1), path("case", CASE, 2), path("case", CASE, 3), path("finding", FINDING, 1),
                path("investigation", INV, 1), path("mission", MISSION, 1), path("screening_record", SCREEN, 1), path("survey", SURVEY, 1),
                path("specimen", SPEC, 1), path("specimen", SPEC, 2), path("custody_event", EV1, 1), path("custody_event", EV2, 1), path("lab_result", LAB, 1));
    }

    @ParameterizedTest @MethodSource("everyMemberPath")
    void oneAlteredByteInAnyMemberFailsExactlyThatMemberAndKeepsCustodyAndTheConstantAxes(String altered) {
        var sealed = seal(withSpecimen(true));
        var copy = export(exportBody(sealed.manifestBytes(), sealed.manifestSha256(), swapped(sealed, altered), sealed.custody(), "TAMPER_DEMO_COPY"));
        ObjectNode r = verify(copy);
        var axes = r.path("axes");
        assertConstantAxes(axes);
        assertThat(axes.path("integrity").asText()).isEqualTo("FAIL");
        assertThat(list(r.path("failed_members"))).containsExactly(altered);
        for (JsonNode c : r.path("member_checks")) {
            boolean hit = c.path("path").asText().equals(altered);
            assertThat(c.path("status").asText()).as(c.path("path").asText()).isEqualTo(hit ? "HASH_MISMATCH" : "PASS");
            if (hit) {
                assertThat(c.path("actual_sha256").asText()).isNotEqualTo(c.path("expected_sha256").asText());
                assertThat(c.path("actual_length").asLong()).isEqualTo(c.path("expected_length").asLong());
            }
        }
        // Independent axes: integrity failure neither erases the custody exception nor invents a lineage verdict.
        assertThat(axes.path("custody_documentation").asText()).isEqualTo("LIMITED");
        assertThat(axes.path("lineage").asText()).isEqualTo("UNVERIFIED");
        // The original pack is untouched and still passes.
        assertThat(verify(exportOf(sealed)).path("axes").path("integrity").asText()).isEqualTo("PASS");
    }

    @Test void blobOnDiskMissingOrCorruptedFailsThatMemberWithoutCrashing() throws Exception {
        var sealed = seal(withSpecimen(true));
        var target = sealed.members().get(2);
        Path file = blobs.pathOf(target.sha256());
        byte[] original = Files.readAllBytes(file);
        Files.delete(file);
        ObjectNode missing = verify(exportOf(sealed));
        assertThat(check(missing, target.path()).path("status").asText()).isEqualTo("MISSING");
        assertThat(check(missing, target.path()).path("actual_sha256").isNull()).isTrue();
        assertThat(missing.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(list(missing.path("failed_members"))).containsExactly(target.path());
        Files.write(file, "damaged".getBytes(StandardCharsets.UTF_8));
        ObjectNode corrupt = verify(exportOf(sealed));
        assertThat(check(corrupt, target.path()).path("status").asText()).isEqualTo("CORRUPT");
        assertThat(corrupt.path("axes").path("custody_documentation").asText()).isEqualTo("LIMITED");
        Files.write(file, original);
        assertThat(verify(exportOf(sealed)).path("axes").path("integrity").asText()).isEqualTo("PASS");
    }

    @Test void manifestBytesAreCheckedAgainstTheStoredExpectationNotAgainstTheirOwnLocator() {
        var sealed = seal(withSpecimen(true));
        byte[] altered = sealed.manifestBytes().clone();
        altered[altered.length - 3] ^= 1;
        String alteredLocator = blobs.put(altered);
        ObjectNode r = verify(export(exportBody(sealed.manifestBytes(), alteredLocator, sealed.members(), sealed.custody(), "TAMPER_DEMO_COPY")));
        assertThat(r.path("manifest_check").path("status").asText()).isEqualTo("HASH_MISMATCH");
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(list(r.path("failed_members"))).contains("manifest.json");
        for (JsonNode c : r.path("member_checks")) assertThat(c.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("UNVERIFIED");
        assertThat(r.path("axes").path("custody_documentation").asText()).isEqualTo("LIMITED");
    }

    @Test void manifestExpectationOrLengthInTheRecordMustMatchTheHeldBytes() {
        var sealed = seal(base());
        ObjectNode wrongHash = exportBody(sealed.manifestBytes(), sealed.manifestSha256(), sealed.members(), sealed.custody(), "SEALED");
        wrongHash.put("manifest_sha256", "0".repeat(64));
        assertThat(verify(export(wrongHash)).path("manifest_check").path("status").asText()).isEqualTo("HASH_MISMATCH");
        ObjectNode wrongLength = exportBody(sealed.manifestBytes(), sealed.manifestSha256(), sealed.members(), sealed.custody(), "SEALED");
        wrongLength.put("manifest_length", sealed.manifestBytes().length + 1);
        ObjectNode r = verify(export(wrongLength));
        assertThat(r.path("manifest_check").path("status").asText()).isEqualTo("LENGTH_MISMATCH");
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL");
    }

    static Stream<String> brokenExportRecords() {
        return Stream.of("NO_MANIFEST_SHA", "MANIFEST_SHA_NOT_HEX", "NO_MANIFEST_LENGTH", "NEGATIVE_LENGTH", "NO_LOCATOR", "LOCATOR_TRAVERSAL",
                "LOCATOR_UPPERCASE", "LOCATOR_ABSENT_BLOB");
    }

    @ParameterizedTest @MethodSource("brokenExportRecords")
    void malformedExportRecordsFailClosedWithoutExceptionsOrFileAccessOutsideTheBlobFolder(String variant) {
        var sealed = seal(base());
        ObjectNode b = exportBody(sealed.manifestBytes(), sealed.manifestSha256(), sealed.members(), sealed.custody(), "SEALED");
        switch (variant) {
            case "NO_MANIFEST_SHA" -> b.remove("manifest_sha256");
            case "MANIFEST_SHA_NOT_HEX" -> b.put("manifest_sha256", "zz");
            case "NO_MANIFEST_LENGTH" -> b.remove("manifest_length");
            case "NEGATIVE_LENGTH" -> b.put("manifest_length", -1);
            case "NO_LOCATOR" -> b.remove("manifest");
            case "LOCATOR_TRAVERSAL" -> ((ObjectNode) b.path("manifest")).put("sha256", "../../../../etc/passwd");
            case "LOCATOR_UPPERCASE" -> ((ObjectNode) b.path("manifest")).put("sha256", sealed.manifestSha256().toUpperCase(Locale.ROOT));
            case "LOCATOR_ABSENT_BLOB" -> ((ObjectNode) b.path("manifest")).put("sha256", "1".repeat(64));
            default -> throw new IllegalStateException(variant);
        }
        ObjectNode r = verify(export(b));
        assertThat(r.path("axes").path("integrity").asText()).as(variant).isEqualTo("FAIL");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("UNVERIFIED");
        assertConstantAxes(r.path("axes"));
        assertThat(r.path("reasons")).isNotEmpty();
    }

    // ---- hand-built packs: internally consistent hashes, so only the structural/lineage checks can object ----

    record Hand(Map<String, String> members, StoredRecord export) {}

    Hand hand(Map<String, String> files, Consumer<ObjectNode> manifestEdit, String custodyStatus) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("schema", "fp.evidence/1").put("case_id", CASE).put("case_revision", 3).put("created_utc", SEALED_AT);
        manifest.putArray("limitations").add("hand built");
        ArrayNode list = manifest.putArray("members");
        List<EvidenceVault.Member> locators = new ArrayList<>();
        for (var e : new TreeMap<>(files).entrySet()) {
            byte[] bytes = utf8(e.getValue());
            String s = blobs.put(bytes);
            list.addObject().put("path", e.getKey()).put("length", bytes.length).put("sha256", s);
            locators.add(new EvidenceVault.Member(e.getKey(), bytes.length, s));
        }
        manifestEdit.accept(manifest);
        byte[] mb;
        try { mb = JSON.writeValueAsBytes(manifest); } catch (IOException e) { throw new IllegalStateException(e); }
        String ms = blobs.put(mb);
        ObjectNode custody = JSON.createObjectNode().put("status", custodyStatus);
        return new Hand(files, export(exportBody(mb, ms, locators, custody, "SEALED")));
    }
    static Map<String, String> baseFiles() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(path("case", CASE, 3), CASE_C); f.put(path("finding", FINDING, 1), FINDING_C); f.put(path("screening_record", SCREEN, 1), SCREEN_C);
        return f;
    }

    @Test void aSelfConsistentHandBuiltPackPassesIntegrityAndLineage() {
        var r = verify(hand(baseFiles(), m -> { }, "NOT_APPLICABLE").export());
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("PASS");
    }

    @Test void lineageFailsIndependentlyWhenAFindingCitesANonMember() {
        Map<String, String> f = baseFiles();
        f.remove(path("screening_record", SCREEN, 1));
        var r = verify(hand(f, m -> { }, "NOT_APPLICABLE").export());
        assertThat(r.path("axes").path("integrity").asText()).as("hashes are self-consistent").isEqualTo("PASS");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("FAIL");
        assertThat(String.join(" ", list(r.path("reasons")))).contains("Lineage:");
    }

    @Test void lineageFailsWhenTheSealedCaseRevisionOrFindingIsNotAMember() {
        Map<String, String> noCase = baseFiles(); noCase.remove(path("case", CASE, 3));
        assertThat(verify(hand(noCase, m -> { }, "NOT_APPLICABLE").export()).path("axes").path("lineage").asText()).isEqualTo("FAIL");
        Map<String, String> noFinding = baseFiles(); noFinding.remove(path("finding", FINDING, 1));
        assertThat(verify(hand(noFinding, m -> { }, "NOT_APPLICABLE").export()).path("axes").path("lineage").asText()).isEqualTo("FAIL");
    }

    @Test void lineageFailsOnForbiddenTypeBodyPathMismatchAndForeignRun() {
        Map<String, String> forbidden = baseFiles();
        forbidden.put(path("notification", OTHER, 1), q("{'id':'" + OTHER + "','revision':1,'run_id':'" + RUN + "'}"));
        assertThat(verify(hand(forbidden, m -> { }, "NOT_APPLICABLE").export()).path("axes").path("lineage").asText()).isEqualTo("FAIL");
        Map<String, String> mismatch = baseFiles();
        mismatch.put(path("survey", SURVEY, 1), SURVEY_C.replace(SURVEY, OTHER));
        assertThat(verify(hand(mismatch, m -> { }, "NOT_APPLICABLE").export()).path("axes").path("lineage").asText()).isEqualTo("FAIL");
        Map<String, String> foreign = baseFiles();
        foreign.put(path("survey", SURVEY, 1), SURVEY_C.replace(RUN, OTHER));
        assertThat(verify(hand(foreign, m -> { }, "NOT_APPLICABLE").export()).path("axes").path("lineage").asText()).isEqualTo("FAIL");
    }

    static Stream<String> invalidManifests() {
        return Stream.of("WRONG_SCHEMA", "EXTRA_FIELD", "MISSING_LIMITATIONS", "OTHER_CASE", "OTHER_REVISION", "NO_MEMBERS", "TRAVERSAL_PATH",
                "ABSOLUTE_PATH", "BACKSLASH_PATH", "DUPLICATE_PATH", "BAD_HASH", "NEGATIVE_LENGTH", "MEMBER_EXTRA_KEY", "MANIFEST_PATH_MEMBER");
    }

    @ParameterizedTest @MethodSource("invalidManifests")
    void structurallyInvalidButSelfHashedManifestsFailIntegrityAndNeverPartiallyPass(String variant) {
        var r = verify(hand(baseFiles(), m -> {
            ArrayNode members = (ArrayNode) m.path("members");
            switch (variant) {
                case "WRONG_SCHEMA" -> m.put("schema", "fp.evidence/2");
                case "EXTRA_FIELD" -> m.put("signature", "x");
                case "MISSING_LIMITATIONS" -> m.remove("limitations");
                case "OTHER_CASE" -> m.put("case_id", OTHER);
                case "OTHER_REVISION" -> m.put("case_revision", 9);
                case "NO_MEMBERS" -> m.putArray("members");
                case "TRAVERSAL_PATH" -> ((ObjectNode) members.get(0)).put("path", "payload/records/../../x.json");
                case "ABSOLUTE_PATH" -> ((ObjectNode) members.get(0)).put("path", "/etc/passwd");
                case "BACKSLASH_PATH" -> ((ObjectNode) members.get(0)).put("path", "payload\\records\\case\\x.json");
                case "DUPLICATE_PATH" -> members.addObject().put("path", members.get(0).path("path").asText()).put("length", 1).put("sha256", "a".repeat(64));
                case "BAD_HASH" -> ((ObjectNode) members.get(0)).put("sha256", "ABC");
                case "NEGATIVE_LENGTH" -> ((ObjectNode) members.get(0)).put("length", -1);
                case "MEMBER_EXTRA_KEY" -> ((ObjectNode) members.get(0)).put("note", "x");
                case "MANIFEST_PATH_MEMBER" -> members.addObject().put("path", "manifest.json").put("length", 1).put("sha256", "a".repeat(64));
                default -> throw new IllegalStateException(variant);
            }
        }, "NOT_APPLICABLE").export());
        assertThat(r.path("axes").path("integrity").asText()).as(variant).isEqualTo("FAIL");
        assertThat(r.path("manifest_check").path("status").asText()).isEqualTo("MANIFEST_INVALID");
        assertThat(r.path("axes").path("lineage").asText()).isEqualTo("UNVERIFIED");
        for (JsonNode c : r.path("member_checks")) assertThat(c.path("status").asText()).isEqualTo("UNVERIFIED");
    }

    @Test void memberInventoryMustMatchExactlyInBothDirections() {
        var sealed = seal(base());
        // A manifest member without a located copy.
        List<EvidenceVault.Member> fewer = new ArrayList<>(sealed.members());
        var dropped = fewer.remove(0);
        ObjectNode r1 = verify(export(exportBody(sealed.manifestBytes(), sealed.manifestSha256(), fewer, sealed.custody(), "SEALED")));
        assertThat(check(r1, dropped.path()).path("status").asText()).isEqualTo("NOT_LOCATED");
        assertThat(r1.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        // A located copy the manifest never listed.
        List<EvidenceVault.Member> more = new ArrayList<>(sealed.members());
        String extraPath = path("mission", OTHER, 1);
        more.add(new EvidenceVault.Member(extraPath, 3, blobs.put(utf8("abc"))));
        ObjectNode r2 = verify(export(exportBody(sealed.manifestBytes(), sealed.manifestSha256(), more, sealed.custody(), "SEALED")));
        assertThat(check(r2, extraPath).path("status").asText()).isEqualTo("UNLISTED");
        assertThat(r2.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(list(r2.path("failed_members"))).containsExactly(extraPath);
        // A repeated locator path.
        List<EvidenceVault.Member> dup = new ArrayList<>(sealed.members());
        dup.add(sealed.members().get(0));
        ObjectNode r3 = verify(export(exportBody(sealed.manifestBytes(), sealed.manifestSha256(), dup, sealed.custody(), "SEALED")));
        assertThat(r3.path("axes").path("integrity").asText()).isEqualTo("FAIL");
    }

    @Test void aMemberLocatorThatIsNotAHashFailsAsBadLocator() {
        var sealed = seal(base());
        ObjectNode b = exportBody(sealed.manifestBytes(), sealed.manifestSha256(), sealed.members(), sealed.custody(), "SEALED");
        ((ObjectNode) b.path("members").get(0)).put("sha256", "../../outside");
        ObjectNode r = verify(export(b));
        assertThat(r.path("member_checks").get(0).path("status").asText()).isEqualTo("BAD_LOCATOR");
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("FAIL");
    }

    @Test void aMemberWithMatchingHashButWrongLengthExpectationOrUnparseableBytesFails() {
        // Manifest says 5 bytes; the held bytes hash correctly but are longer.
        var wrongLength = verify(hand(baseFiles(), m -> ((ObjectNode) m.path("members").get(0)).put("length", 5), "NOT_APPLICABLE").export());
        assertThat(wrongLength.path("member_checks").get(0).path("status").asText()).isEqualTo("LENGTH_MISMATCH");
        assertThat(wrongLength.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        Map<String, String> notJson = baseFiles();
        notJson.put(path("survey", SURVEY, 1), "{not json");
        var bad = verify(hand(notJson, m -> { }, "NOT_APPLICABLE").export());
        assertThat(bad.path("axes").path("integrity").asText()).isEqualTo("FAIL");
        assertThat(check(bad, path("survey", SURVEY, 1)).path("status").asText()).isEqualTo("MEMBER_UNPARSEABLE");
    }

    @Test void aCoordinatedReplacementOfAWholeUnsignedPackIsNotEvidenceOfAuthenticity() {
        // An attacker rewrites a record AND the manifest AND the expectations consistently. Integrity cannot
        // detect that, so the other axes must stay honest: unsigned, time unverified, not legally assessed.
        Map<String, String> forged = baseFiles();
        forged.put(path("screening_record", SCREEN, 1), SCREEN_C.replace("'VALID'".replace('\'', '"'), "\"FORGED\""));
        var r = verify(hand(forged, m -> { }, "PASS").export());
        assertThat(r.path("axes").path("integrity").asText()).isEqualTo("PASS");
        assertConstantAxes(r.path("axes"));
        assertThat(String.join(" ", list(r.path("reasons")))).contains("unsigned");
        // A record summary that claims custody PASS cannot override sealed bytes that contain no specimen custody.
        assertThat(r.path("axes").path("custody_documentation").asText()).isEqualTo("NOT_APPLICABLE");
    }

    @Test void custodySummaryNeverBecomesPassWhenSealedBytesShowAnException() {
        // The sealed custody bytes say BROKEN; a record summary claiming PASS must not win.
        var sealed = seal(withSpecimen(true));
        ObjectNode b = exportBody(sealed.manifestBytes(), sealed.manifestSha256(), sealed.members(), JSON.createObjectNode().put("status", "PASS"), "SEALED");
        ObjectNode r = verify(export(b));
        assertThat(r.path("axes").path("custody_documentation").asText()).isEqualTo("LIMITED");
        assertThat(String.join(" ", list(r.path("reasons")))).contains("more cautious");
    }

    @Test void custodyIsUnverifiedWhenCustodyBytesFailAndNoSealedSummaryExists() {
        var sealed = seal(withSpecimen(true));
        String custodyPath = path("custody_event", EV2, 1);
        ObjectNode b = exportBody(sealed.manifestBytes(), sealed.manifestSha256(), swapped(sealed, custodyPath), sealed.custody(), "TAMPER_DEMO_COPY");
        b.remove("custody_summary");
        assertThat(verify(export(b)).path("axes").path("custody_documentation").asText()).isEqualTo("UNVERIFIED");
    }
}
