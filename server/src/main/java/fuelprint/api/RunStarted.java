package fuelprint.api;

/** Spring event after a run's start committed: the world loop is about to run it. */
public record RunStarted(String runId, String presetId, String mode) {
}
