package fuelprint.api;

import java.util.List;

/**
 * Plain words for a record (AGREEMENT section 6, {@code wording}). The text never changes a number, a
 * status, an outcome or a decision.
 *
 * @param target         the record revision described
 * @param purpose        what the text is for
 * @param text           the text to show or send
 * @param source         LLM or TEMPLATE
 * @param model          the chat model used, or null for the template
 * @param promptVersion  like {@code FP-WORD-1}
 * @param checks         every check and whether it passed
 * @param fallbackReason why the template was used, or null
 */
public record Wording(Ref target, WordingPurpose purpose, String text, WordingSource source, String model,
                      String promptVersion, List<WordingCheck> checks, String fallbackReason) {

    public Wording {
        checks = List.copyOf(checks);
    }
}
