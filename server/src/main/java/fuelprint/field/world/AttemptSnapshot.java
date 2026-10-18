package fuelprint.field.world;

import java.util.List;
import java.util.Map;

import fuelprint.api.Reading;
import fuelprint.api.Stage;

/**
 * A copy of one capture run for the {@code update_attempt} handler to save.
 *
 * @param stages       the stages so far, the running one with {@code endS} null
 * @param collectEnded true once COLLECT has ended, so the channels can be judged
 * @param done         true once REVIEW has ended
 * @param readings     per mission channel: its inlet readings and its branch flow readings, oldest first
 * @param counts       per mission channel: the field's own count of consecutive valid seconds
 */
public record AttemptSnapshot(String attemptId, String missionId, String runId, String methodId,
                              List<String> channels, List<Stage> stages, boolean collectEnded, boolean done,
                              Map<String, List<Reading>> readings, Map<String, ChannelCount> counts) {

    public AttemptSnapshot {
        channels = List.copyOf(channels);
        stages = List.copyOf(stages);
        readings = Map.copyOf(readings);
        counts = Map.copyOf(counts);
    }

    /**
     * The field's own count for one channel (used when the capture rules are missing).
     *
     * @param validSeconds    longest run of consecutive valid COLLECT seconds (capped at the 30 needed)
     * @param volumeL         that run's volume at the nominal branch flow, or null if the branch is unknown
     * @param meanEnhancement mean of that run minus the mean of the valid BACKGROUND seconds, or null
     * @param enhancementNote why {@code meanEnhancement} is null, or null
     * @param reasons         quality flags seen on invalid COLLECT seconds
     */
    public record ChannelCount(int validSeconds, Double volumeL, Double meanEnhancement, String enhancementNote,
                               List<String> reasons) {

        public ChannelCount {
            reasons = List.copyOf(reasons);
        }
    }
}
