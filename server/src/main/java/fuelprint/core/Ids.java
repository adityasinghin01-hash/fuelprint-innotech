package fuelprint.core;

import java.util.UUID;

/** UUID checks for IDs (AGREEMENT section 2). */
public final class Ids {

    private Ids() {
    }

    /** True if {@code value} is a UUID in canonical 8-4-4-4-12 hex form. */
    public static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A new server-made UUID v4 string. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
