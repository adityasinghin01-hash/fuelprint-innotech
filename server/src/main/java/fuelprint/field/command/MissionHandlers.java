package fuelprint.field.command;

import static fuelprint.field.command.FieldRecords.ATTEMPT;
import static fuelprint.field.command.FieldRecords.LEASE;
import static fuelprint.field.command.FieldRecords.LEASE_ACTIVE;
import static fuelprint.field.command.FieldRecords.MISSION;
import static fuelprint.field.command.FieldRecords.PAIRING;
import static fuelprint.field.command.FieldRecords.find;
import static fuelprint.field.command.FieldRecords.missing;
import static fuelprint.field.command.FieldRecords.reject;
import static fuelprint.field.command.FieldRecords.state;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import fuelprint.api.Pilot;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Rejection;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordKey;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.world.FieldWorld;
import fuelprint.field.world.MissionView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mission movement states written by field (AGREEMENT section 8) and the end of a run. */
public final class MissionHandlers {

    /** The movement steps field makes by position: from state to the next state. */
    static final Map<String, String> STEPS = Map.of(
            MissionView.LEASED, MissionView.OUTBOUND,
            MissionView.OUTBOUND, MissionView.AT_TARGET,
            MissionView.RETURNING, MissionView.COMPLETE);

    private MissionHandlers() {
    }

    /**
     * {@code advance_mission} (automation only; internal): mission_id, from_state, to_state. LEASED to OUTBOUND
     * when the drone leaves the depot, OUTBOUND to AT_TARGET within 5 m of the target and inside the height
     * band, RETURNING to COMPLETE when it is down on the depot pad (the lease is then released).
     */
    @Component
    @Profile("!stub")
    public static class AdvanceMission implements CommandHandler {
        private final FieldWorld world;

        public AdvanceMission(FieldWorld world) {
            this.world = world;
        }

        @Override
        public String commandType() {
            return "advance_mission";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.AUTOMATION);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String id = FieldRecords.id(command, "mission_id");
            return id == null ? null : new RecordKey(MISSION, id);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            StoredRecord mission = find(store, MISSION, FieldRecords.id(command, "mission_id"));
            if (mission == null) {
                return missing("mission_id");
            }
            String from = FieldRecords.text(command, "from_state");
            String to = FieldRecords.text(command, "to_state");
            if (from == null || to == null || !to.equals(STEPS.get(from))) {
                return Rejection.invalid("Field moves a mission only LEASED to OUTBOUND, OUTBOUND to AT_TARGET or "
                        + "RETURNING to COMPLETE.");
            }
            if (!from.equals(state(mission))) {
                return reject("WRONG_STATE", "The mission is " + state(mission) + ", not " + from + ".");
            }
            MissionView view = MissionView.of(mission).orElse(null);
            boolean holds = switch (to) {
                case MissionView.OUTBOUND -> !world.landed();
                case MissionView.AT_TARGET -> view != null && world.inPosition(view);
                default -> world.landed();
            };
            if (!holds) {
                return reject("NOT_THERE", "The drone is not where " + to + " needs it to be.");
            }
            LeaseMoves moves = new LeaseMoves(world, store, mission).missionState(to);
            if (MissionView.COMPLETE.equals(to)) {
                moves.endCurrent(FieldRecords.LEASE_RELEASED, "MISSION_COMPLETE").pilot(Pilot.NONE);
            }
            return moves.build();
        }
    }

    /**
     * {@code interrupt_field} (automation only; internal): run_id, reason. Sent after {@code RunEnded}: every
     * open mission of the run becomes INTERRUPTED, every active lease REVOKED, a running attempt INTERRUPTED
     * and an unconfirmed pairing EXPIRED. The world already stopped the drone when the run ended.
     */
    @Component
    @Profile("!stub")
    public static class InterruptField implements CommandHandler {
        private final FieldWorld world;

        public InterruptField(FieldWorld world) {
            this.world = world;
        }

        @Override
        public String commandType() {
            return "interrupt_field";
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.AUTOMATION);
        }

        /** True if run {@code runId} has anything this command would close. */
        public static boolean hasOpenRecords(StoreView store, String runId) {
            return store.list(MISSION, runId).stream().anyMatch(m -> !MissionView.TERMINAL.contains(state(m)))
                    || store.list(LEASE, runId).stream().anyMatch(l -> LEASE_ACTIVE.equals(state(l)))
                    || store.list(ATTEMPT, runId).stream().anyMatch(a -> "COLLECTING".equals(state(a)))
                    || store.list(PAIRING, runId).stream()
                    .anyMatch(p -> "CREATED".equals(state(p)) || "REDEEMED".equals(state(p)));
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            String runId = FieldRecords.id(command, "run_id");
            if (runId == null) {
                return missing("run_id");
            }
            String reason = Optional.ofNullable(FieldRecords.text(command, "reason")).orElse("RUN_ENDED");
            String why = "RUN_" + reason;
            HandlerResult.Builder result = HandlerResult.builder();
            for (StoredRecord mission : store.list(MISSION, runId)) {
                if (!MissionView.TERMINAL.contains(state(mission))) {
                    result.save(FieldRecords.mission(mission, MissionView.INTERRUPTED, Pilot.NONE),
                            "MISSION_INTERRUPTED", null);
                }
            }
            for (StoredRecord lease : store.list(LEASE, runId)) {
                if (LEASE_ACTIVE.equals(state(lease))) {
                    Long last = lease.body().path("last_sequence").isNumber()
                            ? lease.body().get("last_sequence").asLong() : null;
                    result.save(FieldRecords.endLease(lease, FieldRecords.LEASE_REVOKED, why, last),
                            "LEASE_REVOKED", null);
                }
            }
            for (StoredRecord attempt : store.list(ATTEMPT, runId)) {
                if ("COLLECTING".equals(state(attempt))) {
                    result.save(CaptureHandlers.interrupted(attempt, world, why), "ATTEMPT_INTERRUPTED", null);
                }
            }
            for (StoredRecord pairing : store.list(PAIRING, runId)) {
                if ("CREATED".equals(state(pairing)) || "REDEEMED".equals(state(pairing))) {
                    result.save(PairingHandlers.next(pairing, "EXPIRED", null, null), "PAIRING_EXPIRED", null);
                }
            }
            return result.build();
        }
    }
}
