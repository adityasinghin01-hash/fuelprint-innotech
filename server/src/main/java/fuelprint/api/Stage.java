package fuelprint.api;

/**
 * One stage of a capture attempt (AGREEMENT section 6, {@code attempt.stages[]}).
 *
 * @param stage  like {@code SETUP}, {@code BACKGROUND}, {@code PURGE}, {@code SETTLE}, {@code COLLECT}
 * @param startS start, simulation seconds
 * @param endS   end, or null while the stage is running
 */
public record Stage(String stage, double startS, Double endS) {
}
