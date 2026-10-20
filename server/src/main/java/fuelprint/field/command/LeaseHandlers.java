package fuelprint.field.command;

import static fuelprint.field.command.FieldRecords.LEASE;
import static fuelprint.field.command.FieldRecords.LEASE_ACTIVE;
import static fuelprint.field.command.FieldRecords.MISSION;
import static fuelprint.field.command.FieldRecords.find;
import static fuelprint.field.command.FieldRecords.missing;
import static fuelprint.field.command.FieldRecords.reject;
import static fuelprint.field.command.FieldRecords.state;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import fuelprint.api.Pilot;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.Rejection;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.FieldTime;
import fuelprint.field.FieldPorts;
import fuelprint.api.MissionAuthority;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Leases (AGREEMENT sections 5 and 7): one pilot at a time; a person's lease lasts 2 s and is renewed only by
 * fresh valid input on {@code /ws/pilot}; the vehicle stops first whenever a lease moves.
 */
public final class LeaseHandlers {

    private LeaseHandlers() {
    }

    /** Checks the mission and vehicle named in the payload are the drone's current mission. */
    static Object currentMission(Command command, StoreView store, FieldWorld world) {
        String missionId = FieldRecords.id(command, "mission_id");
        String vehicleId = FieldRecords.id(command, "vehicle_id");
        if (missionId == null || vehicleId == null) {
            return missing(missionId == null ? "mission_id" : "vehicle_id");
        }
        StoredRecord mission = find(store, MISSION, missionId);
        if (mission == null) {
            return reject("NOT_FOUND", "There is no mission " + missionId + ".");
        }
        if (!vehicleId.equals(world.droneId()) || !vehicleId.equals(mission.body().path("vehicle_id").asText())) {
            return reject("WRONG_VEHICLE", "That vehicle does not fly this mission.");
        }
        if (!MissionView.ACTIVE.contains(state(mission))) {
            return reject("WRONG_STATE", "The mission is " + state(mission) + "; it can be flown only from APPROVED "
                    + "until it is COMPLETE.");
        }
        if (world.currentMission().map(m -> !m.id().equals(missionId)).orElse(true)) {
            return reject("NOT_CURRENT_MISSION", "The drone is busy with another mission.");
        }
        return mission;
    }

    /** Recheck only initial mission authority; started flight, handover and safety return retain their rules. */
    static Rejection initialDeparture(Command command, StoredRecord mission, FieldPorts ports) {
        if (!MissionView.APPROVED.equals(state(mission))) return null;
        MissionAuthority.Decision authority = ports.initialDeparture(command.runId(), mission.id());
        return authority.allowed() ? null : new Rejection(authority.reasonCode(), authority.reasonText());
    }

    static Pilot personKind(Session session) {
        return session.roles().contains(Role.VISITOR) && !session.roles().contains(Role.FIELD_OPERATOR)
                && !session.roles().contains(Role.SUPERVISOR) ? Pilot.VISITOR : Pilot.OPERATOR;
    }

    /**
     * {@code acquire_lease} (visitor or field_operator): mission_id, vehicle_id. Subject: the mission.
     * No other person may hold the lease; an autopilot lease is handed over only when the mission is not
     * COLLECTING. A visitor must be paired and first in the queue.
     */
    @Component
    @Profile("!stub")
    public static class AcquireLease implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;

        private final FieldPorts ports;

        public AcquireLease(FieldWorld world, FieldTime time, FieldPorts ports) {
            this.world = world;
            this.time = time;
            this.ports = ports;
        }

