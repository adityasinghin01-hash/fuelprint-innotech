package fuelprint.cases;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure aerosol filter calculation for TEACH-AEROSOL-S-1 on human-entered synthetic teaching values.
 *
 * <p>Raw decimal tokens are kept as sent. Subtraction is exact and every QC decision uses exact arithmetic or exact
 * cross-multiplication of raw values. Non-terminating divisions use DECIMAL128 with HALF_EVEN; their plain decimal
 * strings ({@code computed_decimal}) preserve that declared calculation, not arbitrary mathematical exactness. The JSON
 * numbers are the binary64 projection; a projection that would overflow or turn a nonzero value into zero is null
 * with NOT_REPRESENTABLE and keeps its check PENDING, so the result cannot be USABLE. Unknown stays null with a reason.
 */
final class AerosolMath {
    static final String PRECISION = "DECIMAL128_HALF_EVEN";
    static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60), HUNDRED = BigDecimal.valueOf(100),
            TWO_HUNDRED = BigDecimal.valueOf(200), THOUSAND = BigDecimal.valueOf(1000);
    static final List<String> COLLECTION_FIELDS = List.of("start_sim_s", "end_sim_s", "valid_s", "flow_L_min");
    static final Set<String> COLLECTION_KEYS = Set.of("start_sim_s", "end_sim_s", "valid_s", "flow_L_min", "status");
    static final List<String> QC_FIELDS = List.of("gross_ug", "blank_ug", "dup_a_ug", "dup_b_ug", "spike_added_ug", "spike_recovered_ug");

    private AerosolMath() {}

    // ------------------------------------------------------------------ numbers

    record Input(JsonNode raw, BigDecimal value, boolean missing, String invalidReason) {}

    static Input read(JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) return new Input(null, null, true, null);
        if (!raw.isNumber()) return new Input(raw, null, false, "is not a number");
        if (raw.isFloatingPointNumber() && !Double.isFinite(raw.doubleValue())) return new Input(raw, null, false, "is not finite");
        BigDecimal v = raw.decimalValue();
        if (v.signum() < 0) return new Input(raw, null, false, "is negative");
        if (projection(v) == null) return new Input(raw, null, false, "is outside the supported number range");
        return new Input(raw, v, false, null);
    }

    /** Finite binary64 value, or null if it overflows or a nonzero value would become zero. */
    static Double projection(BigDecimal v) {
        double d = v.doubleValue();
        if (!Double.isFinite(d)) return null;
        if (d == 0 && v.signum() != 0) return null;
        return d;
    }

    static String decimal(BigDecimal v) {
        if (v.signum() == 0) return "0";
        BigDecimal s = v.stripTrailingZeros();
        long exponent = (long) s.precision() - s.scale();
        return Math.abs(exponent) > 400 ? s.toString() : s.toPlainString();
    }

    /** Writes the binary64 projection and the decimal string; returns true when the projection is NOT_REPRESENTABLE. */
    static boolean show(ObjectNode computed, ObjectNode decimals, ObjectNode reasons, String name, BigDecimal v, String unknownReason) {
        if (v == null) {
            computed.putNull(name); decimals.putNull(name); reasons.put(name, unknownReason);
            return false;
        }
        decimals.put(name, decimal(v));
        Double d = projection(v);
        if (d == null) { computed.putNull(name); reasons.put(name, "NOT_REPRESENTABLE"); return true; }
        computed.put(name, d); reasons.putNull(name);
        return false;
    }

    // ------------------------------------------------------------------ collection (command-time validation)

    /** A human-entered collection that is not even recordable; the command is refused with this reason. */
    static final class Refused extends RuntimeException { Refused(String m) { super(m); } }

    /**
     * Validates the collection inputs and computes the effective volume. Refuses incoherent or impossible inputs;
     * an insufficient or interrupted collection is recordable and fails QC later. {@code nowSim} is the current run
     * clock (null when unknown: the time check is then UNCHECKED with a reason).
     */
    static ObjectNode collection(JsonNode c, Double nowSim) {
        if (c == null || !c.isObject()) throw new Refused("collection must be an object of human-recorded synthetic teaching values.");
        c.fieldNames().forEachRemaining(k -> { if (!COLLECTION_KEYS.contains(k)) throw new Refused("collection has an unsupported field."); });
        String status = c.path("status").isTextual() ? c.path("status").textValue() : null;
        if (!Set.of("COMPLETE", "INTERRUPTED").contains(status == null ? "" : status)) throw new Refused("collection.status must be COMPLETE or INTERRUPTED.");
        Map<String, BigDecimal> v = new LinkedHashMap<>();
        for (String f : COLLECTION_FIELDS) {
            Input in = read(c.get(f));
            if (in.missing()) throw new Refused("collection." + f + " is required.");
            if (in.invalidReason() != null) throw new Refused("collection." + f + " " + in.invalidReason() + ".");
            v.put(f, in.value());
        }
        BigDecimal start = v.get("start_sim_s"), end = v.get("end_sim_s"), valid = v.get("valid_s"), flow = v.get("flow_L_min");
        if (end.compareTo(start) <= 0) throw new Refused("collection.end_sim_s must be after start_sim_s.");
        if (valid.signum() <= 0) throw new Refused("collection.valid_s must be greater than zero.");
        if (valid.compareTo(end.subtract(start)) > 0) throw new Refused("collection.valid_s cannot exceed the collection window.");
        if (flow.signum() <= 0) throw new Refused("collection.flow_L_min must be greater than zero.");
        String timeCheck = "UNCHECKED", timeReason = "CURRENT_RUN_CLOCK_UNAVAILABLE";
        if (nowSim != null && Double.isFinite(nowSim)) {
            if (end.compareTo(new BigDecimal(Double.toString(nowSim))) > 0) throw new Refused("collection.end_sim_s is later than the current run clock.");
            timeCheck = "CHECKED"; timeReason = null;
        }
        ObjectNode out = CasesRecords.object();
        ObjectNode raw = out.putObject("collection");
        for (String f : COLLECTION_FIELDS) raw.set(f, c.get(f).deepCopy());
        raw.put("status", status).put("time_check", timeCheck).put("time_check_reason", timeReason);
        ObjectNode computed = out.putObject("collection_computed");
        ObjectNode decimals = computed.putObject("computed_decimal");
        ObjectNode reasons = computed.putObject("computed_reasons");
        BigDecimal volume = flow.multiply(valid).divide(SIXTY, MC);
        show(computed, decimals, reasons, "volume_L", volume, null);
        show(computed, decimals, reasons, "volume_m3", volume.divide(THOUSAND, MC), null);
        computed.put("calculation_precision", PRECISION);
        out.putArray("collection_reasons");
        return out;
    }

    // ------------------------------------------------------------------ lab evaluation

    private static final class Check {
        String status = "PASS";
        boolean unknown;
        final List<String> reasons = new ArrayList<>();
        void fail(String r) { status = "FAIL"; reasons.add(r); }
        void unknown(String r) { unknown = true; if (!"FAIL".equals(status)) status = "PENDING"; reasons.add(r); }
        void input(String name, Input in) {
            if (in.missing()) unknown(name + " is missing");
            else if (in.invalidReason() != null) fail("INVALID_INPUT: " + name + " " + in.invalidReason());
        }
    }

    /**
     * @param qcInputs the lab person's numbers as sent (null if none)
     * @param media the saved media body (its collection and computed volume)
     * @param analysisSimS simulation second of this analysis in the same run, or null if unknown
     */
    static ObjectNode evaluate(JsonNode qcInputs, JsonNode media, Double analysisSimS, AerosolMethod method) {
        Map<String, Input> in = new LinkedHashMap<>();
        for (String f : QC_FIELDS) in.put(f, read(qcInputs == null ? null : qcInputs.get(f)));
        JsonNode col = media.path("collection");
        Input flow = read(col.get("flow_L_min")), valid = read(col.get("valid_s")), end = read(col.get("end_sim_s"));
        ObjectNode raw = CasesRecords.object();
        for (String f : QC_FIELDS) raw.set(f, in.get(f).raw() == null ? CasesRecords.object().nullNode() : in.get(f).raw().deepCopy());
        ObjectNode computed = CasesRecords.object(), decimals = CasesRecords.object(), reasons = CasesRecords.object();
        List<String> diagnostics = new ArrayList<>();

        Check flowCheck = new Check();
        flowCheck.input("collection.flow_L_min", flow);
        if (flow.value() != null && flow.value().compareTo(method.flowLMin()) != 0)
            flowCheck.fail("FLOW_NOT_METHOD_FLOW: flow is not the declared " + method.flowLMin().toPlainString() + " L/min");

        Check collection = new Check();
        collection.input("collection.valid_s", valid);
        if (valid.value() != null && valid.value().compareTo(method.validS()) < 0)
            collection.fail("INSUFFICIENT_COLLECTION: fewer than " + method.validS().toPlainString() + " valid seconds");
        if ("INTERRUPTED".equals(col.path("status").asText())) collection.fail("COLLECTION_INTERRUPTED: the collection was recorded as interrupted");

        BigDecimal volume = flow.value() != null && valid.value() != null ? flow.value().multiply(valid.value()).divide(SIXTY, MC) : null;
        Check blank = new Check();
        Input blankIn = in.get("blank_ug"), gross = in.get("gross_ug");
        blank.input("blank_ug", blankIn);
        if (blankIn.value() != null && blankIn.value().compareTo(method.blankMaxUg()) > 0)
            blank.fail("BLANK_ABOVE_LIMIT: blank is above " + method.blankMaxUg().toPlainString() + " ug");

        Check net = new Check();
        net.input("gross_ug", gross);
        BigDecimal netUg = gross.value() != null && blankIn.value() != null ? gross.value().subtract(blankIn.value()) : null;
        if (netUg != null && netUg.signum() < 0) diagnostics.add("NEGATIVE_CORRECTED_MASS: blank exceeds gross; kept as measured, not clamped");
        BigDecimal conc = null;
        String concReason = null;
        if (netUg == null) concReason = "net_ug unavailable";
        else if (volume == null) concReason = "collection volume unavailable";
        else if (volume.signum() == 0) concReason = "collection volume is zero";
        else conc = netUg.multiply(THOUSAND).divide(volume, MC);
        if (show(computed, decimals, reasons, "net_ug", netUg, "gross_ug or blank_ug is missing or invalid"))
            net.unknown("NOT_REPRESENTABLE: net_ug cannot be shown as a number");
        if (show(computed, decimals, reasons, "concentration_ug_m3", conc, concReason))
            net.unknown("NOT_REPRESENTABLE: concentration_ug_m3 cannot be shown as a number");
        if (show(computed, decimals, reasons, "volume_L", volume, "collection flow or valid seconds unavailable"))
            collection.unknown("NOT_REPRESENTABLE: volume_L cannot be shown as a number");

        Check dup = new Check();
        Input a = in.get("dup_a_ug"), b = in.get("dup_b_ug");
        dup.input("dup_a_ug", a); dup.input("dup_b_ug", b);
        BigDecimal rpd = null;
        String rpdReason = "duplicate inputs missing or invalid";
        if (a.value() != null && b.value() != null) {
            BigDecimal sum = a.value().add(b.value()), scaled = a.value().subtract(b.value()).abs().multiply(TWO_HUNDRED);
            if (sum.signum() == 0) { rpdReason = "duplicate mean is zero"; dup.unknown("RPD_UNDEFINED: duplicate mean is zero"); }
            else {
                rpd = scaled.divide(sum, MC);
                if (scaled.compareTo(method.rpdMaxPercent().multiply(sum)) > 0)
                    dup.fail("DUPLICATE_RPD_ABOVE_LIMIT: duplicate difference is above " + method.rpdMaxPercent().toPlainString() + " percent");
            }
        }
        if (show(computed, decimals, reasons, "rpd_pct", rpd, rpdReason)) dup.unknown("NOT_REPRESENTABLE: rpd_pct cannot be shown as a number");

        Check recovery = new Check();
        Input added = in.get("spike_added_ug"), got = in.get("spike_recovered_ug");
        recovery.input("spike_added_ug", added); recovery.input("spike_recovered_ug", got);
        BigDecimal rec = null;
        String recReason = "spike inputs missing or invalid";
        if (added.value() != null && added.value().signum() == 0) { recReason = "spike_added_ug is zero"; recovery.unknown("RECOVERY_UNDEFINED: spike_added_ug is zero"); }
        else if (added.value() != null && got.value() != null) {
            BigDecimal scaled = got.value().multiply(HUNDRED);
            rec = scaled.divide(added.value(), MC);
            if (scaled.compareTo(method.recoveryLow().multiply(added.value())) < 0)
                recovery.fail("RECOVERY_BELOW_LIMIT: recovery is below " + method.recoveryLow().toPlainString() + " percent");
            else if (scaled.compareTo(method.recoveryHigh().multiply(added.value())) > 0)
                recovery.fail("RECOVERY_ABOVE_LIMIT: recovery is above " + method.recoveryHigh().toPlainString() + " percent");
        }
        if (show(computed, decimals, reasons, "recovery_pct", rec, recReason)) recovery.unknown("NOT_REPRESENTABLE: recovery_pct cannot be shown as a number");

        Check hold = new Check();
        BigDecimal holdTime = null;
        String holdReason = null;
        if (end.value() == null) holdReason = "collection end time unknown";
        else if (analysisSimS == null || !Double.isFinite(analysisSimS)) holdReason = "analysis simulation time unknown";
        else {
            BigDecimal elapsed = new BigDecimal(Double.toString(analysisSimS)).subtract(end.value());
            if (elapsed.signum() < 0) holdReason = "analysis simulation time is before collection end";
            else holdTime = elapsed;
        }
        if (holdTime == null) hold.unknown("HOLD_TIME_UNKNOWN: " + holdReason);
        else if (holdTime.compareTo(method.holdS()) > 0)
            hold.fail("HOLD_TIME_EXCEEDED: more than " + method.holdS().toPlainString() + " simulation seconds between collection end and analysis");

        String reporting = netUg == null ? null : netUg.compareTo(method.loqUg()) < 0 ? "BELOW_LOQ" : "REPORTED";
        String reportingReason = netUg == null ? "net_ug unavailable" : null;

        ObjectNode checks = CasesRecords.object();
        String[] names = {"flow", "collection", "blank", "net", "duplicate", "recovery", "hold"};
        List<Check> all = List.of(flowCheck, collection, blank, net, dup, recovery, hold);
        Set<String> distinct = new LinkedHashSet<>();
        boolean pending = false, failed = false;
        for (int i = 0; i < names.length; i++) {
            Check c = all.get(i);
            ObjectNode n = checks.putObject(names[i]).put("status", c.status);
            ArrayNode r = n.putArray("reasons"); c.reasons.forEach(r::add);
            distinct.addAll(c.reasons);
            pending |= c.unknown; failed |= "FAIL".equals(c.status);
        }
        String qc = pending ? "PENDING" : failed ? "FAIL" : "PASS";
        String use = switch (qc) {
            case "PASS" -> "REPORTED".equals(reporting) ? "USABLE" : "LIMITED_BELOW_LOQ";
            case "FAIL" -> "NOT_USABLE";
            default -> "PENDING";
        };

        ObjectNode out = CasesRecords.object();
        out.set("raw", raw);
        out.set("computed", computed);
        out.set("computed_decimal", decimals);
        out.set("computed_reasons", reasons);
        out.put("calculation_precision", PRECISION);
        out.put("qc_status", qc);
        out.set("qc_checks", checks);
        ArrayNode qr = out.putArray("qc_reasons"); distinct.forEach(qr::add);
        ArrayNode d = out.putArray("diagnostics"); diagnostics.forEach(d::add);
        out.put("reporting", reporting).put("reporting_reason", reportingReason).put("result_use", use);
        if (holdTime == null) out.putNull("hold_time_s"); else out.put("hold_time_s", holdTime.doubleValue());
        out.put("hold_time_reason", holdReason);
        if (end.value() == null) out.putNull("collection_end_sim_s"); else out.set("collection_end_sim_s", col.get("end_sim_s").deepCopy());
        return out;
    }
}
