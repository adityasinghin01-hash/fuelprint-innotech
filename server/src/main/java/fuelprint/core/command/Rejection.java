package fuelprint.core.command;

import java.util.regex.Pattern;

/**
 * A handler's refusal: nothing is saved except the REJECTED receipt.
 *
 * @param reasonCode upper snake case code, like {@code NOT_FEASIBLE} (see {@link ReasonCode})
 * @param reasonText plain words for the screen
 */
public record Rejection(String reasonCode, String reasonText) implements HandlerOutcome {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public Rejection {
        if (reasonCode == null || !CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("Reason code must be upper snake case: " + reasonCode);
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("Reason text is required");
        }
    }

    public static Rejection invalid(String text) {
        return new Rejection(ReasonCode.INVALID, text);
    }

    public static Rejection notAllowed(String text) {
        return new Rejection(ReasonCode.NOT_ALLOWED, text);
    }

    public static Rejection staleRevision(String text) {
        return new Rejection(ReasonCode.STALE_REVISION, text);
    }
}
