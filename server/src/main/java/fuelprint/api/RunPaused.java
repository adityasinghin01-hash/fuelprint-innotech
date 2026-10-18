package fuelprint.api;

/** Spring event after a run was paused. */
public record RunPaused(String runId) {
}
