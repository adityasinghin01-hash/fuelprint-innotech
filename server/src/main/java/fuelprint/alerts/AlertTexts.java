package fuelprint.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every fixed SMS text and every check on an SMS text lives here, in one place (AGREEMENT section 13 and
 * AI-5). A text is at most 160 characters, starts with {@value #PREFIX}, never names a factory (S01 to
 * S06) and never uses a banned word. Templates never contain a number, so any digit the chat model adds
 * is a number that was not in the input and fails the check.
 */
final class AlertTexts {

    static final String PREFIX = "FuelPrint DEMO (simulated):";
    static final int MAX_LENGTH = 160;

    static final String SMOKE_WARNING = "SMOKE_WARNING";
    static final String BOARD_UPDATE = "BOARD_UPDATE";
    static final String TEST = "TEST";

    static final String CANDIDATE_SUPPORTED_SIMULATED = "CANDIDATE_SUPPORTED_SIMULATED";
    static final String MORE_INVESTIGATION = "MORE_INVESTIGATION";
    static final String NO_SUPPORTED_SOURCE = "NO_SUPPORTED_SOURCE";
    static final String SAMPLE_INVALID = "SAMPLE_INVALID";

    /** Words that must never appear in an SMS (AI-5). Compared in lower case, as parts of words too. */
    static final List<String> BANNED_WORDS = List.of(
            "guilty", "illegal", "proved", "coal", "probability", "chance", "%");

    private static final Pattern FACTORY_LABEL = Pattern.compile("(?i)(?<![A-Za-z0-9])S0[1-6](?![A-Za-z0-9])");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private AlertTexts() {
    }

    // ---- templates ----

    static String smokeWarning(List<String> areaIds) {
        return fit(PREFIX + " smoke may be heading toward " + names(areaIds) + ". This is a demo, not a real alert.",
                PREFIX + " smoke may be heading toward your area. This is a demo, not a real alert.");
    }

    /** The board update text for {@code decision}, or null if {@code decision} is not one of the four. */
    static String boardUpdate(String decision, List<String> areaIds) {
        if (decision == null) {
            return null;
        }
        String areas = names(areaIds);
        return switch (decision) {
            case CANDIDATE_SUPPORTED_SIMULATED -> fit(
                    PREFIX + " the board acted on the simulated finding for " + areas + ". Demo only.",
                    PREFIX + " the board acted on the simulated finding. Demo only.");
            case MORE_INVESTIGATION -> fit(
                    PREFIX + " the board says the investigation for " + areas + " is still going. Demo only.",
                    PREFIX + " the board says the investigation is still going. Demo only.");
            case NO_SUPPORTED_SOURCE -> fit(
                    PREFIX + " no source was confirmed for " + areas + ". Monitoring continues. Demo only.",
                    PREFIX + " no source was confirmed. Monitoring continues. Demo only.");
            case SAMPLE_INVALID -> fit(
                    PREFIX + " the sample could not be used, so the investigation for " + areas
                            + " continues. Demo only.",
                    PREFIX + " the sample could not be used, so the investigation continues. Demo only.");
            default -> null;
        };
    }

    static String test() {
        return PREFIX + " this is a test message from the FuelPrint stall. Demo only.";
    }

    /** Area names for the text, like "East colony" or "West village and East colony". */
    static String names(List<String> areaIds) {
        List<String> names = areaIds.stream()
                .map(id -> AlertGeography.area(id).map(AlertGeography.Area::name).orElse("your area"))
                .collect(Collectors.toList());
        if (names.isEmpty()) {
            return "your area";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String fit(String full, String shorter) {
        return full.length() <= MAX_LENGTH ? full : shorter;
    }

    // ---- checks ----

    /**
     * The checks an SMS text must pass. Returns the names of the checks that failed; empty means the text
     * may be sent. {@code publicText} is the text the wording was based on; digits in the SMS must
     * also appear there.
     */
    static List<String> failures(String text, String publicText) {
        List<String> failed = new ArrayList<>();
        if (text == null || text.isBlank()) {
            failed.add("not_empty");
            return failed;
        }
        if (text.length() > MAX_LENGTH) {
            failed.add("length");
        }
        if (!text.startsWith(PREFIX)) {
            failed.add("prefix");
        }
        if (FACTORY_LABEL.matcher(text).find()) {
            failed.add("no_factory_label");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String banned : BANNED_WORDS) {
            if (lower.contains(banned)) {
                failed.add("banned_words");
                break;
            }
        }
        if (text.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c > 0x7e)) {
            failed.add("plain_characters");
        }
        if (!digitsComeFrom(text, publicText)) {
            failed.add("numbers_match_input");
        }
        return failed;
    }

    private static boolean digitsComeFrom(String text, String input) {
        Matcher matcher = DIGITS.matcher(text);
        while (matcher.find()) {
            if (input == null || !containsNumber(input, matcher.group())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNumber(String input, String number) {
        Matcher matcher = DIGITS.matcher(input);
        while (matcher.find()) {
            if (matcher.group().equals(number)) {
                return true;
            }
        }
        return false;
    }
}
