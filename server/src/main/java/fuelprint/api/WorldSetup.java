package fuelprint.api;

/** Loads and unloads a scenario (science implements; core calls it for create, reset and end of a run). */
public interface WorldSetup {

    /** Loads the preset of {@code run}: sources, wind and hidden truth (kept in {@code science.truth}). */
    void startRun(RunContext run);

    /** Unloads run {@code runId}. */
    void stopRun(String runId);
}
