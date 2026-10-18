package fuelprint.core.run;

import java.util.Optional;

import fuelprint.api.ClockControl;
import fuelprint.api.RunContext;
import fuelprint.api.ServerMode;
import fuelprint.core.replay.ReplayEngine;
import fuelprint.core.world.WorldLoop;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Picks the run driver and the clock by {@code fp.mode}: the world loop when live, the replay engine in fake mode.
 * The primary beans are small delegates (not the engines themselves), so each type has exactly one primary.
 */
@Configuration
public class RunDriverConfig {

    @Bean
    @Primary
    public ModeRunDriver runDriver(ServerMode mode, WorldLoop world, ReplayEngine replay) {
        return new ModeRunDriver(mode.isReplay() ? replay : world);
    }

    @Bean
    @Primary
    public ModeClock clockControl(ServerMode mode, WorldLoop world, ReplayEngine replay) {
        return new ModeClock(mode.isReplay() ? replay : world);
    }

    /** The {@link RunDriver} of the current mode. */
    public static final class ModeRunDriver implements RunDriver {
        private final RunDriver target;

        ModeRunDriver(RunDriver target) {
            this.target = target;
        }

        @Override
        public Optional<String> readyFor(String presetId) {
            return target.readyFor(presetId);
        }

        @Override
        public void start(RunContext run, double simTimeS) {
            target.start(run, simTimeS);
        }

        @Override
        public void pause(String runId) {
            target.pause(runId);
        }

        @Override
        public void resume(String runId) {
            target.resume(runId);
        }

        @Override
        public void stop(String runId) {
            target.stop(runId);
        }

        @Override
        public boolean isLoaded(String runId) {
            return target.isLoaded(runId);
        }

        @Override
        public Optional<Double> simTimeS(String runId) {
            return target.simTimeS(runId);
        }
    }

    /** The {@link ClockControl} of the current mode (what other packages inject). */
    public static final class ModeClock implements ClockControl {
        private final ClockControl target;

        ModeClock(ClockControl target) {
            this.target = target;
        }

        @Override
        public double simTimeS() {
            return target.simTimeS();
        }

        @Override
        public double timeScale() {
            return target.timeScale();
        }

        @Override
        public void requestTimeScale(String requester, double scale) {
            target.requestTimeScale(requester, scale);
        }

        @Override
        public void releaseTimeScale(String requester) {
            target.releaseTimeScale(requester);
        }
    }
}
