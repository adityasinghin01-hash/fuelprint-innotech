package fuelprint.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The capture rules' pre-check of one channel (AGREEMENT section 6, {@code attempt.channels[]}).
 * A person still reviews it with {@code review_capture}.
 *
 * @param channel      like {@code SO2_ppb}
 * @param validSeconds usable collection seconds, or null if unknown
 * @param volumeL      actual collected volume in litres, or null if unknown
 * @param status       VALID, LIMITED or REJECTED
 * @param reasons      why, empty when VALID
 */
public record ChannelVerdict(String channel, Double validSeconds, @JsonProperty("volume_L") Double volumeL,
                             ChannelStatus status, List<String> reasons) {

    public ChannelVerdict {
        reasons = List.copyOf(reasons);
    }
}
