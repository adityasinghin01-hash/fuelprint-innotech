package fuelprint.core.run;

import java.util.concurrent.TimeUnit;

import fuelprint.core.command.CommandBus;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * On start-up, a run left CREATED or RUNNING by a crash or kill is saved as PAUSED with reason INTERRUPTED
 * (event {@code RUN_INTERRUPTED}, so {@code RunEnded(INTERRUPTED)} is published after commit). Its clock is set to
 * the last saved reading. Nothing committed is touched; the presenter can resume it or reset.
 */
@Component
@Order(0)
public class RunRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunRecovery.class);

    private final RecordStore store;
    private final CommandBus bus;
    private final JdbcTemplate jdbc;

    public RunRecovery(RecordStore store, CommandBus bus, JdbcTemplate jdbc) {
        this.store = store;
        this.bus = bus;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (StoredRecord run : store.listAll(Runs.RUN)) {
            String state = Runs.state(run);
            if (!Runs.RUNNING.equals(state) && !Runs.CREATED.equals(state)) {
                continue;
            }
            Double lastReading = jdbc.queryForObject("SELECT MAX(json_extract(body, '$.sim_time_s')) FROM records "
                    + "WHERE type = 'reading' AND run_id = ?", Double.class, run.id());
            double sim = Math.max(Runs.simTime(run), lastReading == null ? 0 : lastReading);
            bus.saveWithoutCommand(Session.AUTOMATION_ACTOR, HandlerResult.builder()
                    .save(Runs.changeState(run, Runs.PAUSED, sim, "INTERRUPTED"), "RUN_INTERRUPTED", sim)
                    .build(), false).get(30, TimeUnit.SECONDS);
            log.warn("Run {} was still {} when the server stopped; it is now PAUSED (INTERRUPTED) at {} s",
                    run.id(), state, sim);
        }
    }
}
