package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phone rules, SMS templates and SMS text checks (AGREEMENT section 13 and AI-5). */
class AlertTextsTest {

    private static final List<String> ALL_AREAS = List.of("AREA-W", "AREA-N", "AREA-E", "AREA-S");

    @Test
    void validIndianMobileNumbersAreNormalised() {
        assertThat(PhoneNumbers.normalise("+919812345678")).isEqualTo("+919812345678");
        assertThat(PhoneNumbers.normalise(" +91 98123-45678 ")).isEqualTo("+919812345678");
        assertThat(PhoneNumbers.normalise("+916000000000")).isEqualTo("+916000000000");
        assertThat(PhoneNumbers.normalise("+917000000000")).isEqualTo("+917000000000");
        assertThat(PhoneNumbers.normalise("+918000000000")).isEqualTo("+918000000000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "9812345678", "919812345678", "+91981234567", "+9198123456789",
            "+915812345678", "+910812345678", "+14155550123", "+91abcdefghij", "+91 9812 3456 78 9", "++919812345678"})
    void everythingElseIsRefused(String input) {
        assertThat(PhoneNumbers.normalise(input)).isNull();
    }

    @Test
    void nullIsRefused() {
        assertThat(PhoneNumbers.normalise(null)).isNull();
    }

    @Test
    void masksTheMiddleDigits() {
        assertThat(PhoneNumbers.mask("+919812345678")).isEqualTo("+91 98xxxxxx78");
        assertThat(PhoneNumbers.mask("junk")).isEqualTo("(hidden)");
        assertThat(PhoneNumbers.mask(null)).isEqualTo("(hidden)");
    }

    @Test
    void everyTemplateIsShortEnoughAndPassesEveryCheck() {
        for (String decision : List.of(AlertTexts.CANDIDATE_SUPPORTED_SIMULATED, AlertTexts.MORE_INVESTIGATION,
                AlertTexts.NO_SUPPORTED_SOURCE, AlertTexts.SAMPLE_INVALID)) {
            for (List<String> areas : List.of(List.of("AREA-E"), List.of("AREA-W", "AREA-E"), ALL_AREAS)) {
                String text = AlertTexts.boardUpdate(decision, areas);
                assertThat(text).as(decision + " " + areas).isNotNull().startsWith(AlertTexts.PREFIX);
                assertThat(text.length()).as(text).isLessThanOrEqualTo(160);
                assertThat(AlertTexts.failures(text, text)).as(text).isEmpty();
            }
        }
        for (List<String> areas : List.of(List.of("AREA-E"), List.of("AREA-W", "AREA-E"), ALL_AREAS)) {
            String text = AlertTexts.smokeWarning(areas);
            assertThat(text).startsWith(AlertTexts.PREFIX);
            assertThat(text.length()).as(text).isLessThanOrEqualTo(160);
            assertThat(AlertTexts.failures(text, text)).as(text).isEmpty();
        }
        assertThat(AlertTexts.failures(AlertTexts.test(), AlertTexts.test())).isEmpty();
    }

    @Test
    void theFourBoardTextsAreDifferentAndMatchTheirDecision() {
        List<String> areas = List.of("AREA-E");
        String acted = AlertTexts.boardUpdate(AlertTexts.CANDIDATE_SUPPORTED_SIMULATED, areas);
        String more = AlertTexts.boardUpdate(AlertTexts.MORE_INVESTIGATION, areas);
        String none = AlertTexts.boardUpdate(AlertTexts.NO_SUPPORTED_SOURCE, areas);
        String invalid = AlertTexts.boardUpdate(AlertTexts.SAMPLE_INVALID, areas);
        assertThat(List.of(acted, more, none, invalid)).doesNotHaveDuplicates();
        assertThat(acted).contains("acted on");
        assertThat(more).contains("still going");
        assertThat(none).contains("no source was confirmed").contains("Monitoring continues");
        assertThat(invalid).contains("could not be used").contains("investigation");
        assertThat(AlertTexts.boardUpdate("SOMETHING_ELSE", areas)).isNull();
        assertThat(AlertTexts.boardUpdate(null, areas)).isNull();
    }

    @Test
    void aLongAreaListFallsBackToAShorterTemplateInsteadOfGoingOver160() {
        String text = AlertTexts.boardUpdate(AlertTexts.SAMPLE_INVALID, ALL_AREAS);
        assertThat(text.length()).isLessThanOrEqualTo(160);
    }

    @Test
    void checksCatchEachProblem() {
        String ok = AlertTexts.PREFIX + " smoke may be heading toward East colony. Demo only.";
        assertThat(AlertTexts.failures(ok, ok)).isEmpty();
        assertThat(AlertTexts.failures(AlertTexts.PREFIX + " smoke from S02 may reach East colony.", "x"))
                .contains("no_factory_label");
        assertThat(AlertTexts.failures(AlertTexts.PREFIX + " source s06 found.", "x")).contains("no_factory_label");
        assertThat(AlertTexts.failures("Smoke may be heading toward East colony.", "x")).contains("prefix");
        assertThat(AlertTexts.failures(AlertTexts.PREFIX + "x".repeat(200), "x")).contains("length");
        assertThat(AlertTexts.failures(ok + "x".repeat(160 - ok.length()), "x")).doesNotContain("length");
        assertThat(AlertTexts.failures(ok + "x".repeat(161 - ok.length()), "x")).contains("length");
        for (String banned : AlertTexts.BANNED_WORDS) {
            assertThat(AlertTexts.failures(AlertTexts.PREFIX + " word " + banned + " here", "x"))
                    .as(banned).contains("banned_words");
            assertThat(AlertTexts.failures(AlertTexts.PREFIX + " word " + banned.toUpperCase() + " here", "x"))
                    .as(banned.toUpperCase()).contains("banned_words");
        }
        assertThat(AlertTexts.failures(AlertTexts.PREFIX + " about 5 km away", "no digits here")).contains("numbers_match_input");
        assertThat(AlertTexts.failures(AlertTexts.PREFIX + " about 5 km away", "5 km is in the input"))
                .doesNotContain("numbers_match_input");
        assertThat(AlertTexts.failures(AlertTexts.PREFIX + " caf\u00e9", "x")).contains("plain_characters");
        assertThat(AlertTexts.failures("", "x")).containsExactly("not_empty");
        assertThat(AlertTexts.failures(null, "x")).containsExactly("not_empty");
    }

    @Test
    void localDayIsIndianStandardTime() {
        Instant tenAmIst = Instant.parse("2026-10-22T04:30:00Z");
        assertThat(AlertTime.startOfLocalDay(tenAmIst)).isEqualTo(Instant.parse("2026-10-21T18:30:00Z"));
        assertThat(AlertTime.endOfLocalDay(tenAmIst)).isEqualTo(Instant.parse("2026-10-22T18:30:00Z"));
        assertThat(AlertTime.untilNextMidnight(tenAmIst).toHours()).isEqualTo(14);
        // 23:59 IST is still the same local day; 00:00 IST starts the next one.
        assertThat(AlertTime.endOfLocalDay(Instant.parse("2026-10-22T18:29:00Z"))).isEqualTo(Instant.parse("2026-10-22T18:30:00Z"));
        assertThat(AlertTime.endOfLocalDay(Instant.parse("2026-10-22T18:30:00Z"))).isEqualTo(Instant.parse("2026-10-23T18:30:00Z"));
        assertThat(AlertTime.utc(Instant.parse("2026-10-22T18:30:00Z"))).isEqualTo("2026-10-22T18:30:00.000Z");
    }
}
