package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import fuelprint.api.AutomationSettings;
import fuelprint.api.Pilot;
import fuelprint.api.VehicleState;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Session;
import fuelprint.field.geo.Vec3;
import fuelprint.field.world.MissionView;
import org.junit.jupiter.api.Test;

/** AI-7 autopilot (AGREEMENT sections 7 and 12). */
class AutopilotTest extends FieldSpringTest {

    /** Ticks for {@code seconds} of loop time at time scale 1. */
    static int seconds(double s) {
        return (int) Math.round(s / DT);
    }

    void assertInsideEveryLimit() {
        VehicleState last = null;
        for (int i = 0; i < track.size(); i++) {
            VehicleState state = track.get(i);
            Vec3 v = new Vec3(state.velocity().east(), state.velocity().north(), state.velocity().up());
            assertThat(v.norm()).isLessThanOrEqualTo(3.0 + 1e-9);
            if (last != null) {
                Vec3 before = new Vec3(last.velocity().east(), last.velocity().north(), last.velocity().up());
                double dt = trackDt.get(i);
                // A safety stop may drop to zero at once; every other change is at most 1 m/s per second.
                if (v.norm() > 0) {
                    assertThat(v.minus(before).norm()).as("tick %d", i).isLessThanOrEqualTo(dt * 1.0 + 1e-9);
                }
            }
            assertThat(state.pose().east()).isBetween(-450.0, 450.0);
            assertThat(state.pose().north()).isBetween(-450.0, 450.0);
            assertThat(state.pose().up()).isBetween(2.0, 80.0);
            assertThat(TestProfile.OBSTACLES.blocked(new Vec3(state.pose().east(), state.pose().north(),
                    state.pose().up()))).as("2 m clearance at tick %d", i).isFalse();
            last = state;
        }
    }

    @Test
    void startsByItselfAfterTheWaitWhenAutoAutopilotIsOn() {
        settings.set(AutomationSettings.AUTO_AUTOPILOT, true);
        String mission = approvedMission();
        ticks(seconds(59));
        assertThat(automatic("start_autopilot")).isEmpty();
        assertThat(world.lease()).isEmpty();
        tickUntil(() -> world.lease().isPresent(), seconds(2), "autopilot start");
        assertThat(appliedAutomatic("start_autopilot")).hasSize(1);
        assertThat(world.lease().orElseThrow().kind()).isEqualTo(Pilot.AUTOPILOT);
        assertThat(world.lease().orElseThrow().holder()).isEqualTo("autopilot");
        assertThat(mission(mission).path("pilot").asText()).isEqualTo("AUTOPILOT");
        assertThat(missionState(mission)).isEqualTo("LEASED");
        assertThat(world.vehicles().get(0).pilot()).isEqualTo(Pilot.AUTOPILOT);
        assertThat(world.vehicles().get(0).leaseHolder()).isEqualTo("autopilot");
    }

    @Test
    void theWaitFollowsAutopilotWaitS() {
        settings.set(AutomationSettings.AUTO_AUTOPILOT, true);
        settings.waitS(5);
        approvedMission();
        ticks(seconds(4.5));
        assertThat(world.lease()).isEmpty();
        tickUntil(() -> world.lease().isPresent(), seconds(1), "autopilot start after 5 s");
    }

    @Test
    void doesNotStartByItselfWhenAutoAutopilotIsOff() {
        String mission = approvedMission();
        ticks(seconds(120));
        assertThat(automatic("start_autopilot")).isEmpty();
        assertThat(world.lease()).isEmpty();
        assertThat(missionState(mission)).isEqualTo("APPROVED");
    }

    @Test
    void doesNotStartByItselfWhileAPersonFlies() {
        settings.set(AutomationSettings.AUTO_AUTOPILOT, true);
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        long seq = 1;
        for (int i = 0; i < seconds(70); i++) {
            if (i % 2 == 0) {
                input(visitor, lease, seq++, new Vec3(0, 0, i < 200 ? 1 : 0));
            }
            tick();
        }
        settle();
        assertThat(automatic("start_autopilot")).isEmpty();
        assertThat(world.lease().orElseThrow().holder()).isEqualTo(visitor.actorLabel());
    }

