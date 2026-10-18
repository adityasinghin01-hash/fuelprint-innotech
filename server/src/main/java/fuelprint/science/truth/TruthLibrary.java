package fuelprint.science.truth;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hidden truth of the three department presets (AGREEMENT section 10), with fixed seeds.
 * The emission strengths are the tuned values recorded in {@code science/TUNING.md}.
 */
public final class TruthLibrary {

    /** Tuned SO2 emission in g/s of the plume source (nominal is 0.5 g/s; see TUNING.md). */
    public static final double SO2_G_S = 20.0;
    /** CO2 emission in g/s (nominal 500 g/s, never a fuel signature). */
    public static final double CO2_G_S = 500.0;

    /** Fixed seeds so a preset always runs the same way. They are never used for training nights. */
    private static final Map<String, Long> SEEDS = Map.of(
            "SC1-NIGHT-PLUME", 1_000_001L,
            "SC2-BLOCKED-INLET", 1_000_002L,
            "SC3-ABOVE-THE-BOXES", 1_000_003L);

    private TruthLibrary() {
    }

    public static boolean knows(String presetId) {
        return SEEDS.containsKey(presetId);
    }

    public static long seed(String presetId) {
        Long s = SEEDS.get(presetId);
        if (s == null) {
            throw new IllegalArgumentException("Unknown preset " + presetId);
        }
        return s;
    }

    /** The emission plan of a preset, or empty for an unknown preset. */
    public static Optional<EmissionPlan> plan(String presetId) {
        return switch (presetId) {
            case "SC1-NIGHT-PLUME", "SC2-BLOCKED-INLET" ->
                    Optional.of(new EmissionPlan(List.of(new EmissionPlan.Change(0, "S02", SO2_G_S, CO2_G_S))));
            case "SC3-ABOVE-THE-BOXES" ->
                    Optional.of(new EmissionPlan(List.of(new EmissionPlan.Change(0, "S03", SO2_G_S, CO2_G_S))));
            default -> Optional.empty();
        };
    }
}
