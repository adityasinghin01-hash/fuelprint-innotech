package fuelprint.field.command;

import static fuelprint.field.command.FieldRecords.ATTEMPT;
import static fuelprint.field.command.FieldRecords.MISSION;
import static fuelprint.field.command.FieldRecords.missing;
import static fuelprint.field.command.FieldRecords.reject;
import static fuelprint.field.command.FieldRecords.state;

import java.util.Optional;
import java.util.Set;

import fuelprint.api.AutomationSettings;
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
import fuelprint.field.FieldPorts;
import fuelprint.field.FieldTime;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The autopilot (AI-7) and the return home (AGREEMENT sections 5 and 7). */
public final class AutopilotHandlers {

    private AutopilotHandlers() {
    }

    /**
     * {@code start_autopilot} (supervisor, field_operator or automation): mission_id, vehicle_id, and for
     * automation an optional {@code resume: true}. Subject: the mission. The mission is APPROVED or later and
     * not COMPLETE; no person holds the lease. Automation may start it only if {@code auto_autopilot} is on and
     * nobody piloted for {@code autopilot_wait_s}, or resume it (after a person's lease ended) when the autopilot
     * is still enabled for the mission and nobody piloted for 10 s.
     */
    @Component
    @Profile("!stub")
    public static class StartAutopilot implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;
        private final FieldPorts ports;

        public StartAutopilot(FieldWorld world, FieldTime time, FieldPorts ports) {
            this.world = world;
            this.time = time;
            this.ports = ports;
        }

