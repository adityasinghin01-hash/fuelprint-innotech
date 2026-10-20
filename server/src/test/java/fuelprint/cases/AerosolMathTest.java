package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** B4 pure aerosol oracles: literal expected values from TEACH-AEROSOL-S-1, never derived with the code under test. */
class AerosolMathTest {
    final ObjectMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    final AerosolMethod method;
    AerosolMathTest() throws Exception { method = new AerosolMethod(json); }

    JsonNode parse(String s) { try { return json.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); } }
    ObjectNode collection(String validS, String flow, String status) {
        return (ObjectNode) parse("{\"start_sim_s\":100,\"end_sim_s\":800,\"valid_s\":" + validS + ",\"flow_L_min\":" + flow + ",\"status\":\"" + status + "\"}");
    }
    /** A saved media body as create_media writes it. */
    ObjectNode media(String validS, String flow, String status) {
        ObjectNode m = json.createObjectNode();
        AerosolMath.collection(collection(validS, flow, status), 1000.0).fields().forEachRemaining(e -> m.set(e.getKey(), e.getValue()));
        return m;
    }
    ObjectNode qc() { return (ObjectNode) parse("{\"gross_ug\":1.2,\"blank_ug\":0.01,\"dup_a_ug\":1.0,\"dup_b_ug\":1.1,\"spike_added_ug\":1.0,\"spike_recovered_ug\":0.95}"); }
    ObjectNode eval(ObjectNode q, ObjectNode media, Double analysis) { return AerosolMath.evaluate(q, media, analysis, method); }
    ObjectNode eval(ObjectNode q) { return eval(q, media("600", "2", "COMPLETE"), 1000.0); }
    String status(ObjectNode r, String check) { return r.path("qc_checks").path(check).path("status").asText(); }

    @Test void twoLitresPerMinuteForSixHundredValidSecondsIsTwentyLitres() {
        var m = media("600", "2", "COMPLETE");
        assertThat(m.path("collection_computed").path("volume_L").asDouble()).isEqualTo(20.0);
        assertThat(m.path("collection_computed").path("volume_m3").asDouble()).isEqualTo(0.02);
        assertThat(m.path("collection_computed").path("computed_decimal").path("volume_L").asText()).isEqualTo("20");
        assertThat(m.path("collection_computed").path("computed_decimal").path("volume_m3").asText()).isEqualTo("0.02");
        assertThat(m.path("collection_computed").path("calculation_precision").asText()).isEqualTo("DECIMAL128_HALF_EVEN");
        assertThat(m.path("collection").path("time_check").asText()).isEqualTo("CHECKED");
    }

    @Test void declaredExamplePassesAndIsUsableWithLiteralValues() {
        var r = eval(qc());
        assertThat(r.path("qc_status").asText()).as(r.toString()).isEqualTo("PASS");
        assertThat(r.path("result_use").asText()).isEqualTo("USABLE");
        assertThat(r.path("reporting").asText()).isEqualTo("REPORTED");
        assertThat(r.path("computed_decimal").path("net_ug").asText()).isEqualTo("1.19");
        assertThat(r.path("computed_decimal").path("concentration_ug_m3").asText()).isEqualTo("59.5");      // 1.19 ug * 1000 / 20 L
        assertThat(r.path("computed").path("recovery_pct").asDouble()).isEqualTo(95.0);
        assertThat(r.path("computed").path("rpd_pct").asDouble()).isCloseTo(9.523809523809524, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(r.path("hold_time_s").asDouble()).isEqualTo(200.0);
        assertThat(r.path("calculation_precision").asText()).isEqualTo("DECIMAL128_HALF_EVEN");
        assertThat(r.path("diagnostics")).isEmpty();
    }

    @Test void nonTerminatingDivisionUsesDecimal128HalfEvenAndExactDecisions() {
        var m = media("599.999", "2", "COMPLETE");
        // 2 * 599.999 / 60 = 19.99996666... ; DECIMAL128 (34 digits) HALF_EVEN.
        assertThat(m.path("collection_computed").path("computed_decimal").path("volume_L").asText()).isEqualTo("19.99996666666666666666666666666667");
        var r = eval(qc(), m, 1000.0);
        assertThat(status(r, "collection")).isEqualTo("FAIL");
        assertThat(r.path("qc_reasons").toString()).contains("INSUFFICIENT_COLLECTION");
        assertThat(r.path("result_use").asText()).isEqualTo("NOT_USABLE");
    }

    @ParameterizedTest @CsvSource({"600,PASS", "600.000,PASS", "599.999,FAIL", "700,PASS"})
    void collectionValidSecondsBoundary(String valid, String want) { assertThat(status(eval(qc(), media(valid, "2", "COMPLETE"), 1000.0), "collection")).isEqualTo(want); }

    @Test void interruptedCollectionFailsQcButIsRecorded() {
        var r = eval(qc(), media("600", "2", "INTERRUPTED"), 1000.0);
        assertThat(status(r, "collection")).isEqualTo("FAIL");
        assertThat(r.path("qc_reasons").toString()).contains("COLLECTION_INTERRUPTED");
    }

    @ParameterizedTest @CsvSource({"2,PASS", "2.0,PASS", "1.999,FAIL", "2.001,FAIL"})
    void flowMustBeTheDeclaredFlowExactly(String flow, String want) {
        var r = eval(qc(), media("700", flow, "COMPLETE"), 1000.0);
        assertThat(status(r, "flow")).isEqualTo(want);
    }

    @ParameterizedTest @CsvSource({"0.02,PASS", "0.019999,PASS", "0.020001,FAIL"})
    void blankLimitIsInclusive(String blank, String want) { assertThat(status(eval(qc().put("blank_ug", new BigDecimal(blank))), "blank")).isEqualTo(want); }

    @ParameterizedTest @CsvSource({"0.11,REPORTED,USABLE", "0.109999,BELOW_LOQ,LIMITED_BELOW_LOQ", "0.110001,REPORTED,USABLE"})
    void loqBoundaryOnBlankCorrectedMass(String gross, String reporting, String use) {
        var r = eval(qc().put("gross_ug", new BigDecimal(gross)));   // blank 0.01: net 0.1, 0.099999, 0.100001
        assertThat(r.path("reporting").asText()).isEqualTo(reporting);
        assertThat(r.path("result_use").asText()).isEqualTo(use);
        assertThat(r.path("qc_status").asText()).isEqualTo("PASS");
        assertThat(r.path("computed").path("net_ug").isNull()).isFalse();
    }

    @Test void negativeCorrectedMassIsKeptWithADiagnosticWhileNegativeRawInputIsInvalid() {
        var r = eval(qc().put("gross_ug", new BigDecimal("0.01")).put("blank_ug", new BigDecimal("0.02")));
        assertThat(r.path("computed_decimal").path("net_ug").asText()).isEqualTo("-0.01");
        assertThat(r.path("computed").path("net_ug").asDouble()).isEqualTo(-0.01);
        assertThat(r.path("diagnostics").toString()).contains("NEGATIVE_CORRECTED_MASS");
        assertThat(r.path("reporting").asText()).isEqualTo("BELOW_LOQ");
        assertThat(status(r, "net")).isEqualTo("PASS");
        assertThat(r.path("result_use").asText()).isEqualTo("LIMITED_BELOW_LOQ");
        var bad = eval(qc().put("blank_ug", new BigDecimal("-0.01")));
        assertThat(status(bad, "blank")).isEqualTo("FAIL");
        assertThat(bad.path("qc_reasons").toString()).contains("INVALID_INPUT: blank_ug is negative");
        assertThat(bad.path("result_use").asText()).isEqualTo("NOT_USABLE");
    }

    @ParameterizedTest @CsvSource({"0.9,1.1,PASS", "0.9,1.1001,FAIL", "1.0,1.0,PASS"})
    void duplicateRpdTwentyPercentIsInclusive(String a, String b, String want) {
        assertThat(status(eval(qc().put("dup_a_ug", new BigDecimal(a)).put("dup_b_ug", new BigDecimal(b))), "duplicate")).isEqualTo(want);
    }

    @Test void zeroDuplicatesAndZeroSpikeArePendingNotPass() {
        var r = eval(qc().put("dup_a_ug", 0).put("dup_b_ug", 0).put("spike_added_ug", 0));
        assertThat(status(r, "duplicate")).isEqualTo("PENDING");
        assertThat(status(r, "recovery")).isEqualTo("PENDING");
        assertThat(r.path("computed").path("rpd_pct").isNull()).isTrue();
        assertThat(r.path("computed_reasons").path("rpd_pct").asText()).isEqualTo("duplicate mean is zero");
        assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");
        assertThat(r.path("result_use").asText()).isEqualTo("PENDING");
    }

    @ParameterizedTest @CsvSource({"0.8,PASS", "1.2,PASS", "0.79999,FAIL", "1.20001,FAIL"})
    void recoveryEightyToOneHundredTwentyInclusive(String recovered, String want) {
        assertThat(status(eval(qc().put("spike_recovered_ug", new BigDecimal(recovered))), "recovery")).isEqualTo(want);
    }

    @ParameterizedTest @CsvSource({"1100,PASS", "1100.001,FAIL", "800,PASS"})
    void holdTimeFromCollectionEndIsInclusive(double analysis, String want) {
        assertThat(status(eval(qc(), media("600", "2", "COMPLETE"), analysis), "hold")).isEqualTo(want);
    }

    @Test void unknownOrBackwardsHoldIsPending() {
        for (Double t : new Double[]{null, 799.0, Double.NaN}) {
            var r = eval(qc(), media("600", "2", "COMPLETE"), t);
            assertThat(status(r, "hold")).as(String.valueOf(t)).isEqualTo("PENDING");
            assertThat(r.path("hold_time_s").isNull()).isTrue();
            assertThat(r.path("hold_time_reason").asText()).isNotBlank();
        }
    }

    @Test void missingInputsStayNullWithReasonsAndKnownFailuresStayVisible() {
        var q = qc().put("blank_ug", new BigDecimal("0.5")); q.remove("spike_recovered_ug");
        var r = eval(q);
        assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");
        assertThat(status(r, "blank")).isEqualTo("FAIL");
        assertThat(r.path("computed").path("recovery_pct").isNull()).isTrue();
        assertThat(r.path("qc_reasons").toString()).contains("spike_recovered_ug is missing", "BLANK_ABOVE_LIMIT");
        var none = AerosolMath.evaluate(null, media("600", "2", "COMPLETE"), 1000.0, method);
        assertThat(none.path("qc_status").asText()).isEqualTo("PENDING");
        assertThat(none.path("reporting").isNull()).isTrue();
        assertThat(none.path("computed").path("net_ug").isNull()).isTrue();
        for (String f : AerosolMath.QC_FIELDS) assertThat(none.path("raw").path(f).isNull()).isTrue();
    }

    @Test void derivedOverflowAndUnderflowAreNotRepresentableAndNeverUsable() {
        // Underflow: 100 * 1e-300 / 1e300 = 1e-598, nonzero but would become 0.0 as binary64.
        var under = eval(qc().put("spike_recovered_ug", new BigDecimal("1e-300")).put("spike_added_ug", new BigDecimal("1e300")));
        assertThat(under.path("computed").path("recovery_pct").isNull()).isTrue();
        assertThat(under.path("computed_reasons").path("recovery_pct").asText()).isEqualTo("NOT_REPRESENTABLE");
        assertThat(under.path("computed_decimal").path("recovery_pct").asText()).isEqualTo("1E-598");
        assertThat(under.path("result_use").asText()).isNotEqualTo("USABLE");
        // Overflow: concentration = 1e300 * 1000 / (2 * 1e-10 / 60).
        var tiny = json.createObjectNode();
        AerosolMath.collection((ObjectNode) parse("{\"start_sim_s\":0,\"end_sim_s\":1,\"valid_s\":1e-10,\"flow_L_min\":2,\"status\":\"COMPLETE\"}"), 1000.0)
                .fields().forEachRemaining(e -> tiny.set(e.getKey(), e.getValue()));
        var over = eval(qc().put("gross_ug", new BigDecimal("1e300")), tiny, 1000.0);
        assertThat(over.path("computed").path("concentration_ug_m3").isNull()).isTrue();
        assertThat(over.path("computed_reasons").path("concentration_ug_m3").asText()).isEqualTo("NOT_REPRESENTABLE");
        assertThat(over.path("computed_decimal").path("concentration_ug_m3").asText()).isNotBlank();
        assertThat(over.path("qc_status").asText()).isEqualTo("PENDING");
        assertThat(over.toString()).doesNotContain("Infinity").doesNotContain("NaN");
    }

    @Test void impossibleCollectionsAreRefusedAtEntry() {
        String[] bad = {
                "{\"start_sim_s\":100,\"end_sim_s\":100,\"valid_s\":1,\"flow_L_min\":2,\"status\":\"COMPLETE\"}",
                "{\"start_sim_s\":100,\"end_sim_s\":200,\"valid_s\":101,\"flow_L_min\":2,\"status\":\"COMPLETE\"}",
                "{\"start_sim_s\":100,\"end_sim_s\":200,\"valid_s\":0,\"flow_L_min\":2,\"status\":\"COMPLETE\"}",
                "{\"start_sim_s\":100,\"end_sim_s\":200,\"valid_s\":50,\"flow_L_min\":0,\"status\":\"COMPLETE\"}",
                "{\"start_sim_s\":-1,\"end_sim_s\":200,\"valid_s\":50,\"flow_L_min\":2,\"status\":\"COMPLETE\"}",
                "{\"start_sim_s\":100,\"end_sim_s\":200,\"valid_s\":50,\"flow_L_min\":\"2\",\"status\":\"COMPLETE\"}",
                "{\"start_sim_s\":100,\"end_sim_s\":200,\"valid_s\":50,\"status\":\"COMPLETE\"}",
                "{\"start_sim_s\":100,\"end_sim_s\":200,\"valid_s\":50,\"flow_L_min\":2,\"status\":\"DONE\"}",
                "{\"start_sim_s\":100,\"end_sim_s\":200,\"valid_s\":50,\"flow_L_min\":2,\"status\":\"COMPLETE\",\"volume_L\":20}",
                "{\"start_sim_s\":100,\"end_sim_s\":2000,\"valid_s\":50,\"flow_L_min\":2,\"status\":\"COMPLETE\"}",
                "[]"};
        for (String b : bad) assertThatThrownBy(() -> AerosolMath.collection(parse(b), 1000.0)).as(b).isInstanceOf(AerosolMath.Refused.class);
        var unchecked = AerosolMath.collection(parse("{\"start_sim_s\":100,\"end_sim_s\":2000,\"valid_s\":600,\"flow_L_min\":2,\"status\":\"COMPLETE\"}"), null);
        assertThat(unchecked.path("collection").path("time_check").asText()).isEqualTo("UNCHECKED");
        assertThat(unchecked.path("collection").path("time_check_reason").asText()).isEqualTo("CURRENT_RUN_CLOCK_UNAVAILABLE");
    }

    @Test void profileValuesAreTheDeclaredOnes() {
        var l = method.limits();
        assertThat(l.path("flow_L_min").decimalValue()).isEqualByComparingTo("2");
        assertThat(l.path("valid_s").decimalValue()).isEqualByComparingTo("600");
        assertThat(l.path("loq_ug").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(l.path("blank_max_ug").decimalValue()).isEqualByComparingTo("0.02");
        assertThat(l.path("duplicate_rpd_max_percent").decimalValue()).isEqualByComparingTo("20");
        assertThat(l.path("hold_s").decimalValue()).isEqualByComparingTo("300");
        assertThat(l.path("size_class").asText()).isEqualTo("UNCLASSIFIED");
        assertThat(l.toString()).doesNotContain("oracle_efficiency");
    }
}
