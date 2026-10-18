package fuelprint.science.sensor;

/**
 * Tells the science engine that a capture's collection stage has started at a vehicle inlet. Scenario SC2 uses
 * it to fail the SO2 branch flow at collection second 10. (The api package has no such hook yet; see the
 * science report.)
 */
public interface CollectionHook {

    void collectionStarted(String vehicleId, double simTimeS);
}
