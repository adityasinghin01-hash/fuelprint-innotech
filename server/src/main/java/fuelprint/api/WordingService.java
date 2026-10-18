package fuelprint.api;

/** AI (AI-5): plain words from the chat model, checked, with a template fallback. */
public interface WordingService {

    /** Never throws: on any failed check, no key, no internet or no answer in time it returns the template. */
    Wording write(WordingRequest request);
}
