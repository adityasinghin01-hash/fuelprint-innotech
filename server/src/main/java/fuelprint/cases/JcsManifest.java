package fuelprint.cases;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Profile {@code FP-JCS-RESTRICTED-1}: RFC 8785 (JSON Canonicalization Scheme) output for a deliberately
 * restricted value domain only, used for evidence-pack manifests and signature envelopes of format
 * {@code fp.evidence/2}. This is NOT general RFC 8785 support.
 *
 * <p>Accepted values: objects, arrays, strings, {@code true}, {@code false}, {@code null} and integers in the
 * I-JSON safe range {@code -(2^53-1)..2^53-1}. Inside this domain the output equals RFC 8785: members sorted by
 * UTF-16 code units (section 3.2.3), strings with the minimal escaping of section 3.2.2.2, integers as minimal
 * decimal digits. Any other number (fraction, exponent, out of range), a lone surrogate, invalid UTF-8, a
 * duplicate property, trailing content, nesting deeper than 64 or oversized input is refused with an explicit
 * code; nothing is rounded or coerced. Legacy {@link CanonicalJson} is a different, unchanged form.
 */
final class JcsManifest {
    static final String PROFILE = "FP-JCS-RESTRICTED-1";
    static final int MAX_DEPTH = 64;
    static final int MAX_MANIFEST_BYTES = 1 << 20;
    static final BigInteger MAX_SAFE = BigInteger.valueOf(9007199254740991L);
    static final BigInteger MIN_SAFE = MAX_SAFE.negate();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private JcsManifest() {}

    /** A value or byte sequence outside the profile; {@code code} names the rule that refused it. */
    static final class Unsupported extends IllegalArgumentException {
        final String code;
        Unsupported(String code, String message) { super(message); this.code = code; }
    }

    static byte[] bytes(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        byte[] b = out.toString().getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_MANIFEST_BYTES) throw new Unsupported("TOO_LARGE", "The canonical form is larger than the manifest limit.");
        return b;
    }

    /** Parses bytes that must already be in this canonical form; refuses anything else. */
    static JsonNode parseCanonical(byte[] bytes) { return parseCanonical(bytes, MAX_MANIFEST_BYTES); }

    static JsonNode parseCanonical(byte[] bytes, int maxBytes) {
        if (bytes == null) throw new Unsupported("MISSING", "No bytes.");
        if (bytes.length > maxBytes) throw new Unsupported("TOO_LARGE", "The input is larger than " + maxBytes + " bytes.");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Unsupported("INVALID_UNICODE", "The input is not valid UTF-8.");
        }
        JsonNode node;
        try {
            node = STRICT.readTree(text);
        } catch (MismatchedInputException e) {
            throw new Unsupported(String.valueOf(e.getOriginalMessage()).contains("Trailing token") ? "TRAILING_CONTENT" : "MALFORMED", "The input is not one JSON value.");
        } catch (JsonParseException e) {
            String m = String.valueOf(e.getOriginalMessage());
            if (m.contains("Duplicate field")) throw new Unsupported("DUPLICATE_PROPERTY", "An object repeats a property name.");
            if (m.contains("nesting depth")) throw new Unsupported("TOO_DEEP", "JSON nesting is too deep.");
            throw new Unsupported("MALFORMED", "The input is not well-formed JSON.");
        } catch (Exception e) {
            throw new Unsupported("MALFORMED", "The input is not well-formed JSON.");
        }
        if (node == null || node.isMissingNode()) throw new Unsupported("MALFORMED", "The input is empty.");
        byte[] again = bytes(node);
        if (!Arrays.equals(again, bytes)) throw new Unsupported("NOT_CANONICAL", "The bytes are not in " + PROFILE + " canonical form.");
        return node;
    }

    private static void write(JsonNode node, StringBuilder out, int depth) {
        if (node == null || node.isMissingNode()) throw new Unsupported("MALFORMED", "A missing value cannot be written.");
        if (depth > MAX_DEPTH) throw new Unsupported("TOO_DEEP", "JSON nesting is deeper than " + MAX_DEPTH + ".");
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) names.add(it.next());
            Collections.sort(names); // String.compareTo = UTF-16 code unit order (RFC 8785 section 3.2.3)
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                string(names.get(i), out);
                out.append(':');
                write(node.get(names.get(i)), out, depth + 1);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                write(node.get(i), out, depth + 1);
            }
            out.append(']');
        } else if (node.isTextual()) {
            string(node.textValue(), out);
        } else if (node.isIntegralNumber()) {
            BigInteger v = node.bigIntegerValue();
            if (v.compareTo(MAX_SAFE) > 0 || v.compareTo(MIN_SAFE) < 0)
                throw new Unsupported("UNSUPPORTED_NUMBER", "Integers outside -(2^53-1)..2^53-1 are outside " + PROFILE + ".");
            out.append(v.toString());
        } else if (node.isNumber()) {
            throw new Unsupported("UNSUPPORTED_NUMBER", "Only safe integers are allowed in " + PROFILE + "; fractions and exponents are refused, never rounded.");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNull()) {
            out.append("null");
        } else {
            throw new Unsupported("MALFORMED", "Unsupported JSON value type " + node.getNodeType() + ".");
        }
    }

    /** RFC 8785 section 3.2.2.2 string serialization. */
    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new Unsupported("INVALID_UNICODE", "A lone surrogate has no UTF-8 form.");
                out.append(c).append(value.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) throw new Unsupported("INVALID_UNICODE", "A lone surrogate has no UTF-8 form.");
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 15]);
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
