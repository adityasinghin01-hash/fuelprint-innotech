package fuelprint.api;

import java.util.List;

/**
 * One channel of a capture attempt to judge, for {@link CaptureRules#judge}.
 *
 * @param attemptId the attempt UUID
 * @param methodId  like {@code TEACH-NODE-ONLINE-2}
 * @param channel   like {@code SO2_ppb}
 * @param stages    the attempt's stages so far
 * @param readings  the vehicle inlet readings of this channel and its flow, oldest first
 */
public record CaptureInput(String attemptId, String methodId, String channel, List<Stage> stages,
                           List<Reading> readings) {

    public CaptureInput {
        stages = List.copyOf(stages);
        readings = List.copyOf(readings);
    }
}
