package fuelprint.api;

/** Science publishes this when the world's live capacity is used up; core completes the run through automation. */
public record WorldCapacityReached(String runId, String reason, double simTimeS, int puffCount) {
}
