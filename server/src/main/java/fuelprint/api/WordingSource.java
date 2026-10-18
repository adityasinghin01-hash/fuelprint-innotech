package fuelprint.api;

/** Who wrote the text: the chat model, or the fixed template after a failed check or no answer. */
public enum WordingSource {
    LLM,
    TEMPLATE
}
