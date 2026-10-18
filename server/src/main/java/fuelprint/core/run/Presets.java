package fuelprint.core.run;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The scenario presets of AGREEMENT section 10, each with a fixed seed (owned by the core) and its replay file. */
public final class Presets {

    /** One preset. */
    public record Preset(String id, long seed, String replayName, String description) {
    }

    public static final List<Preset> ALL = List.of(
            new Preset("SC1-NIGHT-PLUME", 1001L, "SC1",
                    "S02 emits at night with wind from the west; the alarm at N2 opens an investigation."),
            new Preset("SC2-BLOCKED-INLET", 1002L, "SC2",
                    "Same start; the drone's SO2 tube blocks during capture, so SO2 is rejected and CO2 kept."),
            new Preset("SC3-ABOVE-THE-BOXES", 1003L, "SC3",
                    "S03's hot plume passes above every box: no alarm; a scheduled survey ends inconclusive."));

    private static final Map<String, Preset> BY_ID = Map.of(
            ALL.get(0).id(), ALL.get(0), ALL.get(1).id(), ALL.get(1), ALL.get(2).id(), ALL.get(2));

    /** Run modes (AGREEMENT section 5 {@code create_run}). */
    public static final List<String> MODES = List.of("BLIND", "TEACHING");

    private Presets() {
    }

    public static Optional<Preset> find(String presetId) {
        return Optional.ofNullable(presetId == null ? null : BY_ID.get(presetId));
    }

    /** The preset whose replay file is {@code SC1}, {@code SC2} or {@code SC3}. */
    public static Optional<Preset> byReplayName(String name) {
        return ALL.stream().filter(p -> p.replayName().equalsIgnoreCase(name)).findFirst();
    }
}
