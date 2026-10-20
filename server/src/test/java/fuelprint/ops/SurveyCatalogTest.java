package fuelprint.ops;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import fuelprint.api.*;
import org.junit.jupiter.api.Test;

class SurveyCatalogTest {
    VehicleState vehicle(Double energy,Velocity velocity,String lease,VehiclePose pose) {
        return new VehicleState(UUID.randomUUID().toString(),"DRONE-1","DRONE",pose,velocity,new Enu(0,0,0),energy,null,lease,Pilot.NONE);
    }
    @Test void catalogueResolvesBothIndependentSurveyTargetsAndRejectsUnknownActions() {
        assertThat(SurveyCatalog.find("SURVEY-DRONE-0-100-35").orElseThrow().target()).isEqualTo(new Enu(0,100,35));
        assertThat(SurveyCatalog.find("SURVEY-DRONE-280-140-45").orElseThrow().target()).isEqualTo(new Enu(280,140,45));
        assertThat(SurveyCatalog.find("S02-secret-target")).isEmpty();
    }
    @Test void movingVehicleIsUnavailableEvenWhenItsStageIsUnknown() {
        var action=SurveyCatalog.find("SURVEY-DRONE-0-100-35").orElseThrow();
        assertThat(SurveyCatalog.available(vehicle(50.0,new Velocity(1,0,0),null,new VehiclePose(0,0,2,0)),action)).isFalse();
    }
    @Test void invalidEnergyLeaseAndGeometryCannotProduceSurveyAuthority() {
        var action=SurveyCatalog.find("SURVEY-DRONE-0-100-35").orElseThrow();
        for(VehicleState v:List.of(vehicle(null,new Velocity(0,0,0),null,new VehiclePose(0,0,2,0)),
                vehicle(Double.NaN,new Velocity(0,0,0),null,new VehiclePose(0,0,2,0)),
                vehicle(50.0,new Velocity(0,0,0),"visitor",new VehiclePose(0,0,2,0)),
                vehicle(50.0,new Velocity(0,0,0),null,new VehiclePose(Double.NaN,0,2,0))))
            assertThat(SurveyCatalog.available(v,action)).isFalse();
        assertThat(SurveyCatalog.available(vehicle(50.0,new Velocity(0,0,0),null,new VehiclePose(0,0,2,0)),action)).isTrue();
    }
}
