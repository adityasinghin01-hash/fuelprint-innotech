package fuelprint.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One explanation in an analysis (AGREEMENT section 6, {@code candidates[]}).
 *
 * @param explanationId like {@code S02}, {@code S01+S02}, {@code BACKGROUND}, {@code UNKNOWN}
 * @param sources       the factory labels in it (empty for UNKNOWN and BACKGROUND)
 * @param fit           normalised error, lower fits better; a fit number, never a probability; null for UNKNOWN
 * @param status        SURVIVES or REJECTED
 * @param reason        why, or null
 */
public record Candidate(String explanationId, List<String> sources, Double fit, CandidateStatus status,
                        String reason) {

    /** The explanation id of the row that is always present: an unregistered source. */
    public static final String UNKNOWN = "UNKNOWN";

    public Candidate {
        sources = List.copyOf(sources);
    }

    /** True for the UNKNOWN row. */
    @JsonIgnore
    public boolean isUnknown() {
        return UNKNOWN.equals(explanationId);
    }
}
