package fuelprint.core.privacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Last line of defence for AGREEMENT ground rule 10: masks anything that looks like an Indian mobile number
 * ({@code +91} followed by 10 digits, optionally after one space or dash) in text the core sends out: every
 * {@code /api} response and every live socket message. A hit is a bug somewhere else; it is logged without the
 * number. Masked form: {@code +91 98xxxxxx78}.
 */
public final class PhoneGuard {

    /** The pattern that must never leave the server. */
    public static final Pattern PHONE = Pattern.compile("\\+91[ -]?(\\d{2})\\d{6}(\\d{2})(?!\\d)");

    private static final Logger log = LoggerFactory.getLogger(PhoneGuard.class);

    private PhoneGuard() {
    }

    /** {@code text} with every phone number masked; the same string if there is none. */
    public static String mask(String text, String where) {
        if (text == null || text.indexOf("+91") < 0) {
            return text;
        }
        Matcher matcher = PHONE.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        do {
            matcher.appendReplacement(out, "+91 " + matcher.group(1) + "xxxxxx" + matcher.group(2));
            count++;
        } while (matcher.find());
        matcher.appendTail(out);
        log.error("PRIVACY: {} phone number(s) were about to leave the server in {}; they were masked", count, where);
        return out.toString();
    }
}
