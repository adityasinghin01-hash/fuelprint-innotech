package fuelprint.core.run;

import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.core.Ids;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The run commands of AGREEMENT section 5 (role presenter): {@code create_run}, {@code pause_run},
 * {@code resume_run}, {@code reset_run}. The handlers only decide and return records; {@link RunLifecycle} moves
 * the world after the commit. At most one run is active (CREATED, RUNNING or PAUSED) at a time.
 */
public final class RunCommands {

    public static final String CREATE = "create_run";
    public static final String PAUSE = "pause_run";
    public static final String RESUME = "resume_run";
    public static final String RESET = "reset_run";
    public static final Set<String> ALL = Set.of(CREATE, PAUSE, RESUME, RESET);

    /** Run is in the wrong state for this command. */
    public static final String WRONG_STATE = "WRONG_STATE";
    /** Another run is active; reset it instead. */
    public static final String RUN_ACTIVE = "RUN_ACTIVE";
    /** The world (or the recording) for this preset is not available. */
    public static final String WORLD_NOT_READY = "WORLD_NOT_READY";
    /** No such run. */
    public static final String NOT_FOUND = "NOT_FOUND";

    private RunCommands() {
    }

    /** Shared checks of preset and mode; null if fine. */
    static Rejection presetProblem(JsonNode payload, RunDriver driver) {
        String presetId = payload.path("preset_id").asText(null);
        Optional<Presets.Preset> preset = Presets.find(presetId);
        if (preset.isEmpty()) {
            return Rejection.invalid("preset_id must be one of SC1-NIGHT-PLUME, SC2-BLOCKED-INLET, SC3-ABOVE-THE-BOXES.");
        }
        String mode = payload.path("mode").asText(null);
        if (!Presets.MODES.contains(mode)) {
            return Rejection.invalid("mode must be BLIND or TEACHING.");
        }
        Optional<String> notReady = driver.readyFor(presetId);
        return notReady.map(text -> new Rejection(WORLD_NOT_READY, text)).orElse(null);
    }

    /** The run named by {@code payload.run_id}, or by the command's run_id. */
    static String runIdOf(Command command) {
        String fromPayload = command.payload().path("run_id").asText(null);
        return fromPayload != null ? fromPayload : command.runId();
    }

    /** {@code create_run}: a NEW run in state RUNNING with its four sensors; never reuses a run. */
    @Component
    public static class CreateRun implements CommandHandler {
        private final Runs runs;
        private final ObjectProvider<RunDriver> drivers;

        public CreateRun(Runs runs, ObjectProvider<RunDriver> drivers) {
            this.runs = runs;
            this.drivers = drivers;
        }

        @Override
        public String commandType() {
            return CREATE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.PRESENTER);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            RunDriver driver = drivers.getObject();
            Rejection problem = presetProblem(command.payload(), driver);
            if (problem != null) {
                return problem;
            }
            Optional<StoredRecord> active = Runs.active(store.listAll(Runs.RUN));
            if (active.isPresent()) {
                return new Rejection(RUN_ACTIVE, "A run is already " + Runs.state(active.get())
                        + ". Use reset_run to replace it with a new run.");
            }
            Presets.Preset preset = Presets.find(command.payload().path("preset_id").asText()).orElseThrow();
            return runs.newRun(HandlerResult.builder(), Ids.newId(), preset, command.payload().path("mode").asText(),
                    null).build();
        }
    }

    /** {@code reset_run}: the active run (if any) becomes ABORTED with reason RESET; a new run starts. */
    @Component
    public static class ResetRun implements CommandHandler {
        private final Runs runs;
        private final ObjectProvider<RunDriver> drivers;

        public ResetRun(Runs runs, ObjectProvider<RunDriver> drivers) {
            this.runs = runs;
            this.drivers = drivers;
        }

        @Override
        public String commandType() {
            return RESET;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.PRESENTER);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            RunDriver driver = drivers.getObject();
            Rejection problem = presetProblem(command.payload(), driver);
            if (problem != null) {
                return problem;
            }
            Presets.Preset preset = Presets.find(command.payload().path("preset_id").asText()).orElseThrow();
            HandlerResult.Builder builder = HandlerResult.builder();
            Optional<StoredRecord> active = Runs.active(store.listAll(Runs.RUN));
            String parent = null;
            if (active.isPresent()) {
                StoredRecord old = active.get();
                double sim = driver.simTimeS(old.id()).orElse(Runs.simTime(old));
                builder.save(Runs.changeState(old, Runs.ABORTED, sim, "RESET"), "RUN_RESET", sim);
                parent = old.id();
            }
            return runs.newRun(builder, Ids.newId(), preset, command.payload().path("mode").asText(), parent).build();
        }
    }

    /** {@code pause_run}: RUNNING to PAUSED. */
    @Component
    public static class PauseRun implements CommandHandler {
        private final ObjectProvider<RunDriver> drivers;

        public PauseRun(ObjectProvider<RunDriver> drivers) {
            this.drivers = drivers;
        }

        @Override
        public String commandType() {
            return PAUSE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.PRESENTER);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String runId = runIdOf(command);
            return Ids.isUuid(runId) ? new RecordKey(Runs.RUN, runId) : null;
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            RunDriver driver = drivers.getObject();
            String runId = runIdOf(command);
            Optional<StoredRecord> run = Ids.isUuid(runId) ? store.get(Runs.RUN, runId) : Optional.empty();
            if (run.isEmpty()) {
                return new Rejection(NOT_FOUND, "There is no run with that run_id.");
            }
            if (!Runs.RUNNING.equals(Runs.state(run.get()))) {
                return new Rejection(WRONG_STATE, "Only a RUNNING run can be paused; this one is "
                        + Runs.state(run.get()) + ".");
            }
            double sim = driver.simTimeS(runId).orElse(Runs.simTime(run.get()));
            return HandlerResult.builder().save(Runs.changeState(run.get(), Runs.PAUSED, sim, null), "RUN_PAUSED", sim)
                    .build();
        }
    }

    /** {@code resume_run}: PAUSED to RUNNING (also after a restart marked the run INTERRUPTED). */
    @Component
    public static class ResumeRun implements CommandHandler {
        private final ObjectProvider<RunDriver> drivers;

        public ResumeRun(ObjectProvider<RunDriver> drivers) {
            this.drivers = drivers;
        }

        @Override
        public String commandType() {
            return RESUME;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.PRESENTER);
        }

        @Override
        public RecordKey revisionSubject(Command command) {
            String runId = runIdOf(command);
            return Ids.isUuid(runId) ? new RecordKey(Runs.RUN, runId) : null;
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            RunDriver driver = drivers.getObject();
            String runId = runIdOf(command);
            Optional<StoredRecord> run = Ids.isUuid(runId) ? store.get(Runs.RUN, runId) : Optional.empty();
            if (run.isEmpty()) {
                return new Rejection(NOT_FOUND, "There is no run with that run_id.");
            }
            if (!Runs.PAUSED.equals(Runs.state(run.get()))) {
                return new Rejection(WRONG_STATE, "Only a PAUSED run can be resumed; this one is "
                        + Runs.state(run.get()) + ".");
            }
            Optional<String> notReady = driver.readyFor(run.get().body().path("preset_id").asText());
            if (notReady.isPresent()) {
                return new Rejection(WORLD_NOT_READY, notReady.get());
            }
            double sim = driver.simTimeS(runId).orElse(Runs.simTime(run.get()));
            return HandlerResult.builder().save(Runs.changeState(run.get(), Runs.RUNNING, sim, null), "RUN_RESUMED", sim)
                    .build();
        }
    }
}
