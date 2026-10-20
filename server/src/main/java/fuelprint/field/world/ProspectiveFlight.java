package fuelprint.field.world;

import fuelprint.api.*;
import fuelprint.field.geo.Area;
import fuelprint.field.geo.Obstacles;
import fuelprint.field.geo.PathPlanner;
import fuelprint.field.geo.Vec3;
import fuelprint.field.profile.WorldProfile;
import fuelprint.field.vehicle.Drone;
import fuelprint.field.vehicle.Kinematics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Pure copy of a prospective autopilot flight. Never moves the live vehicle or writes records. */
@Component
@Profile("!stub")
public class ProspectiveFlight implements ProspectiveInlets {
    private final WorldProfile profile;
    private final InletHistory history;

    public ProspectiveFlight(WorldProfile profile,InletHistory history) { this.profile=profile; this.history=history; }

    @Override public Optional<ProspectiveInlets.Forecast> forecast(PlanContext context, VehicleState vehicle,
                                                                 Enu target, java.util.function.BooleanSupplier expired) {
        String run=context.runId(); double now=context.simTimeS();
        if (expired.getAsBoolean()) return Optional.empty();
        if(!"DRONE".equals(vehicle.kind()) || vehicle.pose()==null || vehicle.velocity()==null
                || vehicle.energyWh()==null || vehicle.inlet()==null || !Double.isFinite(now) || now<0 || now>900)
            return Optional.empty();
        Optional<List<InletSample>> recorded=history.track(run,vehicle.vehicleId(),0,now);
        if(recorded.isEmpty() || recorded.get().isEmpty()) return Optional.empty();
        var obstacles=new Obstacles(profile.obstacles(),profile.obstacleMarginM());
        var area=new Area(profile.xyMin(),profile.xyMax(),profile.zMin(),profile.zMax());
        var path=new PathPlanner(obstacles,area);
        var pilot=new Autopilot(path,profile.maxSpeedMS(),profile.maxAccelMS2());
        var motion=new Kinematics(profile,obstacles);
        Drone drone=Drone.copyOf(vehicle);
        List<InletSample> samples=new ArrayList<>(recorded.get().stream()
                .filter(s->s.simTimeS()<=now).map(s->new InletSample(s.simTimeS(),s.inlet())).toList());
        Vec3 goal=new Vec3(target.east(),target.north(),target.up());
        double arrival=Double.NaN,bgStart=0,bgEnd=0,colStart=0,colEnd=0,reviewEnd=0;
        double dt=.05;
        var procedure=profile.procedure();
        for(int tick=1;tick<=18000;tick++) {
            if (expired.getAsBoolean()) return Optional.empty();
            double time=Math.round((now+tick*dt)*1e6)/1e6;
            if(time>900) return Optional.empty();
            boolean returning=Double.isFinite(arrival) && time>=reviewEnd;
            Vec3 destination=returning?profile.depot():goal;
            var wanted=pilot.steer(returning?"return":"out",drone.position(),()->List.of(destination),dt);
            if(wanted.isEmpty()) return Optional.empty();
            motion.step(drone,wanted.get(),dt,true);
            if(!Double.isFinite(arrival) && drone.position().horizontalDistance(goal)<=FieldWorld.TARGET_HORIZONTAL_M
                    && Math.abs(drone.position().u()-goal.u())<=FieldWorld.HEIGHT_BAND_M) {
                arrival=Math.ceil(time-1e-6);
                bgStart=arrival+procedure.setupS(); bgEnd=bgStart+procedure.backgroundS();
                colStart=bgEnd+procedure.purgeS()+procedure.minimumSettleS(); colEnd=colStart+procedure.validCollectionS();
                reviewEnd=colEnd+procedure.minimumRecoveryS()+procedure.reviewS();
            }
            if(!Double.isFinite(arrival) || time<=colEnd)
                samples.add(new InletSample(time,drone.inlet().toEnu()));
            if(returning && drone.position().horizontalDistance(profile.depot())<=FieldWorld.LANDED_HORIZONTAL_M
                    && drone.position().u()<=profile.depot().u()+FieldWorld.LANDED_HEIGHT_M && drone.speed()==0) {
                return Optional.of(new ProspectiveInlets.Forecast(samples,
                        List.of(new Stage("BACKGROUND",bgStart,bgEnd),new Stage("COLLECT",colStart,colEnd)),
                        time-now,vehicle.energyWh()-drone.energyWh()));
            }
        }
        return Optional.empty();
    }
}
