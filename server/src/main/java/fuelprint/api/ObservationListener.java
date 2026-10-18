package fuelprint.api;

/** Called by the core world loop with every tick's readings (CORE-API section 3, step 4); field feeds capture stages here. */
public interface ObservationListener {

    /** The readings observed at {@code simTimeS}. Never block or save records here; use {@code AutomationBus}. */
    void onObservation(double simTimeS, ReadingBatch batch);
}
