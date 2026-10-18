package fuelprint.api;

/**
 * The normal-air model's expected SO2 band for one box at one time (AI-6).
 *
 * @param expectedLow    low end of normal
 * @param expectedHigh   high end of normal
 * @param modelVersionId the model_version that produced it
 */
public record ExpectedBand(double expectedLow, double expectedHigh, String modelVersionId) {
}
