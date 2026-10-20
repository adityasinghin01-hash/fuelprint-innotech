package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Literal decimal oracles independent of production arithmetic and QC decisions. */
class LabMathTest {
    final ObjectMapper json=new ObjectMapper();
    final CasesMethod method;
    LabMathTest() throws Exception {method=new CasesMethod(json);}
    ObjectNode qc(){return json.createObjectNode().put("gross_ug",2010).put("blank_ug",10).put("dup_a_pct",2).put("dup_b_pct",2.2).put("spike_added_ug",100).put("spike_recovered_ug",90);}
    ObjectNode evaluate(ObjectNode q,double mass,Double seal,Double analysis){return LabMath.evaluate(q,DoubleNode.valueOf(mass),seal,analysis,method);}
    @Test void declaredLiteralExample(){var r=evaluate(qc(),100,0.0,300.0);assertThat(r.path("computed").path("net_ug").asDouble()).isEqualTo(2000);assertThat(r.path("computed").path("percent_w_w").asDouble()).isEqualTo(2);assertThat(r.path("computed").path("rpd_pct").asDouble()).isCloseTo(9.523809523809524,org.assertj.core.data.Offset.offset(1e-12));assertThat(r.path("computed").path("recovery_pct").asDouble()).isEqualTo(90);assertThat(r.path("qc_status").asText()).isEqualTo("PASS");}
    @ParameterizedTest @CsvSource({"1.800001,2.2,PASS","1.8,2.2,PASS","1.799999,2.2,FAIL"})
    void exactRpdCrossProductOracle(double a,double b,String want){assertThat(evaluate(qc().put("dup_a_pct",a).put("dup_b_pct",b),100,0.0,0.0).path("qc_status").asText()).isEqualTo(want);}
    @ParameterizedTest @CsvSource({"49.999,FAIL","50,PASS","50.001,PASS"})
    void literalMassBoundary(double mass,String want){assertThat(evaluate(qc(),mass,0.0,0.0).path("qc_status").asText()).isEqualTo(want);}
    @ParameterizedTest @CsvSource({"9.999,BELOW_LOQ","10,REPORTED","10.001,REPORTED","-1,BELOW_LOQ"})
    void reportingCrossProductOracle(double net,String want){var r=evaluate(qc().put("gross_ug",net+10),100,0.0,0.0);assertThat(r.path("reporting").asText()).isEqualTo(want);assertThat(r.path("computed").path("net_ug").asDouble()).isCloseTo(net,org.assertj.core.data.Offset.offset(1e-12));}
    @ParameterizedTest @CsvSource({"299.999,PASS","300,PASS","300.001,FAIL"})
    void holdDecimalBoundary(double seconds,String want){assertThat(evaluate(qc(),100,0.0,seconds).path("qc_status").asText()).isEqualTo(want);}
    @Test void zeroUndefinedAndNonfiniteRemainUnavailable(){var r=evaluate(qc().put("dup_a_pct",0).put("dup_b_pct",0).put("spike_added_ug",0),100,null,300.0);assertThat(r.path("computed").path("rpd_pct").isNull()).isTrue();assertThat(r.path("computed").path("recovery_pct").isNull()).isTrue();assertThat(r.path("hold_time_s").isNull()).isTrue();assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");var invalid=evaluate(qc().put("gross_ug",Double.NaN),100,0.0,0.0);assertThat(invalid.path("computed").path("net_ug").isNull()).isTrue();assertThat(invalid.path("qc_status").asText()).isEqualTo("FAIL");}
    @Test void overflowCannotBecomeInfinityOrPass(){var r=evaluate(qc().put("dup_a_pct",Double.MAX_VALUE).put("dup_b_pct",Double.MAX_VALUE).put("spike_recovered_ug",Double.MAX_VALUE).put("spike_added_ug",Double.MIN_VALUE),100,0.0,0.0);assertThat(r.path("computed").path("rpd_pct").asDouble()).isZero();assertThat(r.path("computed").path("recovery_pct").isNull()).isTrue();assertThat(r.path("qc_status").asText()).isNotEqualTo("PASS");assertThat(r.path("qc_reasons").toString()).contains("RECOVERY");}

    // ---- Task 5 additional pure oracles ----
    @ParameterizedTest @CsvSource({"19.999,PASS","20,PASS","20.001,FAIL"})
    void blankLimitIsInclusiveAtTwentyMicrograms(double blank,String want){var r=evaluate(qc().put("blank_ug",blank),100,0.0,0.0);assertThat(r.path("qc_status").asText()).isEqualTo(want);assertThat(r.path("qc_checks").path("blank").path("status").asText()).isEqualTo(want);}
    @ParameterizedTest @CsvSource({"79.999,FAIL","80,PASS","120,PASS","120.001,FAIL"})
    void recoveryLimitsAreInclusive(double recovered,String want){assertThat(evaluate(qc().put("spike_recovered_ug",recovered),100,0.0,0.0).path("qc_status").asText()).isEqualTo(want);}
    @Test void declaredRpdIsNineAndAHalfPercentToFourDecimals(){assertThat(evaluate(qc(),100,0.0,0.0).path("computed").path("rpd_pct").asDouble()).isCloseTo(9.5238,org.assertj.core.data.Offset.offset(5e-5));}
    @Test void negativeBlankCorrectedNetIsMeasuredNotZeroAndNotAFailure(){var r=evaluate(qc().put("gross_ug",5),100,0.0,0.0);assertThat(r.path("computed").path("net_ug").asDouble()).isEqualTo(-5);assertThat(r.path("computed").path("percent_w_w").asDouble()).isEqualTo(-0.005);assertThat(r.path("reporting").asText()).isEqualTo("BELOW_LOQ");assertThat(r.path("qc_status").asText()).isEqualTo("PASS");}
    @Test void belowLoqKeepsTheSmallPositivePercentage(){var r=evaluate(qc().put("gross_ug",19),100,0.0,0.0);assertThat(r.path("computed").path("percent_w_w").asDouble()).isEqualTo(0.009);assertThat(r.path("reporting").asText()).isEqualTo("BELOW_LOQ");}
    @Test void unknownOrBackwardsTimesLeaveHoldUnavailableWithAReason(){for(Double[] t:new Double[][]{{null,5.0},{5.0,null},{10.0,5.0},{Double.NaN,5.0},{5.0,Double.POSITIVE_INFINITY}}){var r=evaluate(qc(),100,t[0],t[1]);assertThat(r.path("hold_time_s").isNull()).as(java.util.Arrays.toString(t)).isTrue();assertThat(r.path("hold_time_reason").asText()).isNotBlank();assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");assertThat(r.path("qc_checks").path("hold").path("status").asText()).isEqualTo("PENDING");}}
    @Test void nullQcInputsAreEntirelyPendingWithEveryFieldNamedAndNothingInvented(){var r=LabMath.evaluate(null,DoubleNode.valueOf(100),0.0,0.0,method);assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");for(String f:LabMath.QC_FIELDS){assertThat(r.path("raw").path(f).isNull()).isTrue();assertThat(r.path("qc_reasons").toString()).contains(f);}assertThat(r.path("computed").path("net_ug").isNull()).isTrue();assertThat(r.path("reporting").isNull()).isTrue();}
    @Test void knownFailuresStayVisibleWhileAnotherInputIsMissing(){var q=qc().put("blank_ug",30);q.remove("spike_recovered_ug");var r=evaluate(q,100,0.0,301.0);assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");assertThat(r.path("qc_checks").path("blank").path("status").asText()).isEqualTo("FAIL");assertThat(r.path("qc_checks").path("hold").path("status").asText()).isEqualTo("FAIL");assertThat(r.path("qc_checks").path("recovery").path("status").asText()).isEqualTo("PENDING");assertThat(r.path("qc_reasons").toString()).contains("BLANK_ABOVE_LIMIT","HOLD_TIME_EXCEEDED","spike_recovered_ug");}
    @Test void invalidRawValuesFailTheirOwnCriterionAndAreNeverUsedInArithmetic(){var q=qc();q.put("dup_a_pct",-1).put("spike_added_ug","100");var r=evaluate(q,100,0.0,0.0);assertThat(r.path("qc_status").asText()).isEqualTo("FAIL");assertThat(r.path("raw").path("dup_a_pct").asInt()).isEqualTo(-1);assertThat(r.path("raw").path("spike_added_ug").asText()).isEqualTo("100");assertThat(r.path("computed").path("rpd_pct").isNull()).isTrue();assertThat(r.path("computed").path("recovery_pct").isNull()).isTrue();assertThat(r.path("qc_checks").path("duplicate").path("status").asText()).isEqualTo("FAIL");assertThat(r.path("qc_checks").path("recovery").path("status").asText()).isEqualTo("FAIL");assertThat(r.path("computed").path("net_ug").asDouble()).isEqualTo(2000);}
    @Test void zeroOrMissingMassKeepsPercentageUnavailableAndNeverZero(){var zero=LabMath.evaluate(qc(),DoubleNode.valueOf(0),0.0,0.0,method);assertThat(zero.path("computed").path("percent_w_w").isNull()).isTrue();assertThat(zero.path("reporting").isNull()).isTrue();assertThat(zero.path("qc_status").asText()).isEqualTo("PENDING");assertThat(zero.path("qc_checks").path("mass").path("status").asText()).isEqualTo("FAIL");
        var missing=LabMath.evaluate(qc(),com.fasterxml.jackson.databind.node.NullNode.getInstance(),0.0,0.0,method);assertThat(missing.path("computed").path("percent_w_w").isNull()).isTrue();assertThat(missing.path("qc_status").asText()).isEqualTo("PENDING");assertThat(missing.path("raw").path("mass_mg").isNull()).isTrue();}
    @Test void rawAndComputedPreserveTheOriginalInputsExactly(){var q=qc();var r=evaluate(q,100,0.0,0.0);for(String f:LabMath.QC_FIELDS)assertThat(r.path("raw").path(f)).isEqualTo(q.path(f));assertThat(r.path("raw").path("mass_mg").asInt()).isEqualTo(100);}
    @Test void methodLimitsComeFromThePackagedProfileNotTheOracleMass(){var l=method.limits();assertThat(l.path("minimum_mass_mg").asInt()).isEqualTo(50);assertThat(l.path("hold_s").asInt()).isEqualTo(300);assertThat(l.path("loq_percent_w_w").asDouble()).isEqualTo(0.01);assertThat(l.toString()).doesNotContain("oracle");}

    // ---- Fix round 1 (R2/R3): independent 10^312 and subnormal literal oracles, decimal ingress precision ----
    static final java.math.BigInteger HUGE=java.math.BigInteger.TEN.pow(312);
    com.fasterxml.jackson.databind.node.JsonNodeFactory nf=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"gross_ug","blank_ug","dup_a_pct","dup_b_pct","spike_added_ug","spike_recovered_ug"})
    void integerAboveBinary64NeverPassesInAnyField(String field){var q=qc();q.set(field,nf.numberNode(HUGE));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("qc_status").asText()).as(field).isNotEqualTo("PASS");assertThat(r.path("raw").path(field).bigIntegerValue()).isEqualTo(HUGE);assertThat(r.path("qc_reasons").toString()).contains(field);}
    @Test void hugeGrossLeavesNetAndPercentUnavailableWithAReasonAndNoReporting(){var q=qc();q.set("gross_ug",nf.numberNode(HUGE));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("computed").path("net_ug").isNull()).isTrue();assertThat(r.path("computed").path("percent_w_w").isNull()).isTrue();assertThat(r.path("reporting").isNull()).isTrue();assertThat(r.path("qc_checks").path("net").path("status").asText()).isNotEqualTo("PASS");}
    @Test void subnormalGrossWithZeroBlankKeepsNonzeroNetButPercentUnderflowIsUnavailable(){var q=qc().put("blank_ug",0);q.set("gross_ug",nf.numberNode(new java.math.BigDecimal("5e-324")));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("computed").path("net_ug").asDouble()).isEqualTo(Double.MIN_VALUE);assertThat(r.path("computed").path("percent_w_w").isNull()).isTrue();assertThat(r.path("computed_reasons").path("percent_w_w").asText()).isEqualTo("NOT_REPRESENTABLE");
        assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");assertThat(r.path("reporting").asText()).isEqualTo("BELOW_LOQ");assertThat(r.path("raw").path("gross_ug").decimalValue()).isEqualByComparingTo(new java.math.BigDecimal("5e-324"));}
    @Test void nonzeroInputBelowBinary64RangeIsNotTurnedIntoZero(){var q=qc();q.set("gross_ug",nf.numberNode(new java.math.BigDecimal("1e-400")));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("qc_status").asText()).isNotEqualTo("PASS");assertThat(r.path("computed").path("net_ug").isNull()).isTrue();assertThat(r.path("raw").path("gross_ug").decimalValue()).isEqualByComparingTo(new java.math.BigDecimal("1e-400"));}
    @Test void rpdUnderflowStaysUnavailableAndCannotPass(){var q=qc();java.math.BigDecimal a=new java.math.BigDecimal("1e300");q.set("dup_a_pct",nf.numberNode(a));q.set("dup_b_pct",nf.numberNode(a.add(new java.math.BigDecimal("1e-300"))));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("computed").path("rpd_pct").isNull()).as("true RPD is about 1e-600, not 0").isTrue();assertThat(r.path("computed_reasons").path("rpd_pct").asText()).isEqualTo("NOT_REPRESENTABLE");assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");}
    @Test void recoveryUnderflowAndOverflowStayUnavailable(){var q=qc();q.set("spike_added_ug",nf.numberNode(new java.math.BigDecimal("1e300")));q.set("spike_recovered_ug",nf.numberNode(new java.math.BigDecimal("1e-300")));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("computed").path("recovery_pct").isNull()).isTrue();assertThat(r.path("qc_checks").path("recovery").path("status").asText()).isEqualTo("FAIL");assertThat(r.path("qc_status").asText()).isNotEqualTo("PASS");}
    @org.junit.jupiter.params.ParameterizedTest @CsvSource({"20.0000000000000001,FAIL","20.0,PASS","19.9999999999999999,PASS"})
    void decimalNodeBlankJustAboveExactAndBelowTheLimit(String literal,String want){var q=qc();q.set("blank_ug",nf.numberNode(new java.math.BigDecimal(literal)));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("qc_checks").path("blank").path("status").asText()).as(literal).isEqualTo(want);assertThat(r.path("raw").path("blank_ug").decimalValue()).isEqualByComparingTo(new java.math.BigDecimal(literal));}
    @Test void negativeNetStaysExactAndMeasuredWhenOtherwiseValid(){var q=qc().put("gross_ug",5);var r=evaluate(q,100,0.0,0.0);assertThat(r.path("computed").path("net_ug").asDouble()).isEqualTo(-5);assertThat(r.path("qc_status").asText()).isEqualTo("PASS");}
    @Test void missingAndInvalidRemainDistinctUnderTheNewRangeRule(){var q=qc();q.remove("gross_ug");q.set("blank_ug",nf.numberNode(HUGE));var r=evaluate(q,100,0.0,0.0);
        assertThat(r.path("qc_checks").path("blank").path("status").asText()).isEqualTo("FAIL");assertThat(r.path("qc_reasons").toString()).contains("gross_ug is missing","blank_ug");assertThat(r.path("qc_status").asText()).isEqualTo("PENDING");}
}
