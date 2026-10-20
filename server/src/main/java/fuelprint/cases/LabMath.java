package fuelprint.cases;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure, side-effect-free laboratory calculation (teaching fuel assay).
 *
 * <p>Every decision uses exact decimal values and cross-multiplication, never a rounded or
 * binary64 quotient, so a value exactly on a declared limit passes that limit (limits are inclusive).
 * Doubles are only used for the numbers shown. An unknown value stays null with a reason; it is never
 * turned into zero or a pass. Known failures stay visible even when another input is missing.
 *
 * <p>Overall status: PENDING if any input is missing or any arithmetic is undefined; otherwise FAIL if any
 * input is invalid or any limit is broken; otherwise PASS.
 */
final class LabMath {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100), TWO_HUNDRED = BigDecimal.valueOf(200),
            THOUSAND = BigDecimal.valueOf(1000);
    static final List<String> QC_FIELDS = List.of("gross_ug", "blank_ug", "dup_a_pct", "dup_b_pct", "spike_added_ug", "spike_recovered_ug");

    private LabMath() {}

    /** A raw input after validation: exactly one of value, missing or invalid is set. */
    private record Input(JsonNode raw, BigDecimal value, boolean missing, String invalidReason) {}

    private static Input read(JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) return new Input(null, null, true, null);
        if (!raw.isNumber()) return new Input(raw, null, false, "is not a number");
        if (raw.isFloatingPointNumber() && !Double.isFinite(raw.doubleValue())) return new Input(raw, null, false, "is not finite");
        BigDecimal value = raw.decimalValue();
        if (value.signum() < 0) return new Input(raw, null, false, "is negative");
        // Supported range: a nonzero input must be a normal reading a double can show. Anything beyond
        // (too large, or so small it would show as zero) is kept as sent but is never used in arithmetic.
        if (shown(value) == null) return new Input(raw, null, false, "is outside the supported number range");
        return new Input(raw, value, false, null);
    }

    /** The number as a finite double, or null if it would overflow, or if a nonzero value would underflow to zero. */
    private static Double shown(BigDecimal value) {
        double d = value.doubleValue();
        if (!Double.isFinite(d)) return null;
        if (d == 0 && value.signum() != 0) return null;
        return d;
    }

    /** One QC criterion's outcome. A known failure is never overwritten by a later unknown. */
    private static final class Check {
        String status = "PASS";
        boolean unknown;
        final List<String> reasons = new ArrayList<>();
        void fail(String reason) { status = "FAIL"; reasons.add(reason); }
        void unknown(String reason) { unknown = true; if (!"FAIL".equals(status)) status = "PENDING"; reasons.add(reason); }
        void input(String name, Input in) {
            if (in.missing) unknown(name + " is missing");
            else if (in.invalidReason != null) fail("INVALID_INPUT: " + name + " " + in.invalidReason);
        }
    }

    /**
     * @param qcInputs the lab person's numbers exactly as sent (null if none were sent)
     * @param mass the specimen's recorded mass_mg
     * @param sealedSimS simulation second at which the specimen was sealed, or null if unknown
     * @param analysisSimS simulation second of this analysis in the same run, or null if unknown
     */
    static ObjectNode evaluate(JsonNode qcInputs, JsonNode mass, Double sealedSimS, Double analysisSimS, CasesMethod method) {
        Map<String, Input> in = new LinkedHashMap<>();
        for (String field : QC_FIELDS) in.put(field, read(qcInputs == null ? null : qcInputs.get(field)));
        Input massIn = read(mass), gross = in.get("gross_ug"), blankIn = in.get("blank_ug"),
                a = in.get("dup_a_pct"), b = in.get("dup_b_pct"), added = in.get("spike_added_ug"), got = in.get("spike_recovered_ug");
        ObjectNode raw = CasesRecords.object();
        for (String field : QC_FIELDS) putRaw(raw, field, in.get(field));
        putRaw(raw, "mass_mg", massIn);
        ObjectNode computed = CasesRecords.object(), computedReasons = CasesRecords.object();

        // Specimen mass
        Check massCheck = new Check();
        massCheck.input("mass_mg", massIn);
        if (massIn.value != null && massIn.value.compareTo(method.minimumMass()) < 0)
            massCheck.fail("MASS_BELOW_MINIMUM: mass_mg is below " + method.minimumMass().toPlainString() + " mg");

        // Blank
        Check blank = new Check();
        blank.input("blank_ug", blankIn);
        if (blankIn.value != null && blankIn.value.compareTo(method.blankMaxUg()) > 0)
            blank.fail("BLANK_ABOVE_LIMIT: blank is above " + method.blankMaxUg().toPlainString() + " ug");

        // Net amount (a negative blank-corrected result is a real measurement, not an error) and concentration
        Check netCheck = new Check();
        netCheck.input("gross_ug", gross);
        BigDecimal net = null, percent = null;
        if (gross.value != null && blankIn.value != null) net = gross.value.subtract(blankIn.value);
        else computedReasons.put("net_ug", "gross_ug or blank_ug is missing or invalid");
        if (net != null) {
            if (massIn.value == null) computedReasons.put("percent_w_w", "mass_mg is missing or invalid");
            else if (massIn.value.signum() == 0) {
                computedReasons.put("percent_w_w", "mass_mg is zero");
                netCheck.unknown("PERCENT_W_W_UNDEFINED: mass_mg is zero");
            } else percent = net.multiply(HUNDRED).divide(massIn.value.multiply(THOUSAND), MC);
        } else computedReasons.put("percent_w_w", "net_ug unavailable");
        if (show(computed, computedReasons, "net_ug", net)) netCheck.unknown("NOT_REPRESENTABLE: net_ug cannot be shown as a number");
        if (show(computed, computedReasons, "percent_w_w", percent)) netCheck.unknown("NOT_REPRESENTABLE: percent_w_w cannot be shown as a number");

        // Duplicate relative percent difference: 200|a-b|/(a+b) <= limit  <=>  200|a-b| <= limit*(a+b)
        Check dup = new Check();
        dup.input("dup_a_pct", a);
        dup.input("dup_b_pct", b);
        BigDecimal rpd = null;
        if (a.value != null && b.value != null) {
            BigDecimal sum = a.value.add(b.value), scaledDiff = a.value.subtract(b.value).abs().multiply(TWO_HUNDRED);
            if (sum.signum() == 0) {
                computedReasons.put("rpd_pct", "duplicate mean is zero");
                dup.unknown("RPD_UNDEFINED: duplicate mean is zero");
            } else {
                rpd = scaledDiff.divide(sum, MC);
                if (scaledDiff.compareTo(method.rpdMaxPercent().multiply(sum)) > 0)
                    dup.fail("DUPLICATE_RPD_ABOVE_LIMIT: duplicate difference is above " + method.rpdMaxPercent().toPlainString() + " percent");
            }
        } else computedReasons.put("rpd_pct", "duplicate inputs missing or invalid");
        if (show(computed, computedReasons, "rpd_pct", rpd)) dup.unknown("NOT_REPRESENTABLE: rpd_pct cannot be shown as a number");

        // Spike recovery: low*added <= 100*recovered <= high*added
        Check recovery = new Check();
        recovery.input("spike_added_ug", added);
        recovery.input("spike_recovered_ug", got);
        BigDecimal rec = null;
        if (added.value != null && added.value.signum() == 0) {
            computedReasons.put("recovery_pct", "spike_added_ug is zero");
            recovery.unknown("RECOVERY_UNDEFINED: spike_added_ug is zero");
        } else if (added.value != null && got.value != null) {
            BigDecimal scaled = got.value.multiply(HUNDRED);
            rec = scaled.divide(added.value, MC);
            if (scaled.compareTo(method.recoveryLow().multiply(added.value)) < 0)
                recovery.fail("RECOVERY_BELOW_LIMIT: recovery is below " + method.recoveryLow().toPlainString() + " percent");
            else if (scaled.compareTo(method.recoveryHigh().multiply(added.value)) > 0)
                recovery.fail("RECOVERY_ABOVE_LIMIT: recovery is above " + method.recoveryHigh().toPlainString() + " percent");
        } else computedReasons.put("recovery_pct", "spike inputs missing or invalid");
        if (show(computed, computedReasons, "recovery_pct", rec)) recovery.unknown("NOT_REPRESENTABLE: recovery_pct cannot be shown as a number");

        // Hold time: same-run simulation seconds from sealing to this analysis
        Check hold = new Check();
        BigDecimal holdTime = null;
        String holdReason = null;
        if (sealedSimS == null || !Double.isFinite(sealedSimS)) holdReason = "sealing simulation time unknown";
        else if (analysisSimS == null || !Double.isFinite(analysisSimS)) holdReason = "analysis simulation time unknown";
        else {
            BigDecimal elapsed = BigDecimal.valueOf(analysisSimS).subtract(BigDecimal.valueOf(sealedSimS));
            if (elapsed.signum() < 0) holdReason = "analysis simulation time is before sealing time";
            else holdTime = elapsed;
        }
        if (holdTime == null) hold.unknown("HOLD_TIME_UNKNOWN: " + holdReason);
        else if (holdTime.compareTo(method.holdS()) > 0)
            hold.fail("HOLD_TIME_EXCEEDED: more than " + method.holdS().toPlainString() + " simulation seconds between sealing and analysis");

        // Reporting limit: below LOQ when 100*net < LOQ*mass*1000 (percent w/w). Never replaces the measured net.
        String reporting = null, reportingReason = null;
        if (net == null) reportingReason = "net_ug unavailable";
        else if (massIn.value == null) reportingReason = "mass_mg missing or invalid";
        else if (massIn.value.signum() == 0) reportingReason = "mass_mg is zero";
        else reporting = net.multiply(HUNDRED).compareTo(method.loqPercent().multiply(massIn.value).multiply(THOUSAND)) < 0
                ? "BELOW_LOQ" : "REPORTED";

        ObjectNode checks = CasesRecords.object();
        List<Check> all = List.of(massCheck, blank, netCheck, dup, recovery, hold);
        String[] names = {"mass", "blank", "net", "duplicate", "recovery", "hold"};
        ArrayNode reasons = JsonNodeFactory.instance.arrayNode();
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        boolean pending = false, failed = false;
        for (int i = 0; i < names.length; i++) {
            Check c = all.get(i);
            checks.set(names[i], checkNode(c));
            distinct.addAll(c.reasons);
            pending |= c.unknown;
            failed |= "FAIL".equals(c.status);
        }
        distinct.forEach(reasons::add);

        ObjectNode result = CasesRecords.object();
        result.set("raw", raw);
        result.set("computed", computed);
        result.set("computed_reasons", computedReasons);
        result.put("qc_status", pending ? "PENDING" : failed ? "FAIL" : "PASS");
        result.set("qc_checks", checks);
        result.set("qc_reasons", reasons);
        result.put("reporting", reporting);
        result.put("reporting_reason", reportingReason);
        if (holdTime == null) result.putNull("hold_time_s"); else result.put("hold_time_s", holdTime.doubleValue());
        result.put("hold_time_reason", holdReason);
        return result;
    }

    /**
     * Shows a computed number. A value a double cannot show (overflow, or nonzero underflowing to zero) stays null
     * with reason NOT_REPRESENTABLE and the caller must not let its criterion pass. Returns true in that case.
     */
    private static boolean show(ObjectNode computed, ObjectNode reasons, String name, BigDecimal value) {
        if (value == null) { computed.putNull(name); return false; }
        Double shown = shown(value);
        if (shown == null) {
            computed.putNull(name);
            reasons.put(name, "NOT_REPRESENTABLE");
            return true;
        }
        computed.put(name, shown);
        reasons.putNull(name);
        return false;
    }

    private static void putRaw(ObjectNode raw, String field, Input in) {
        if (in.raw == null) raw.putNull(field); else raw.set(field, in.raw.deepCopy());
    }

    private static ObjectNode checkNode(Check check) {
        ObjectNode node = CasesRecords.object().put("status", check.status);
        ArrayNode reasons = node.putArray("reasons");
        check.reasons.forEach(reasons::add);
        return node;
    }
}
