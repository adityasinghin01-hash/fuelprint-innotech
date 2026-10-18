package fuelprint.api;

/**
 * A window the analysis did not use, and why.
 *
 * @param readingWindow the window UUID
 * @param reason        like {@code STALE}
 */
public record Exclusion(String readingWindow, String reason) {
}
