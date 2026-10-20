package fuelprint.api;

import java.text.Normalizer;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

/** Bounded guard for common phone formats and explicit contact keys; ordinary numeric measurements stay numeric. */
public final class ContactText {
    private static final Pattern OPAQUE = Pattern.compile(
            "(?:[0-9a-fA-F]{32,128}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");
    private static final Pattern INTERNATIONAL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\+[1-9](?:[ \\t().-]{0,3}[0-9]){7,14}(?![\\p{L}\\p{N}])");
    private static final Pattern INDIAN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:91[ \\t().-]{0,3})?[6-9](?:[ \\t().-]{0,3}[0-9]){9}(?![\\p{L}\\p{N}])");

    private static final Set<String> CONTACT_KEYS = Set.of("phone", "phonenumber", "phoneno", "mobile", "mobilenumber", "mobileno",
            "telephone", "telephonenumber", "telephoneno", "contactnumber", "contactno", "msisdn");

    private ContactText() {}

    /** Exact known contact-field names, including common separator/case and compatibility-character variants. */
    public static boolean isContactFieldName(String name) {
        if (name == null) return false;
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_-]", "");
        return CONTACT_KEYS.contains(normalized);
    }

    public static boolean contains(String text) {
        if (text == null) return false;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (OPAQUE.matcher(normalized).matches()) return false;
        return INTERNATIONAL.matcher(normalized).find() || INDIAN.matcher(normalized).find();
    }

    /** Numeric values are private only under explicit contact keys; ordinary measurements retain their meaning. */
    public static boolean contains(JsonNode value) {
        if (value == null) return false;
        if (value.isTextual()) return contains(value.textValue());
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (isContactFieldName(field.getKey()) || contains(field.getKey()) || contains(field.getValue())) return true;
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) if (contains(child)) return true;
        }
        return false;
    }
}
