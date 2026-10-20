package fuelprint.cases;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Compact, recursively key-sorted UTF-8 JSON used for evidence-pack members and manifests.
 *
 * <p>This is this project's own simple canonical form, <b>not RFC 8785</b>. The rules are:
 * <ul>
 * <li>Objects: keys sorted by {@link String#compareTo} (UTF-16 code unit order), no spaces.</li>
 * <li>Arrays: element order is kept.</li>
 * <li>Strings: only {@code "} {@code \}, {@code \b \f \n \r \t} and other control characters below U+0020
 *     (as lower-case four-digit hex escapes, for example the escape for U+001F) are escaped; everything else, including non-ASCII and {@code /},
 *     is written as raw UTF-8. A lone surrogate is refused (it has no UTF-8 form).</li>
 * <li>Numbers: integers as plain digits; other numbers as their exact decimal value without trailing zeros
 *     ({@code 50.0} and {@code 50} both write {@code 50}; {@code -0.0} writes {@code 0}).
 *     Exponents within +/-400 use plain notation; larger exponents use exact BigDecimal scientific notation
 *     to preserve legal raw evidence without allocating an enormous string. NaN and infinities are refused.</li>
 * <li>{@code null}, {@code true}, {@code false} as usual. Binary, POJO and missing nodes are refused.</li>
 * </ul>
 * The bytes written once are stored and hashed; they are never regenerated for verification.
 */
final class CanonicalJson {
    static final int MAX_DEPTH = 64;
    private static final int MAX_EXPONENT = 400;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalJson() {}

    /** A value the canonical form cannot represent. */
    static final class Unsupported extends IllegalArgumentException {
        Unsupported(String message) { super(message); }
    }

    static byte[] bytes(JsonNode node) {
        return text(node).getBytes(StandardCharsets.UTF_8);
    }

    static String text(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out, 0);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out, int depth) {
        if (node == null || node.isMissingNode()) throw new Unsupported("A missing value cannot be written.");
        if (depth > MAX_DEPTH) throw new Unsupported("JSON nesting is deeper than " + MAX_DEPTH + ".");
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) names.add(it.next());
            Collections.sort(names);
            out.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) out.append(',');
                first = false;
                string(name, out);
                out.append(':');
                write(node.get(name), out, depth + 1);
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
        } else if (node.isNumber()) {
            number(node, out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNull()) {
            out.append("null");
        } else {
            throw new Unsupported("Unsupported JSON value type " + node.getNodeType() + ".");
        }
    }

    private static void number(JsonNode node, StringBuilder out) {
        if (node.isIntegralNumber()) { out.append(node.bigIntegerValue().toString()); return; }
        BigDecimal value;
        if (node.isFloat()) {
            float f = node.floatValue();
            if (!Float.isFinite(f)) throw new Unsupported("NaN and infinity cannot be written.");
            value = new BigDecimal(Float.toString(f));
        } else if (node.isDouble()) {
            double d = node.doubleValue();
            if (!Double.isFinite(d)) throw new Unsupported("NaN and infinity cannot be written.");
            value = BigDecimal.valueOf(d);
        } else {
            value = node.decimalValue();
        }
        if (value.signum() == 0) { out.append('0'); return; }
        BigDecimal stripped = value.stripTrailingZeros();
        long exponent = (long) stripped.precision() - stripped.scale();
        out.append(Math.abs(exponent) > MAX_EXPONENT ? stripped.toString() : stripped.toPlainString());
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new Unsupported("A lone surrogate has no UTF-8 form.");
                out.append(c).append(value.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) throw new Unsupported("A lone surrogate has no UTF-8 form.");
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
