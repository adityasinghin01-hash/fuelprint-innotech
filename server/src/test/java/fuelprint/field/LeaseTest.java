package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import fuelprint.api.AutomationSettings;
import fuelprint.api.Pilot;
import fuelprint.core.command.Command;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Session;
import fuelprint.field.geo.Vec3;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;
import org.junit.jupiter.api.Test;

/** AGREEMENT sections 5 and 7: one lease holder, take over stops first, no hand-over while COLLECTING. */
class LeaseTest extends FieldSpringTest {

    void flyUp(Session pilot, String lease, long fromSequence, int ticks) {
        long seq = fromSequence;
        for (int i = 0; i < ticks; i++) {
            if (i % 2 == 0) {
                input(pilot, lease, seq++, new Vec3(0, 0, 3));
            }
            tick();
        }
    }

    @Test
    void acquiringMovesTheMissionToLeasedWithThePilot() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        assertThat(missionState(mission)).isEqualTo("LEASED");
        assertThat(mission(mission).path("pilot").asText()).isEqualTo("VISITOR");
        assertThat(record("lease", lease).body().path("holder_session").asText()).isEqualTo(visitor.actorLabel());
        assertThat(record("lease", lease).body().path("state").asText()).isEqualTo("ACTIVE");
        assertThat(record("lease", lease).body().hasNonNull("expires_utc")).isTrue();
        flyUp(visitor, lease, 1, 20);
        settle();
        assertThat(missionState(mission)).as("off the pad").isEqualTo("OUTBOUND");
        assertThat(appliedAutomatic("advance_mission")).hasSize(1);
    }

    @Test
    void onlyOnePersonHoldsTheLease() {
        String mission = approvedMission();
        Session first = pairedVisitor(mission);
        Session second = pairedVisitor(mission);
        Receipt notYet = send(second, "acquire_lease", missionVehicle(mission));
        assertThat(notYet.reasonCode()).isEqualTo("NOT_YOUR_TURN");

        acquire(first, mission);
        Receipt held = send(second, "acquire_lease", missionVehicle(mission));
        assertThat(held.reasonCode()).isEqualTo("LEASE_HELD");
        Receipt operator = send(OPERATOR, "acquire_lease", missionVehicle(mission));
        assertThat(operator.reasonCode()).isEqualTo("LEASE_HELD");
        Receipt again = send(first, "acquire_lease", missionVehicle(mission));
        assertThat(again.reasonCode()).isEqualTo("ALREADY_HOLDER");
        assertThat(records("lease")).filteredOn(l -> l.body().path("state").asText().equals("ACTIVE")).hasSize(1);
    }

    @Test
    void anUnpairedVisitorCannotFly() {
        String mission = approvedMission();
        Session stranger = sessions.createVisitor("visitor-zz99");
        assertThat(send(stranger, "acquire_lease", missionVehicle(mission)).reasonCode()).isEqualTo("NOT_PAIRED");
    }

    @Test
    void releasingHandsTheTurnToTheNextVisitorAndOnlyTheHolderCanRelease() {
        String mission = approvedMission();
        Session first = pairedVisitor(mission);
        Session second = pairedVisitor(mission);
        String lease = acquire(first, mission);
        assertThat(send(second, "release_lease", payload().put("lease_id", lease)).reasonCode()).isEqualTo("NOT_HOLDER");
        Receipt released = send(first, "release_lease", payload().put("lease_id", lease));
        assertThat(released.applied()).isTrue();
        assertThat(record("lease", lease).body().path("state").asText()).isEqualTo("RELEASED");
        assertThat(mission(mission).path("pilot").asText()).isEqualTo("NONE");
        assertThat(world.queue(mission)).extracting(FieldWorld.QueueEntry::visitor).containsExactly(second.actorLabel());
        acquire(second, mission);
    }

    @Test
    void takeOverStopsTheDroneFirstThenMovesTheLease() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        flyUp(visitor, lease, 1, 60);
        assertThat(speed()).isPositive();

        Receipt taken = send(SUPERVISOR, "take_over", payload().put("vehicle_id", world.droneId()));
        assertThat(taken.applied()).as(taken.reasonText()).isTrue();
        assertThat(speed()).as("stopped first").isZero();
        assertThat(record("lease", lease).body().path("state").asText()).isEqualTo("REVOKED");
        assertThat(record("lease", lease).body().path("ended_reason").asText()).isEqualTo("TAKEN_OVER");
        FieldWorld.LeaseInfo now = world.lease().orElseThrow();
        assertThat(now.holder()).isEqualTo(SUPERVISOR.actorLabel());
        assertThat(now.kind()).isEqualTo(Pilot.OPERATOR);
        assertThat(mission(mission).path("pilot").asText()).isEqualTo("OPERATOR");
        // The old holder's packets are refused even with a higher sequence.
        assertThat(input(visitor, lease, 9_999, new Vec3(0, 0, 3))).isEqualTo(FieldWorld.InputResult.NO_LEASE);
        tick();
        assertThat(speed()).isZero();
    }

    @Test
    void aVisitorCannotTakeOverFromTheAutopilotWhileCollecting() {
        settings.set(AutomationSettings.AUTO_CAPTURE, true);
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> missionState(mission).equals("COLLECTING"), 3000, "COLLECTING");

        Receipt refused = send(visitor, "acquire_lease", missionVehicle(mission));
        assertThat(refused.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(refused.reasonCode()).isEqualTo("COLLECTING");
        Receipt operator = send(OPERATOR, "take_over", payload().put("vehicle_id", world.droneId()));
        assertThat(operator.reasonCode()).isEqualTo("COLLECTING");
        assertThat(world.lease().orElseThrow().kind()).isEqualTo(Pilot.AUTOPILOT);
    }

    @Test
    void aVisitorCanTakeOverFromTheAutopilotOutsideCollectionAndTheDroneStopsFirst() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        assertThat(send(OPERATOR, "start_autopilot", missionVehicle(mission)).applied()).isTrue();
        tickUntil(() -> speed() > 2, 400, "autopilot flying");
        String autopilotLease = world.lease().orElseThrow().leaseId();

        String lease = acquire(visitor, mission);
        assertThat(speed()).isZero();
        assertThat(record("lease", autopilotLease).body().path("state").asText()).isEqualTo("REVOKED");
        assertThat(record("lease", autopilotLease).body().path("ended_reason").asText()).isEqualTo("HANDED_OVER");
        assertThat(mission(mission).path("pilot").asText()).isEqualTo("VISITOR");
        assertThat(lease).isNotEqualTo(autopilotLease);
    }

    @Test
    void wrongRolesAreRefused() {
        String mission = approvedMission();
        assertThat(send(QC, "acquire_lease", missionVehicle(mission)).reasonCode()).isEqualTo("NOT_ALLOWED");
        Session visitor = pairedVisitor(mission);
        assertThat(send(visitor, "take_over", payload().put("vehicle_id", world.droneId())).reasonCode())
                .isEqualTo("NOT_ALLOWED");
        assertThat(send(visitor, "start_autopilot", missionVehicle(mission)).reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(send(visitor, "end_lease", payload()).reasonCode()).isEqualTo("NOT_ALLOWED");
        assertThat(send(OPERATOR, "advance_mission", payload()).reasonCode()).isEqualTo("NOT_ALLOWED");
    }

    @Test
    void aStaleMissionRevisionIsRefusedAndARepeatedCommandAppliesOnce() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        long revision = record("mission", mission).revision();
        Receipt stale = send(visitor, "acquire_lease", missionVehicle(mission), revision - 1);
        assertThat(stale.reasonCode()).isEqualTo("STALE_REVISION");

        Command acquire = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), "acquire_lease", runId,
                revision, missionVehicle(mission));
        Receipt first = bus.send(acquire, visitor);
        settle();
        Receipt second = bus.send(acquire, visitor);
        assertThat(first.applied()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(records("lease")).hasSize(1);
    }

    @Test
    void theRunEndingRevokesLeasesStopsTheDroneAndInterruptsTheMission() {
        String mission = approvedMission();
        Session visitor = pairedVisitor(mission);
        String lease = acquire(visitor, mission);
        flyUp(visitor, lease, 1, 40);
        assertThat(speed()).isPositive();
        Receipt pairing = send(OPERATOR, "create_pairing", missionVehicle(mission));
        assertThat(pairing.applied()).isTrue();

        events.publishEvent(new fuelprint.api.RunEnded(runId, fuelprint.api.RunEnded.Reason.ABORTED));
        assertThat(speed()).isZero();
        assertThat(world.lease()).isEmpty();
        settle();
        assertThat(missionState(mission)).isEqualTo(MissionView.INTERRUPTED);
        assertThat(record("lease", lease).body().path("state").asText()).isEqualTo("REVOKED");
        assertThat(record("lease", lease).body().path("ended_reason").asText()).isEqualTo("RUN_ABORTED");
        assertThat(record("pairing", pairing.recordRefs().get(0).id()).body().path("state").asText()).isEqualTo("EXPIRED");
        assertThat(appliedAutomatic("interrupt_field")).hasSize(1);
        assertThat(input(visitor, lease, 500, new Vec3(0, 0, 3))).isEqualTo(FieldWorld.InputResult.NO_LEASE);
    }
}
