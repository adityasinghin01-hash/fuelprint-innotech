package fuelprint.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A time interval in simulation seconds. JSON is a two-number array {@code [start_s, end_s]}. */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({"start_s", "end_s"})
public record Interval(double startS, double endS) {
}
