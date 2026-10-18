package fuelprint.science.world;

/**
 * Published (Spring event) when the live envelope is used up: 900 observed simulation seconds or 160,000 puffs.
 * The world has stopped advancing and refuses to go on; a new identified run is needed.
 */
public record WorldCapacityReached(String runId, String reason, double simTimeS, int puffCount) {
}
