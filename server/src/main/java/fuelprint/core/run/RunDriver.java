package fuelprint.core.run;

import java.util.Optional;

import fuelprint.api.RunContext;

/**
 * What moves a run forward: the world loop in live mode, the replay engine in fake mode. The run commands and
 * {@link RunLifecycle} call it; only one driver is active (chosen by {@code fp.mode}).
 */
public interface RunDriver {

    /** Empty if a run of {@code presetId} can start now, otherwise plain words why not. */
    Optional<String> readyFor(String presetId);

    /** Loads {@code run} and starts moving it from {@code simTimeS}. */
    void start(RunContext run, double simTimeS);

    /** Holds the loaded run still. */
    void pause(String runId);

    /** Moves the loaded run again. */
    void resume(String runId);

    /** Unloads {@code runId} if it is loaded. */
    void stop(String runId);

    /** True if {@code runId} is the loaded run. */
    boolean isLoaded(String runId);

    /** Simulation seconds of the loaded run, or empty if {@code runId} is not loaded. */
    Optional<Double> simTimeS(String runId);
}
