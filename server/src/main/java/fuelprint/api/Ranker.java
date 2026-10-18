package fuelprint.api;

/** Science (AI-1): smoke-spread reasoning, FP-COMPAT-3. */
public interface Ranker {

    /** The physics analysis; its learned part is empty and {@code learned_status} null. */
    Analysis analyze(AnalysisInput input);
}
