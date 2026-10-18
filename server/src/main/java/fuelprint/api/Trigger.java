package fuelprint.api;

import java.util.List;

/**
 * An alarm from the fixed rule (AGREEMENT section 6, {@code trigger}), returned by {@link Detector}.
 * The saved record adds the common fields.
 *
 * @param channel            like {@code SO2_ppb}
 * @param sensor             the sensor UUID that raised it
 * @param intervals          the time intervals above the rule's floor
 * @param backgroundWindowId the background window used, or null
 * @param rule               always {@link #TEACH_DETECT_1} in our builds
 */
public record Trigger(String channel, String sensor, List<Interval> intervals, String backgroundWindowId,
                      String rule) {

    /** The fixed teaching alarm rule. */
    public static final String TEACH_DETECT_1 = "TEACH-DETECT-1";

    public Trigger {
        intervals = List.copyOf(intervals);
    }
}
