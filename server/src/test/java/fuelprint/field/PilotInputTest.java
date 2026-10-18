package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;

import fuelprint.api.Pilot;
import fuelprint.api.VehicleState;
import fuelprint.core.command.Session;
import fuelprint.field.geo.Vec3;
import fuelprint.field.pilot.PilotInputService;
import fuelprint.field.world.FieldWorld;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** AGREEMENT section 7: input at 10 Hz, the 400 ms dead man, stale sequences, neutral, reconnect, limits. */
class PilotInputTest extends FieldSpringTest {

    @Autowired PilotInputService pilotInput;

    /** Flies north at full input for {@code ticks} ticks, one message every 2 ticks (10 Hz). Returns the next sequence. */
    long fly(Session pilot, String leaseId, long sequence, int ticks, Vec3 velocity) {
        for (int i = 0; i < ticks; i++) {
            if (i % 2 == 0) {
                assertThat(input(pilot, leaseId, sequence++, velocity)).isEqualTo(FieldWorld.InputResult.ACCEPTED);
            }
            tick();
        }
        return sequence;
    }

    @Test
    void deadManStopsTheDroneWithin500msOfTheLastInput() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        long seq = fly(visitor, lease, 1, 60, new Vec3(0, 0, 3));
        assertThat(speed()).isGreaterThan(2.0);
        assertThat(input(visitor, lease, seq, new Vec3(0, 0, 3))).isEqualTo(FieldWorld.InputResult.ACCEPTED);

