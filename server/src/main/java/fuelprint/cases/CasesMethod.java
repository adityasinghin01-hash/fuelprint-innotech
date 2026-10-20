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
 * Reads the already packaged method configuration, including the declared QC limits.
 * Only declared limits are used; the profile's fixture mass (oracle_mass_mg) is never read.
 */
@Component @Profile("!stub")
final class CasesMethod {
    private final String id;
    private final String material;
    private final String analyte;
    private final double minimumMassMg;
    private final BigDecimal minimumMass, loqPercent, blankMaxUg, rpdMaxPercent, recoveryLow, recoveryHigh, holdS;
    CasesMethod(ObjectMapper mapper) throws IOException {
        JsonNode configured;
        try (var input = new ClassPathResource("reference/method-profiles.json").getInputStream()) {
            configured = mapper.readTree(input);
        }
        JsonNode profile = null;
        for (JsonNode candidate : configured)
            if ("TEACH-FUEL-S-1".equals(candidate.path("id").asText())) profile = candidate;
        if (profile == null || !CasesRecords.ORIGIN.equals(profile.path("origin").asText())
                || !profile.path("material").isTextual() || profile.path("material").asText().isBlank()
                || !profile.path("analyte").isTextual() || profile.path("analyte").asText().isBlank()
                || !profile.path("minimum_mass_mg").isNumber()
                || !Double.isFinite(profile.path("minimum_mass_mg").doubleValue())
                || profile.path("minimum_mass_mg").doubleValue() <= 0)
            throw new IllegalStateException("The declared teaching fuel method configuration is unavailable or invalid.");
        id = profile.path("id").asText();
        material = profile.path("material").asText();
        analyte = profile.path("analyte").asText();
        minimumMassMg = profile.path("minimum_mass_mg").doubleValue();
        minimumMass = positive(profile, "minimum_mass_mg");
        loqPercent = positive(profile, "loq_percent_w_w");
        blankMaxUg = positive(profile, "blank_max_ug");
        rpdMaxPercent = positive(profile, "duplicate_RPD_max_percent");
        holdS = positive(profile, "hold_s");
        JsonNode recovery = profile.path("recovery_percent");
        if (!recovery.isArray() || recovery.size() != 2 || !finitePositive(recovery.get(0)) || !finitePositive(recovery.get(1))
                || recovery.get(0).decimalValue().compareTo(recovery.get(1).decimalValue()) > 0)
            throw new IllegalStateException("The declared teaching fuel method recovery limits are unavailable or invalid.");
        recoveryLow = recovery.get(0).decimalValue();
        recoveryHigh = recovery.get(1).decimalValue();
    }
    private static boolean finitePositive(JsonNode n) {
        return n != null && n.isNumber() && Double.isFinite(n.doubleValue()) && n.decimalValue().signum() > 0;
    }
    private static BigDecimal positive(JsonNode profile, String field) {
        JsonNode n = profile.path(field);
        if (!finitePositive(n)) throw new IllegalStateException("The declared teaching fuel method limit " + field + " is unavailable or invalid.");
        return n.decimalValue();
    }
    String id() { return id; }
    String material() { return material; }
    BigDecimal minimumMass() { return minimumMass; }
    BigDecimal loqPercent() { return loqPercent; }
    BigDecimal blankMaxUg() { return blankMaxUg; }
    BigDecimal rpdMaxPercent() { return rpdMaxPercent; }
    BigDecimal recoveryLow() { return recoveryLow; }
    BigDecimal recoveryHigh() { return recoveryHigh; }
    BigDecimal holdS() { return holdS; }
    /** The exact configuration a result was judged with, saved beside the result. */
    ObjectNode limits() {
        ObjectNode limits = CasesRecords.object().put("method_id", id).put("material", material).put("analyte", analyte)
                .put("origin", CasesRecords.ORIGIN);
        limits.put("minimum_mass_mg", minimumMass).put("loq_percent_w_w", loqPercent).put("blank_max_ug", blankMaxUg)
                .put("duplicate_rpd_max_percent", rpdMaxPercent).put("hold_s", holdS);
        limits.putArray("recovery_percent").add(recoveryLow).add(recoveryHigh);
        return limits;
    }
    void validate(JsonNode payload) {
        CasesRecords.require(id.equals(CasesRecords.text(payload, "method_id")), "Choose the declared teaching fuel assay method.");
        CasesRecords.require(material.equals(CasesRecords.text(payload, "material")), "Material must match the declared fuel assay method.");
        JsonNode mass = payload.path("mass_mg");
        CasesRecords.require(mass.isNumber() && Double.isFinite(mass.doubleValue()) && mass.decimalValue().compareTo(minimumMass) >= 0,
                "mass_mg must be a known finite number of at least " + minimumMassMg + " mg.");
    }
}
