package fuelprint.core;

import java.util.Locale;

import fuelprint.api.ServerMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code fp.mode}: {@code live} (default, the world loop runs) or {@code replay} (fake mode plays the recordings in
 * {@code classpath:replay/}). {@code fp.replay.automation=live} lets {@code AutomationBus} run in replay mode too.
 */
@Component
public class ServerModeConfig implements ServerMode {

    private final boolean replay;
    private final boolean automationInReplay;

    public ServerModeConfig(@Value("${fp.mode:live}") String mode,
                            @Value("${fp.replay.automation:recorded}") String replayAutomation) {
        String normalised = mode.trim().toLowerCase(Locale.ROOT);
        if (!normalised.equals("live") && !normalised.equals("replay")) {
            throw new IllegalArgumentException("fp.mode must be live or replay, not " + mode);
        }
        this.replay = normalised.equals("replay");
        this.automationInReplay = "live".equalsIgnoreCase(replayAutomation.trim());
    }

    @Override
    public boolean isReplay() {
        return replay;
    }

    @Override
    public boolean automationActive() {
        return !replay || automationInReplay;
    }
}