        @Override
        public String commandType() {
            return "acquire_lease";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.VISITOR, Role.FIELD_OPERATOR);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "mission_id");
            return id == null ? null : new RecordKey(MISSION, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            Object found = currentMission(command, store, world);
            if (found instanceof Rejection rejection) {
                return rejection;
            }
            StoredRecord mission = (StoredRecord) found;
            Optional<FieldWorld.LeaseInfo> lease = world.lease().filter(l -> !l.ending());
            if (lease.isPresent() && lease.get().person()) {
                return lease.get().holder().equals(session.actorLabel())
                        ? reject("ALREADY_HOLDER", "You already hold the lease.")
                        : reject("LEASE_HELD", "Someone else is flying. Wait for your turn.");
            }
            if (lease.isPresent() && MissionView.COLLECTING.equals(state(mission))) {
                return reject("COLLECTING", "The autopilot is holding still while the sample is collected. "
                        + "You can take over when collection ends.");
            }
            Pilot kind = personKind(session);
            if (kind == Pilot.VISITOR) {
                List<FieldWorld.QueueEntry> queue = world.queue(mission.id());
                boolean queued = queue.stream().anyMatch(e -> e.visitor().equals(session.actorLabel()));
                if (!queued) {
                    return reject("NOT_PAIRED", "Pair this phone with the field operator first.");
                }
                if (!queue.get(0).visitor().equals(session.actorLabel())) {
                    return reject("NOT_YOUR_TURN", "Someone is ahead of you in the queue.");
                }
            }
            Rejection authority = initialDeparture(command, mission, ports);
            if (authority != null) return authority;
            LeaseMoves moves = new LeaseMoves(world, store, mission)
                    .endCurrent(lease.isPresent() ? FieldRecords.LEASE_REVOKED : FieldRecords.LEASE_EXPIRED,
                            lease.isPresent() ? "HANDED_OVER" : "EXPIRED")
                    .grant(session.actorLabel(), kind, "MISSION", time.now());
            world.freeze();
            return moves.build();
        }
    }

    /** {@code release_lease} (the lease holder): lease_id. Subject: the lease. The drone stops. */
    @Component
    @Profile("!stub")
    public static class ReleaseLease implements CommandHandler {
        private final FieldWorld world;

        public ReleaseLease(FieldWorld world) {
            this.world = world;
        }

        @Override
        public String commandType() {
            return "release_lease";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.VISITOR, Role.FIELD_OPERATOR, Role.SUPERVISOR);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "lease_id");
            return id == null ? null : new RecordKey(LEASE, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String leaseId = FieldRecords.id(command, "lease_id");
            if (leaseId == null) {
                return missing("lease_id");
            }
            StoredRecord lease = find(store, LEASE, leaseId);
            if (lease == null) {
                return reject("NOT_FOUND", "There is no lease " + leaseId + ".");
            }
            if (!LEASE_ACTIVE.equals(state(lease))) {
                return reject("NOT_ACTIVE", "This lease already ended (" + state(lease) + ").");
            }
            if (!session.actorLabel().equals(lease.body().path("holder_session").asText())) {
                return reject("NOT_HOLDER", "Only the lease holder can release it.");
            }
            Long last = world.lease().filter(l -> l.leaseId().equals(leaseId))
                    .map(FieldWorld.LeaseInfo::lastSequence).orElse(null);
            StoredRecord mission = find(store, MISSION, lease.body().path("mission_id").asText(null));
            LeaseMoves moves = new LeaseMoves(world, store,
                    mission != null && MissionView.ACTIVE.contains(state(mission)) ? mission : null)
                    .also(FieldRecords.endLease(lease, FieldRecords.LEASE_RELEASED, "RELEASED", last), "LEASE_RELEASED")
                    .pilot(Pilot.NONE);
            if (world.lease().map(l -> l.leaseId().equals(leaseId)).orElse(false)) {
                world.freeze();
            }
            return moves.build();
        }
    }

    /** {@code end_lease} (automation only; internal): lease_id, reason EXPIRED. No fresh input for 2 s. */
    @Component
    @Profile("!stub")
    public static class EndLease implements CommandHandler {
        private final FieldWorld world;

        public EndLease(FieldWorld world) {
            this.world = world;
        }

        @Override
        public String commandType() {
            return "end_lease";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.AUTOMATION);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String leaseId = FieldRecords.id(command, "lease_id");
            StoredRecord lease = find(store, LEASE, leaseId);
            if (lease == null) {
                return missing("lease_id");
            }
            if (!"EXPIRED".equals(FieldRecords.text(command, "reason"))) {
                return Rejection.invalid("reason must be EXPIRED.");
            }
            if (!LEASE_ACTIVE.equals(state(lease))) {
                return reject("NOT_ACTIVE", "This lease already ended (" + state(lease) + ").");
            }
            Long last = world.lease().filter(l -> l.leaseId().equals(leaseId))
                    .map(FieldWorld.LeaseInfo::lastSequence).orElse(null);
            StoredRecord mission = find(store, MISSION, lease.body().path("mission_id").asText(null));
            return new LeaseMoves(world, store,
                    mission != null && MissionView.ACTIVE.contains(state(mission)) ? mission : null)
                    .also(FieldRecords.endLease(lease, FieldRecords.LEASE_EXPIRED, "EXPIRED", last), "LEASE_EXPIRED")
                    .pilot(Pilot.NONE)
                    .build();
        }
    }

    /**
     * {@code take_over} (supervisor or field_operator): vehicle_id. The drone stops first, then any visitor
     * or autopilot lease is revoked and the sender gets the lease. Taking over from the autopilot while the
     * mission is COLLECTING is refused (AGREEMENT section 7); stop_autopilot is always allowed.
     */
    @Component
    @Profile("!stub")
    public static class TakeOver implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;

        private final FieldPorts ports;

        public TakeOver(FieldWorld world, FieldTime time, FieldPorts ports) {
            this.world = world;
            this.time = time;
            this.ports = ports;
        }

        @Override
        public String commandType() {
            return "take_over";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.SUPERVISOR, Role.FIELD_OPERATOR);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String vehicleId = FieldRecords.id(command, "vehicle_id");
            if (vehicleId == null) {
                return missing("vehicle_id");
            }
            if (!vehicleId.equals(world.droneId())) {
                return reject("NOT_FOUND", "There is no vehicle " + vehicleId + ".");
            }
            StoredRecord mission = LeaseMoves.currentMission(world, store);
            if (mission == null) {
                return reject("NO_MISSION", "The drone has no approved mission to fly.");
            }
            Optional<FieldWorld.LeaseInfo> lease = world.lease();
            if (lease.isPresent() && lease.get().holder().equals(session.actorLabel())) {
                return reject("ALREADY_HOLDER", "You already hold the lease.");
            }
            if (lease.isPresent() && !lease.get().person() && MissionView.COLLECTING.equals(state(mission))) {
                return reject("COLLECTING", "The autopilot is holding still while the sample is collected. "
                        + "Stop the autopilot first if you must take control now.");
            }
            Rejection authority = initialDeparture(command, mission, ports);
            if (authority != null) return authority;
            LeaseMoves moves = new LeaseMoves(world, store, mission)
                    .endCurrent(FieldRecords.LEASE_REVOKED, "TAKEN_OVER")
                    .grant(session.actorLabel(), Pilot.OPERATOR, "MISSION", time.now());
            world.freeze();
            return moves.build();
        }
    }
}
