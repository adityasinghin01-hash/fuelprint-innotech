package fuelprint.api;

/**
 * The run a {@link WorldSetup} loads (CORE-API section 4).
 *
 * @param runId    the run UUID
 * @param presetId like {@code SC1-NIGHT-PLUME}
 * @param mode     {@code BLIND} or {@code TEACHING}
 * @param seed     random seed of this run
 */
public record RunContext(String runId, String presetId, String mode, long seed) {
}