        @Override
        public String commandType() {
            return "start_autopilot";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.SUPERVISOR, Role.FIELD_OPERATOR, Role.AUTOMATION);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "mission_id");
            return id == null ? null : new RecordKey(MISSION, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            Object found = LeaseHandlers.currentMission(command, store, world);
            if (found instanceof Rejection rejection) {
                return rejection;
            }
            StoredRecord mission = (StoredRecord) found;
            Optional<FieldWorld.LeaseInfo> lease = world.lease().filter(l -> !l.ending());
            if (lease.isPresent()) {
                return lease.get().person()
                        ? reject("LEASE_HELD", lease.get().holder() + " is flying; the autopilot starts only when "
                        + "nobody is.")
                        : reject("ALREADY_ACTIVE", "The autopilot is already flying.");
            }
            if (session.isAutomation()) {
                boolean resume = command.payload().path("resume").asBoolean(false);
                double idle = world.noPilotSeconds();
                if (resume) {
                    if (!world.autopilotEnabledFor(mission.id())) {
                        return reject("NOT_ENABLED", "The autopilot was stopped for this mission; it does not resume.");
                    }
                    if (idle < FieldWorld.RESUME_AFTER_S) {
                        return reject("NOT_YET", "The autopilot resumes after 10 s with no pilot.");
                    }
                } else {
                    if (!ports.isOn(AutomationSettings.AUTO_AUTOPILOT)) {
                        return reject("AUTOMATION_OFF", "The automatic autopilot is switched off.");
                    }
                    if (idle < ports.autopilotWaitS()) {
                        return reject("NOT_YET", "The autopilot waits " + ports.autopilotWaitS()
                                + " s for a person to fly first.");
                    }
                }
            }
            MissionView view = MissionView.of(mission).orElse(null);
            if (view == null || !world.routeFlyable(view)) {
                return reject("NO_SAFE_ROUTE", "No safe path keeps 2 m from every building and stack.");
            }
            return new LeaseMoves(world, store, mission)
                    .endCurrent(FieldRecords.LEASE_EXPIRED, "EXPIRED")
                    .grant(FieldWorld.AUTOPILOT_HOLDER, Pilot.AUTOPILOT,
                            MissionView.RETURNING.equals(state(mission)) ? "RETURN" : "MISSION", time.now())
                    .build();
        }
    }

    /** {@code stop_autopilot} (supervisor or field_operator): vehicle_id. The drone stops first; it will not resume. */
    @Component
    @Profile("!stub")
    public static class StopAutopilot implements CommandHandler {
        private final FieldWorld world;

        public StopAutopilot(FieldWorld world) {
            this.world = world;
        }

        @Override
        public String commandType() {
            return "stop_autopilot";
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
            Optional<FieldWorld.LeaseInfo> lease = world.lease().filter(l -> !l.ending());
            if (lease.isEmpty() || lease.get().person()) {
                return reject("NO_AUTOPILOT", "The autopilot is not flying.");
            }
            StoredRecord mission = LeaseMoves.currentMission(world, store);
            LeaseMoves moves = new LeaseMoves(world, store, mission)
                    .endCurrent(FieldRecords.LEASE_REVOKED, "STOP_AUTOPILOT");
            world.freeze();
            return moves.build();
        }
    }

    /**
     * {@code return_to_depot} (automation, supervisor or field_operator): vehicle_id, reason BATTERY_RESERVE,
     * MISSION_DONE or OPERATOR. The vehicle is away from the depot. The server flies it home itself (an
     * autopilot lease with purpose RETURN; the person's lease is revoked, drone stopped first), except for
     * MISSION_DONE while a person is flying: they keep the lease and fly it home. The mission becomes RETURNING;
     * a capture still running is INTERRUPTED with the reason.
     */
    @Component
    @Profile("!stub")
    public static class ReturnToDepot implements CommandHandler {
        private final FieldWorld world;
        private final FieldTime time;

        public ReturnToDepot(FieldWorld world, FieldTime time) {
            this.world = world;
            this.time = time;
        }

        @Override
        public String commandType() {
            return "return_to_depot";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.AUTOMATION, Role.SUPERVISOR, Role.FIELD_OPERATOR);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String vehicleId = FieldRecords.id(command, "vehicle_id");
            String reason = FieldRecords.text(command, "reason");
            if (vehicleId == null) {
                return missing("vehicle_id");
            }
            if (reason == null || !FieldRecords.RETURN_REASONS.contains(reason)) {
                return Rejection.invalid("reason must be BATTERY_RESERVE, MISSION_DONE or OPERATOR.");
            }
            if (!vehicleId.equals(world.droneId())) {
                return reject("NOT_FOUND", "There is no vehicle " + vehicleId + ".");
            }
            if (world.landed()) {
                return reject("AT_DEPOT", "The drone is already at the depot.");
            }
            StoredRecord mission = LeaseMoves.currentMission(world, store);
            Optional<FieldWorld.LeaseInfo> lease = world.lease().filter(l -> !l.ending());
            boolean returning = mission != null && MissionView.RETURNING.equals(state(mission));
            boolean autopilot = lease.isPresent() && !lease.get().person();
            if (autopilot && (lease.get().homing() || returning)) {
                return reject("ALREADY_RETURNING", "The drone is already flying home by itself.");
            }
            boolean personKeeps = lease.isPresent() && lease.get().person() && "MISSION_DONE".equals(reason);
            LeaseMoves moves = new LeaseMoves(world, store, mission);
            if (!personKeeps && !autopilot) {
                moves.endCurrent(lease.isPresent() ? FieldRecords.LEASE_REVOKED : FieldRecords.LEASE_EXPIRED,
                                lease.isPresent() ? reason : "EXPIRED")
                        .grant(FieldWorld.AUTOPILOT_HOLDER, Pilot.AUTOPILOT, "RETURN", time.now());
            }
            if (mission != null && !MissionView.APPROVED.equals(state(mission))) {
                moves.missionState(MissionView.RETURNING);
            }
            if (mission != null) {
                StoredRecord running = CaptureHandlers.runningAttempt(store, mission);
                if (running != null) {
                    moves.also(CaptureHandlers.interrupted(running, world, reason), "ATTEMPT_INTERRUPTED");
                }
            }
            if (!personKeeps && !autopilot) {
                world.freeze();
            }
            return moves.build();
        }
    }
}
