package fuelprint.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FP-JCS-RESTRICTED-1. Expected bytes are written literally from RFC 8785 (sections 3.2.2.2, 3.2.3, Appendix B);
 * none are derived with the implementation under test.
 */
class JcsManifestTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BS = "\\";
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static JsonNode parse(String s) { try { return JSON.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); } }
    static String text(JsonNode n) { return new String(JcsManifest.bytes(n), StandardCharsets.UTF_8); }
    static String code(Runnable r) {
        try { r.run(); } catch (JcsManifest.Unsupported u) { return u.code; }
        throw new AssertionError("not refused");
    }

    @Test void rfc8785Section3_2_2_2StringAndLiteralsSampleWithinTheDomain() {
        // RFC 8785 section 3.2.4 sample input, without its "numbers" member (non-integers are outside this profile).
        String input = "{\"string\": \"" + BS + "u20ac$" + BS + "u000F" + BS + "u000aA'" + BS + "u0042" + BS + "u0022" + BS + "u005c" + BS + BS
                + BS + "\"" + BS + "/\", \"literals\": [null, true, false]}";
        String expected = "{\"literals\":[null,true,false],\"string\":\"\u20ac$" + BS + "u000f" + BS + "nA'B" + BS + "\"" + BS + BS + BS + BS + BS + "\"/\"}";
        assertThat(text(parse(input))).isEqualTo(expected);
        // UTF-8 hex of the euro sign is e2 82 ac; '/' is not escaped.
        assertThat(HexFormat.of().formatHex(JcsManifest.bytes(TextNode.valueOf("\u20ac/")))).isEqualTo("22e282ac2f22");
    }

    @Test void rfc8785Section3_2_3PropertySortingByUtf16CodeUnits() {
        String input = "{\"\u20ac\": \"Euro Sign\", \"" + BS + "r\": \"Carriage Return\", \"\ufb33\": \"Hebrew Letter Dalet With Dagesh\", \"1\": \"One\", "
                + "\"\ud83d\ude00\": \"Emoji: Grinning Face\", \"\u0080\": \"Control\", \"\u00f6\": \"Latin Small Letter O With Diaeresis\"}";
        String expected = "{\"" + BS + "r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\",\"\u00f6\":\"Latin Small Letter O With Diaeresis\","
                + "\"\u20ac\":\"Euro Sign\",\"\ud83d\ude00\":\"Emoji: Grinning Face\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}";
        assertThat(text(parse(input))).isEqualTo(expected);
    }

    @Test void controlCharactersUseLowerCaseHexAndOthersAreLiteral() {
        assertThat(text(TextNode.valueOf("\u0001\u001f\u007f\u2028\b\f\n\r\t")))
                .isEqualTo("\"" + BS + "u0001" + BS + "u001f\u007f\u2028" + BS + "b" + BS + "f" + BS + "n" + BS + "r" + BS + "t\"");
    }

    @ParameterizedTest @CsvSource({"0,0", "-0,0", "1,1", "-1,-1", "9007199254740991,9007199254740991", "-9007199254740991,-9007199254740991"})
    void appendixBIntegerSamplesInsideTheSafeRange(String in, String out) {
        assertThat(text(parse(in))).isEqualTo(out);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9007199254740992", "-9007199254740992", "1E30", "4.50", "2e-3", "333333333.33333329", "0.000000000000000000000000001",
            "1.0", "-0.0", "5e-324", "1.7976931348623157e308", "295147905179352830000"})
    void appendixBNumbersOutsideTheProfileAreRefusedNeverRounded(String number) {
        assertThat(code(() -> JcsManifest.bytes(parse("{\"n\":" + number + "}")))).isEqualTo("UNSUPPORTED_NUMBER");
        assertThat(code(() -> JcsManifest.bytes(DoubleNode.valueOf(0.5)))).isEqualTo("UNSUPPORTED_NUMBER");
        assertThat(code(() -> JcsManifest.bytes(DecimalNode.valueOf(new java.math.BigDecimal("1e1000"))))).isEqualTo("UNSUPPORTED_NUMBER");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"n\":" + number + "}")))).isEqualTo("UNSUPPORTED_NUMBER");
    }

    @Test void loneSurrogatesAndInvalidUtf8AreRefused() {
        assertThat(code(() -> JcsManifest.bytes(TextNode.valueOf("a\ud800")))).isEqualTo("INVALID_UNICODE");
        assertThat(code(() -> JcsManifest.bytes(TextNode.valueOf("\udc00")))).isEqualTo("INVALID_UNICODE");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("\"" + BS + "ud800\"")))).isEqualTo("INVALID_UNICODE");
        assertThat(code(() -> JcsManifest.parseCanonical(new byte[]{'"', (byte) 0xc3, '"'}))).isEqualTo("INVALID_UNICODE");
        assertThat(code(() -> JcsManifest.parseCanonical(new byte[]{'"', (byte) 0xed, (byte) 0xa0, (byte) 0x80, '"'}))).isEqualTo("INVALID_UNICODE");
    }

    @Test void parseRefusesDuplicatesTrailingContentNonCanonicalBytesDepthAndSize() {
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"a\":1,\"a\":1}")))).isEqualTo("DUPLICATE_PROPERTY");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"a\":1}{}")))).isEqualTo("TRAILING_CONTENT");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"a\":1} x")))).isIn("TRAILING_CONTENT", "MALFORMED");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"b\":1,\"a\":2}")))).isEqualTo("NOT_CANONICAL");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"a\": 1}")))).isEqualTo("NOT_CANONICAL");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"a\":1}\n")))).isEqualTo("NOT_CANONICAL");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("-0")))).isEqualTo("NOT_CANONICAL");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("\"" + BS + "u0041\"")))).isEqualTo("NOT_CANONICAL");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("{\"a\":")))).isEqualTo("MALFORMED");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("")))).isEqualTo("MALFORMED");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("[".repeat(66) + "]".repeat(66))))).isEqualTo("TOO_DEEP");
        assertThat(code(() -> JcsManifest.parseCanonical(utf8("\"" + "a".repeat(20) + "\""), 10))).isEqualTo("TOO_LARGE");
        assertThat(JcsManifest.parseCanonical(utf8("[".repeat(64) + "]".repeat(64))).isArray()).isTrue();
    }

    @Test void canonicalBytesRoundTripExactly() {
        byte[] b = utf8("{\"a\":[1,-2,null,true,false,\"x\"],\"b\":{\"c\":\"\u00e9\"}}");
        assertThat(JcsManifest.bytes(JcsManifest.parseCanonical(b))).isEqualTo(b);
    }

    @Test void legacyCanonicalJsonIsUnchangedAndDifferentForDecimals() {
        // The legacy member form keeps exact decimals that this profile refuses.
        JsonNode legacy = JSON.createArrayNode().add(new java.math.BigDecimal("1e1000")).add(new java.math.BigDecimal("2.50"));
        assertThat(CanonicalJson.text(legacy)).isEqualTo("[1E+1000,2.5]");
        assertThatThrownBy(() -> JcsManifest.bytes(legacy)).isInstanceOf(JcsManifest.Unsupported.class);
    }
}
