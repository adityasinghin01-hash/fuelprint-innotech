package fuelprint.field.command;

import static fuelprint.field.command.FieldRecords.LEASE;
import static fuelprint.field.command.FieldRecords.LEASE_ACTIVE;
import static fuelprint.field.command.FieldRecords.MISSION;
import static fuelprint.field.command.FieldRecords.find;
import static fuelprint.field.command.FieldRecords.state;

import java.time.Instant;
import java.util.Optional;

import fuelprint.api.Pilot;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;

/**
 * Builds one command's lease and mission revisions: end the lease that is active now (if its record is
 * still ACTIVE), grant a new one, and write the mission once with its new state and pilot.
 */
final class LeaseMoves {

    private final FieldWorld world;
    private final StoreView store;
    private final HandlerResult.Builder result = HandlerResult.builder();
    private final StoredRecord mission;
    private String missionState;
    private Pilot missionPilot;
    private boolean ended;

    LeaseMoves(FieldWorld world, StoreView store, StoredRecord mission) {
        this.world = world;
        this.store = store;
        this.mission = mission;
        if (mission != null) {
            missionState = state(mission);
            missionPilot = pilotOf(mission);
        }
    }

    /** The current mission record of the drone, or null. */
    static StoredRecord currentMission(FieldWorld world, StoreView store) {
        return world.currentMission().map(m -> find(store, MISSION, m.id())).orElse(null);
    }

    static Pilot pilotOf(StoredRecord mission) {
        try {
            return Pilot.valueOf(mission.body().path("pilot").asText("NONE"));
        } catch (IllegalArgumentException e) {
            return Pilot.NONE;
        }
    }

    /** The active lease's record, if the world has one and its record is still ACTIVE. */
    Optional<StoredRecord> liveLeaseRecord() {
        return world.lease().map(l -> find(store, LEASE, l.leaseId()))
                .filter(r -> LEASE_ACTIVE.equals(state(r)));
    }

    /** Ends the active lease with {@code state} and {@code reason}; nothing if there is none. */
    LeaseMoves endCurrent(String state, String reason) {
        Optional<FieldWorld.LeaseInfo> live = world.lease();
        Optional<StoredRecord> record = liveLeaseRecord();
        if (live.isPresent() && record.isPresent()) {
            result.save(FieldRecords.endLease(record.get(), state, reason, live.get().lastSequence()),
                    "LEASE_" + state, world.simTimeS());
            ended = true;
            missionPilot = Pilot.NONE;
        }
        return this;
    }

    boolean endedSomething() {
        return ended;
    }

    /** Grants a new ACTIVE lease to {@code holder}; an APPROVED mission becomes LEASED. */
    LeaseMoves grant(String holder, Pilot kind, String purpose, Instant now) {
        String runId = mission != null ? mission.runId() : world.runId().orElse(null);
        String missionId = mission != null ? mission.id() : null;
        result.save(FieldRecords.newLease(runId, missionId, world.droneId(), holder, kind, purpose, now),
                "LEASE_GRANTED", world.simTimeS());
        missionPilot = kind;
        if (MissionView.APPROVED.equals(missionState)) {
            missionState = MissionView.LEASED;
        }
        return this;
    }

    /** Sets the mission state to write (if there is a mission). */
    LeaseMoves missionState(String state) {
        if (mission != null) {
            missionState = state;
        }
        return this;
    }

    LeaseMoves pilot(Pilot pilot) {
        missionPilot = pilot;
        return this;
    }

    /** Adds any other record of this command. */
    LeaseMoves also(RecordChange change, String eventType) {
        result.save(change, eventType, world.simTimeS());
        return this;
    }

    HandlerResult build() {
        if (mission != null && FieldRecords.missionChanges(mission, missionState, missionPilot)) {
            String event = missionState.equals(state(mission)) ? "MISSION_PILOT_CHANGED" : "MISSION_" + missionState;
            result.save(FieldRecords.mission(mission, missionState, missionPilot), event, world.simTimeS());
        }
        return result.build();
    }
}
