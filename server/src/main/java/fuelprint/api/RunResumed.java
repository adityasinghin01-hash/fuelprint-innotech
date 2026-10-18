package fuelprint.api;

/** Spring event after a paused run was resumed. */
public record RunResumed(String runId) {
}
