package fuelprint.core.stub;

import java.util.List;

import fuelprint.api.Enu;
import fuelprint.api.Pilot;
import fuelprint.api.VehiclePose;
import fuelprint.api.VehicleSource;
import fuelprint.api.VehicleState;
import fuelprint.api.Velocity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Stub field vehicles: one drone, DRONE-1, standing still at the depot (the map origin) with a full battery. */
@Component
@Profile("stub")
public class StubVehicles implements VehicleSource {

    /** The fixture drone id, so recordings and stubs agree. */
    public static final String DRONE_ID = "9ad11ce0-f3b3-54f6-a41a-871f695c246f";

    private volatile Velocity velocity = new Velocity(0, 0, 0);

    @Override
    public List<VehicleState> vehicles() {
        return List.of(new VehicleState(DRONE_ID, "DRONE-1", "DRONE", new VehiclePose(0, 0, 0, 0), velocity,
                new Enu(0, 0, 0.4), 64.0, null, null, Pilot.NONE));
    }

    /** Makes the stub drone report a velocity (it does not actually move). For tests of the stationary rule. */
    public void setVelocity(Velocity velocity) {
        this.velocity = velocity == null ? new Velocity(0, 0, 0) : velocity;
    }
}
