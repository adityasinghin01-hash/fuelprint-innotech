package fuelprint.api;

/** Called by the core world loop after every {@code WorldClock.step} (CORE-API section 3, step 2); field moves vehicles here. */
public interface WorldStepListener {

    /** One world step of {@code dtS} simulation seconds just ended at {@code simTimeS}. Never block or save records here. */
    void onStep(double simTimeS, double dtS);
}
