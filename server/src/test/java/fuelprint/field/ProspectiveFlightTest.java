package fuelprint.field;

import fuelprint.api.*;
import fuelprint.field.geo.Vec3;
import fuelprint.field.vehicle.Drone;
import fuelprint.field.vehicle.Kinematics;
import fuelprint.field.world.Autopilot;
import fuelprint.science.plan.ProspectiveMeasurement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ProspectiveFlightTest {
    @Test void forecastUsesTheSameAutopilotKinematicsAndInletOffsetsAsARealFlight() {
        var profile=TestProfile.PROFILE;
        VehicleState vehicle=new VehicleState("drone","DRONE-1","DRONE",new VehiclePose(0,0,2,0),
                new Velocity(0,0,0),new Enu(0,.4,1.6),64.0,"READY",null,Pilot.NONE);
        InletHistory history=(run,id,start,end) -> Optional.of(List.of(new InletSample(0,vehicle.inlet()),
                new InletSample(end,vehicle.inlet())));
        var source=new fuelprint.field.world.ProspectiveFlight(profile,history);
        var target=new Enu(0,100,35);
        ProspectiveMeasurement measurement=ProspectiveMeasurement.from(source.forecast(
                new PlanContext("run","analysis",300,new WindNow(270,3),List.of(vehicle)),vehicle,target,()->false).orElseThrow());
        var drone=new Drone("copy","copy",new Vec3(0,0,2),64);
        var pilot=new Autopilot(TestProfile.PLANNER,profile.maxSpeedMS(),profile.maxAccelMS2());
        var motion=new Kinematics(profile,TestProfile.OBSTACLES);
        for(int tick=1;tick<=1200;tick++) {
            motion.step(drone,pilot.steer("out",drone.position(),() -> List.of(new Vec3(0,100,35)),.05)
                    .orElseThrow(),.05,true);
            var predicted=measurement.track().at(300+tick*.05);
            assertThat(predicted.east()).isCloseTo(drone.inlet().e(),offset(1e-9));
            assertThat(predicted.north()).isCloseTo(drone.inlet().n(),offset(1e-9));
            assertThat(predicted.up()).isCloseTo(drone.inlet().u(),offset(1e-9));
        }
        assertThat(measurement.collectionEnd()-measurement.collectionStart()).isEqualTo(30);
        assertThat(measurement.backgroundEnd()-measurement.backgroundStart()).isEqualTo(30);
        assertThat(measurement.completeSeconds()).isGreaterThan(390);
        assertThat(measurement.energyWh()).isBetween(18.0,40.0);
        assertThat(vehicle.energyWh()).isEqualTo(64);
    }

    @Test void missingHistoryCannotBeReplacedByACleanOrSettledInstrument() {
        var source=new fuelprint.field.world.ProspectiveFlight(TestProfile.PROFILE,(r,v,s,e)->Optional.empty());
        var vehicle=new VehicleState("drone","DRONE-1","DRONE",new VehiclePose(0,0,2,0),new Velocity(0,0,0),
                new Enu(0,.4,1.6),64.0,"READY",null,Pilot.NONE);
        assertThat(source.forecast(new PlanContext("run","analysis",300,new WindNow(270,3),List.of(vehicle)),vehicle,new Enu(0,100,35),()->false)).isEmpty();
    }
    @Test void deadlineCanAbortAnActualForecastDuringTheFlightLoop() {
        var vehicle=new VehicleState("drone","DRONE-1","DRONE",new VehiclePose(0,0,2,0),new Velocity(0,0,0),
                new Enu(0,.4,1.6),64.0,"READY",null,Pilot.NONE);
        InletHistory history=(r,v,s,e)->Optional.of(List.of(new InletSample(0,vehicle.inlet()),new InletSample(e,vehicle.inlet())));
        var source=new fuelprint.field.world.ProspectiveFlight(TestProfile.PROFILE,history);
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(source.forecast(new PlanContext("run","analysis",300,new WindNow(270,3),List.of(vehicle)),
                vehicle,new Enu(0,100,35),()->checks.incrementAndGet()>10)).isEmpty();
        assertThat(checks.get()).isEqualTo(11);
        assertThat(vehicle.energyWh()).isEqualTo(64);
    }
}
