package fuelprint.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The wind right now. JSON {@code {"from_deg": 270, "speed_m_s": 3.0}} as in the tick.
 *
 * @param fromDeg where the wind comes FROM, degrees clockwise from north (270 = from the west)
 * @param speedMS wind speed in metres per second
 */
public record WindNow(double fromDeg, @JsonProperty("speed_m_s") double speedMS) {
}
