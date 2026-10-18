package fuelprint.api;

import java.util.List;

/** The vehicles in the world right now (field implements); the core uses them for inlets and the tick. */
public interface VehicleSource {

    /** Every vehicle with its current state. */
    List<VehicleState> vehicles();
}
