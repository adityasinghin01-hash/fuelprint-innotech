package fuelprint.cases;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The declared teaching aerosol filter method TEACH-AEROSOL-S-1, read exactly as packaged. No threshold is tuned and
 * {@code oracle_efficiency} is never read. The filter material label is the declared
 * {@code SYNTHETIC_UNCLASSIFIED_AEROSOL}; the size class stays UNCLASSIFIED (no PM2.5 qualification).
 */
@Component @Profile("!stub")
final class AerosolMethod {
    static final String ID = "TEACH-AEROSOL-S-1";
    static final String MATERIAL = "SYNTHETIC_UNCLASSIFIED_AEROSOL";
    private final String analyte, sizeClass;
    private final BigDecimal flowLMin, validS, loqUg, blankMaxUg, rpdMaxPercent, recoveryLow, recoveryHigh, holdS;

    AerosolMethod(ObjectMapper mapper) throws IOException {
        JsonNode configured;
        try (var input = new ClassPathResource("reference/method-profiles.json").getInputStream()) { configured = mapper.readTree(input); }
        JsonNode profile = null;
        for (JsonNode candidate : configured) if (ID.equals(candidate.path("id").asText())) profile = candidate;
        if (profile == null || !CasesRecords.ORIGIN.equals(profile.path("origin").asText()) || !profile.path("analyte").isTextual()
                || !"UNCLASSIFIED".equals(profile.path("size_class").asText()))
            throw new IllegalStateException("The declared teaching aerosol method configuration is unavailable or invalid.");
        analyte = profile.path("analyte").asText();
        sizeClass = profile.path("size_class").asText();
        flowLMin = positive(profile, "flow_L_min");
        validS = positive(profile, "valid_s");
        loqUg = positive(profile, "loq_ug");
        blankMaxUg = positive(profile, "blank_max_ug");
        rpdMaxPercent = positive(profile, "duplicate_RPD_max_percent");
        holdS = positive(profile, "hold_s");
        JsonNode r = profile.path("recovery_percent");
        if (!r.isArray() || r.size() != 2 || !finitePositive(r.get(0)) || !finitePositive(r.get(1))
                || r.get(0).decimalValue().compareTo(r.get(1).decimalValue()) > 0)
            throw new IllegalStateException("The declared teaching aerosol recovery limits are unavailable or invalid.");
        recoveryLow = r.get(0).decimalValue();
        recoveryHigh = r.get(1).decimalValue();
    }

    private static boolean finitePositive(JsonNode n) {
        return n != null && n.isNumber() && Double.isFinite(n.doubleValue()) && n.decimalValue().signum() > 0;
    }

    private static BigDecimal positive(JsonNode profile, String field) {
        JsonNode n = profile.path(field);
        if (!finitePositive(n)) throw new IllegalStateException("The declared teaching aerosol limit " + field + " is unavailable or invalid.");
        return n.decimalValue();
    }

    BigDecimal flowLMin() { return flowLMin; }
    BigDecimal validS() { return validS; }
    BigDecimal loqUg() { return loqUg; }
    BigDecimal blankMaxUg() { return blankMaxUg; }
    BigDecimal rpdMaxPercent() { return rpdMaxPercent; }
    BigDecimal recoveryLow() { return recoveryLow; }
    BigDecimal recoveryHigh() { return recoveryHigh; }
    BigDecimal holdS() { return holdS; }

    /** The exact profile values a result was judged with, saved beside it. */
    ObjectNode limits() {
        ObjectNode l = CasesRecords.object().put("method_id", ID).put("material", MATERIAL).put("analyte", analyte).put("size_class", sizeClass)
                .put("origin", CasesRecords.ORIGIN);
        l.put("flow_L_min", flowLMin).put("valid_s", validS).put("loq_ug", loqUg).put("blank_max_ug", blankMaxUg)
                .put("duplicate_rpd_max_percent", rpdMaxPercent).put("hold_s", holdS);
        l.putArray("recovery_percent").add(recoveryLow).add(recoveryHigh);
        return l;
    }
}