        // Each tick is 50 ms of wall time after the last input.
        int stoppedAfterTicks = -1;
        for (int i = 1; i <= 12; i++) {
            tick();
            if (speed() == 0 && stoppedAfterTicks < 0) {
                stoppedAfterTicks = i;
            }
            if (i * 50 < 400) {
                assertThat(speed()).as("still flying before 400 ms").isPositive();
            }
        }
        assertThat(stoppedAfterTicks * 50).as("ms from last input to stopped").isGreaterThan(400).isLessThanOrEqualTo(500);
        ticks(20);
        assertThat(speed()).isZero();
    }

    @Test
    void theDeadManAlsoWorksFromTheSafetyLoopWhileTheWorldLoopIsStopped() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        fly(visitor, lease, 1, 40, new Vec3(0, 0, 3));
        assertThat(speed()).isPositive();
        time.advanceMillis(450);
        world.safetyTick();
        assertThat(speed()).isZero();
    }

    @Test
    void staleSequenceIsIgnoredAndDoesNotRenewTheLease() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        fly(visitor, lease, 100, 10, new Vec3(0, 0, 2));
        // Last accepted sequence is 104. Older and repeated numbers are refused.
        assertThat(input(visitor, lease, 104, new Vec3(0, 0, 3))).isEqualTo(FieldWorld.InputResult.STALE_SEQUENCE);
        assertThat(input(visitor, lease, 7, new Vec3(0, 0, 3))).isEqualTo(FieldWorld.InputResult.STALE_SEQUENCE);
        assertThat(world.lease().orElseThrow().lastSequence()).isEqualTo(104L);

        // Keep sending only stale input: the lease is not renewed and expires 2 s after the last fresh one.
        for (int i = 0; i < 44; i++) {
            input(visitor, lease, 50, new Vec3(0, 0, 3));
            tick();
            bus.awaitIdle();
        }
        settle();
        assertThat(record("lease", lease).body().path("state").asText()).isEqualTo("EXPIRED");
        assertThat(record("lease", lease).body().path("last_sequence").asLong()).isEqualTo(104);
        assertThat(world.lease()).isEmpty();
        assertThat(speed()).isZero();
        assertThat(appliedAutomatic("end_lease")).hasSize(1);
        assertThat(mission(mission).path("pilot").asText()).isEqualTo("NONE");
    }

    @Test
    void freshInputRenewsTheLeaseForTwoSeconds() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        fly(visitor, lease, 1, 100, new Vec3(0, 0, 1));
        assertThat(world.lease()).isPresent();
        assertThat(record("lease", lease).body().path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void oneNeutralMessageStopsAtOnce() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        long seq = fly(visitor, lease, 1, 60, new Vec3(0, 0, 3));
        assertThat(speed()).isPositive();
        assertThat(input(visitor, lease, seq, null)).isEqualTo(FieldWorld.InputResult.ACCEPTED);
        assertThat(speed()).isZero();
        tick();
        assertThat(speed()).isZero();
    }

    @Test
    void afterAReconnectTheDroneStaysNeutralAndOldInputIsNeverReplayed() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        long seq = fly(visitor, lease, 1, 60, new Vec3(0, 0, 3));
        pilotInput.disconnected(visitor.actorLabel());
        assertThat(speed()).isZero();
        pilotInput.connected(visitor.actorLabel());
        for (int i = 0; i < 6; i++) {
            tick();
            assertThat(speed()).as("no replay of the last velocity").isZero();
        }
        assertThat(input(visitor, lease, seq - 1, new Vec3(0, 0, 3))).isEqualTo(FieldWorld.InputResult.STALE_SEQUENCE);
        assertThat(input(visitor, lease, seq, new Vec3(0, 0, 3))).isEqualTo(FieldWorld.InputResult.ACCEPTED);
        tick();
        assertThat(speed()).isPositive();
    }

    @Test
    void theServerLimitsSpeedAndAccelerationOfPilotInput() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        long seq = fly(visitor, lease, 1, 60, new Vec3(0, 0, 10));
        fly(visitor, lease, seq, 200, new Vec3(10, 10, 0));
        double previous = 0;
        VehicleState last = null;
        for (VehicleState state : track) {
            double v = Math.sqrt(state.velocity().east() * state.velocity().east()
                    + state.velocity().north() * state.velocity().north() + state.velocity().up() * state.velocity().up());
            assertThat(v).isLessThanOrEqualTo(3.0 + 1e-9);
            if (last != null) {
                double dv = Math.sqrt(Math.pow(state.velocity().east() - last.velocity().east(), 2)
                        + Math.pow(state.velocity().north() - last.velocity().north(), 2)
                        + Math.pow(state.velocity().up() - last.velocity().up(), 2));
                assertThat(dv).isLessThanOrEqualTo(DT * 1.0 + 1e-9);
            }
            assertThat(state.pose().up()).isBetween(2.0, 80.0);
            last = state;
            previous = v;
        }
        assertThat(previous).isGreaterThan(2.9);
        assertThat(last.pilot()).isEqualTo(Pilot.VISITOR);
        assertThat(last.leaseHolder()).isEqualTo(visitor.actorLabel());
        assertThat(last.inlet().up()).isEqualTo(last.pose().up() - 0.4);
    }

    @Test
    void inputFromSomeoneElseOrMalformedInputIsRefusedAndDoesNotRenew() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        assertThat(input(OPERATOR, lease, 1, new Vec3(0, 0, 1))).isEqualTo(FieldWorld.InputResult.NOT_HOLDER);
        assertThat(world.acceptInput(visitor.actorLabel(), "00000000-0000-4000-8000-000000000000", 1, false,
                new Vec3(0, 0, 1))).isEqualTo(FieldWorld.InputResult.NO_LEASE);
        assertThat(pilotInput.accept(visitor.actorLabel(), "{not json")).isEqualTo(FieldWorld.InputResult.INVALID);
        assertThat(pilotInput.accept(visitor.actorLabel(), "{\"lease_id\":\"" + lease + "\",\"sequence\":1,"
                + "\"neutral\":false,\"velocity_enu\":{\"east\":\"x\",\"north\":0,\"up\":0}}"))
                .isEqualTo(FieldWorld.InputResult.INVALID);
        assertThat(pilotInput.accept(visitor.actorLabel(), "{\"lease_id\":\"" + lease + "\",\"sequence\":1,"
                + "\"neutral\":false,\"velocity_enu\":{\"east\":0,\"north\":0,\"up\":1.5},\"sent_utc\":\"x\"}"))
                .isEqualTo(FieldWorld.InputResult.ACCEPTED);
        assertThat(world.lease().orElseThrow().lastSequence()).isEqualTo(1L);
    }
}
