package fuelprint.api;

/**
 * One check the text had to pass (AGREEMENT section 11, AI-5).
 *
 * @param check  like {@code numbers_match_input}, {@code banned_words}, {@code length}
 * @param passed whether it passed
 */
public record WordingCheck(String check, boolean passed) {
}