    @Test
    void automationCannotStartItEarlyOrWhenSwitchedOff() {
        String mission = approvedMission();
        ticks(seconds(61));
        // Switched off: the listener does not even send it.
        assertThat(automatic("start_autopilot")).isEmpty();
        settings.set(AutomationSettings.AUTO_AUTOPILOT, true);
        settings.waitS(600);
        ticks(2);
        assertThat(automatic("start_autopilot")).isEmpty();
        assertThat(missionState(mission)).isEqualTo("APPROVED");
    }

    @Test
    void flysTheSc1RouteToAtTargetInsideTheLimitsCapturesAndComesHome() {
        settings.set(AutomationSettings.AUTO_AUTOPILOT, true).set(AutomationSettings.AUTO_CAPTURE, true);
        String mission = approvedMission();
        tickUntil(() -> world.lease().isPresent(), seconds(62), "autopilot start");

        tickUntil(() -> missionState(mission).equals("AT_TARGET") || missionState(mission).equals("COLLECTING"),
                seconds(120), "AT_TARGET");
        VehicleState there = world.vehicles().get(0);
        assertThat(Math.hypot(there.pose().east() - 0, there.pose().north() - 100)).isLessThanOrEqualTo(5.0);
        assertThat(there.pose().up()).isCloseTo(35.0, within(2.0));

        tickUntil(() -> missionState(mission).equals("COLLECTING"), seconds(5), "automatic capture");
        // Holds position inside the height band for the whole capture.
        tickUntil(() -> !missionState(mission).equals("COLLECTING"), 2000, "capture done");
        for (VehicleState state : track.subList(track.size() - 300, track.size() - 1)) {
            assertThat(Math.hypot(state.pose().east(), state.pose().north() - 100)).isLessThanOrEqualTo(5.0);
            assertThat(state.pose().up()).isCloseTo(35.0, within(2.0));
        }
        assertThat(missionState(mission)).isEqualTo("RETURNING");
        assertThat(appliedAutomatic("return_to_depot")).hasSize(1);
        assertThat(String.valueOf(appliedAutomatic("return_to_depot").get(0).get("payload"))).contains("MISSION_DONE");

        tickUntil(() -> missionState(mission).equals("COMPLETE"), seconds(150), "home");
        assertThat(world.landed()).isTrue();
        assertThat(speed()).isZero();
        assertThat(world.lease()).isEmpty();
        assertThat(mission(mission).path("pilot").asText()).isEqualTo("NONE");
        assertInsideEveryLimit();
        assertThat(world.drone().energyWh()).isLessThan(64).isGreaterThan(12);

        List<String> states = store.revisions("mission", mission).stream()
                .map(r -> r.body().path("state").asText()).distinct().toList();
        assertThat(states).containsExactly("APPROVED", "LEASED", "OUTBOUND", "AT_TARGET", "COLLECTING",
                "RETURNING", "COMPLETE");
    }

