package fuelprint.alerts;

import java.util.regex.Pattern;

/** Phone number rules. Full numbers are only ever handled here, in the contact table and in the gateway. */
final class PhoneNumbers {

    private static final Pattern INDIAN_MOBILE = Pattern.compile("\\+91[6-9][0-9]{9}");

    private PhoneNumbers() {
    }

    /**
     * The E.164 form of an Indian mobile number: {@code +91} then 10 digits starting 6, 7, 8 or 9.
     * Spaces and hyphens typed by the visitor are removed first.
     *
     * @return the number like {@code +919812345678}, or null if it is not valid
     */
    static String normalise(String input) {
        if (input == null) {
            return null;
        }
        String compact = input.trim().replace(" ", "").replace("-", "");
        return INDIAN_MOBILE.matcher(compact).matches() ? compact : null;
    }

    /** {@code +919812345678} becomes {@code +91 98xxxxxx78}. Anything else becomes {@code (hidden)}. */
    static String mask(String e164) {
        if (e164 == null || !INDIAN_MOBILE.matcher(e164).matches()) {
            return "(hidden)";
        }
        String digits = e164.substring(3);
        return "+91 " + digits.substring(0, 2) + "xxxxxx" + digits.substring(8);
    }
}