    @Test
    void whenAPersonsLeaseEndsTheAutopilotResumesAfter10s() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> world.drone().position().u() > 20, seconds(60), "climbing");
        String lease = acquire(visitor, mission);
        assertThat(speed()).isZero();
        long seq = 1;
        for (int i = 0; i < 40; i++) {
            if (i % 2 == 0) {
                input(visitor, lease, seq++, new Vec3(1, 0, 0));
            }
            tick();
        }
        assertThat(send(visitor, "release_lease", payload().put("lease_id", lease)).applied()).isTrue();
        assertThat(speed()).as("the drone stops when the lease ends").isZero();

        ticks(seconds(9.5));
        assertThat(world.lease()).isEmpty();
        assertThat(speed()).isZero();
        tickUntil(() -> world.lease().isPresent(), seconds(1), "resume");
        assertThat(world.lease().orElseThrow().kind()).isEqualTo(Pilot.AUTOPILOT);
        assertThat(String.valueOf(appliedAutomatic("start_autopilot").get(0).get("payload"))).contains("resume");
        tickUntil(() -> missionState(mission).equals("AT_TARGET"), seconds(90), "AT_TARGET after resume");
    }

    @Test
    void afterStopAutopilotItDoesNotResume() {
        String mission = approvedMission();
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> speed() > 1, seconds(10), "flying");
        Receipt stop = send(SUPERVISOR, "stop_autopilot", payload().put("vehicle_id", world.droneId()));
        assertThat(stop.applied()).isTrue();
        assertThat(speed()).as("stops first").isZero();
        ticks(seconds(15));
        assertThat(world.lease()).isEmpty();
        assertThat(speed()).isZero();
        assertThat(automatic("start_autopilot")).isEmpty();
        assertThat(send(OPERATOR, "stop_autopilot", payload().put("vehicle_id", world.droneId())).reasonCode())
                .isEqualTo("NO_AUTOPILOT");
    }

    @Test
    void cannotStartWhileAPersonHoldsTheLeaseOrForAFinishedMission() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        acquire(visitor, mission);
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).reasonCode()).isEqualTo("LEASE_HELD");

        String other = java.util.UUID.randomUUID().toString();
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(other)).reasonCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void batteryReserveSendsTheDroneHomeAsAutomationWhoeverIsFlying() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        long seq = 1;
        for (int i = 0; i < seconds(12); i++) {
            if (i % 2 == 0) {
                input(visitor, lease, seq++, new Vec3(0, 0, 3));
            }
            tick();
        }
        settle();
        assertThat(automatic("return_to_depot")).isEmpty();
        double needed = world.returnEnergyWh();
        world.drone().setEnergyWh(12 + needed + 0.002);
        for (int i = 0; i < seconds(3); i++) {
            if (i % 2 == 0) {
                input(visitor, lease, seq++, new Vec3(0, 1, 0));
            }
            tick();
            bus.awaitIdle();
        }
        settle();
        List<java.util.Map<String, Object>> returns = appliedAutomatic("return_to_depot");
        assertThat(returns).hasSize(1);
        assertThat(String.valueOf(returns.get(0).get("payload"))).contains("BATTERY_RESERVE");
        assertThat(record("lease", lease).body().path("state").asText()).isEqualTo("REVOKED");
        assertThat(record("lease", lease).body().path("ended_reason").asText()).isEqualTo("BATTERY_RESERVE");
        assertThat(world.lease().orElseThrow().kind()).isEqualTo(Pilot.AUTOPILOT);
        assertThat(world.lease().orElseThrow().homing()).isTrue();
        assertThat(missionState(mission)).isEqualTo(MissionView.RETURNING);

        tickUntil(() -> missionState(mission).equals("COMPLETE"), seconds(60), "home");
        assertThat(world.drone().energyWh()).as("lands with the reserve").isGreaterThanOrEqualTo(12.0);
        assertThat(appliedAutomatic("return_to_depot")).hasSize(1);
    }

    @Test
    void anOperatorCanSendTheDroneHomeButNotFromTheDepot() {
        String mission = approvedMission();
        assertThat(send(OPERATOR, "return_to_depot", payload().put("vehicle_id", world.droneId())
                .put("reason", "OPERATOR")).reasonCode()).isEqualTo("AT_DEPOT");
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> missionState(mission).equals("OUTBOUND") && world.drone().position().u() > 10, seconds(30),
                "outbound");
        assertThat(send(OPERATOR, "return_to_depot", payload().put("vehicle_id", world.droneId())
                .put("reason", "LUNCH")).reasonCode()).isEqualTo("INVALID");
        Receipt home = send(OPERATOR, "return_to_depot", payload().put("vehicle_id", world.droneId())
                .put("reason", "OPERATOR"));
        assertThat(home.applied()).as(home.reasonText()).isTrue();
        assertThat(missionState(mission)).isEqualTo("RETURNING");
        tickUntil(() -> missionState(mission).equals("COMPLETE"), seconds(60), "home");
    }
}
