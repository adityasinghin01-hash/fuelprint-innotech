package fuelprint.field;

import java.util.Optional;
import java.util.List;

import fuelprint.api.AutomationSettings;
import fuelprint.api.CaptureInput;
import fuelprint.api.CaptureRules;
import fuelprint.api.ChannelVerdict;
import fuelprint.api.ClockControl;
import fuelprint.api.Window;
import fuelprint.api.MissionAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The narrow adapter between field and the {@code fuelprint.api} beans other packages provide. Every one is
 * optional so the field package starts before core P3 and science exist:
 * <ul>
 *   <li>no {@link AutomationSettings}: every automatic step is treated as OFF (the safe side);</li>
 *   <li>no {@link ClockControl}: time scale 1, requests are dropped;</li>
 *   <li>no {@link CaptureRules}: the channel is REJECTED with {@code CAPTURE_RULES_UNAVAILABLE} by the caller;</li>
 *   <li>no {@link MissionAuthority}: initial departure is refused with {@code AUTHORITY_UNAVAILABLE}.</li>
 * </ul>
 * Never call these while holding the field world lock (the core may call field while holding its own).
 */
@Component
@Profile("!stub")
public class FieldPorts {

    private static final Logger log = LoggerFactory.getLogger(FieldPorts.class);
    static final int DEFAULT_AUTOPILOT_WAIT_S = 60;

    private final ObjectProvider<AutomationSettings> settings;
    private final ObjectProvider<ClockControl> clock;
    private final ObjectProvider<CaptureRules> rules;
    private final ObjectProvider<MissionAuthority> authority;

    public FieldPorts(ObjectProvider<AutomationSettings> settings, ObjectProvider<ClockControl> clock,
                      ObjectProvider<CaptureRules> rules, ObjectProvider<MissionAuthority> authority) {
        this.settings = settings;
        this.clock = clock;
        this.rules = rules;
        this.authority = authority;
    }

    /** Current initial-departure authority; missing, failed or empty validation always refuses. */
    public MissionAuthority.Decision initialDeparture(String runId, String missionId) {
        try {
            MissionAuthority current = authority.getIfAvailable();
            if (current != null) {
                MissionAuthority.Decision decision = current.checkInitialDeparture(runId, missionId);
                if (decision != null) return decision;
            }
        } catch (RuntimeException e) {
            log.warn("Mission authority validation unavailable; refusing initial departure ({})", e.getClass().getSimpleName());
        }
        return MissionAuthority.Decision.refuse("AUTHORITY_UNAVAILABLE",
                "Current mission approval could not be checked; initial departure is refused.");
    }

    /** True if the automatic step {@code setting} is on; false if there are no settings or they fail. */
    public boolean isOn(String setting) {
        AutomationSettings current = settings.getIfAvailable();
        if (current == null) {
            return false;
        }
        try {
            return current.isOn(setting);
        } catch (RuntimeException e) {
            log.warn("Automation settings failed for {}; treating it as off", setting, e);
            return false;
        }
    }

    /** Seconds the autopilot waits for a person (60 when there are no settings). */
    public int autopilotWaitS() {
        AutomationSettings current = settings.getIfAvailable();
        if (current == null) {
            return DEFAULT_AUTOPILOT_WAIT_S;
        }
        try {
            return current.autopilotWaitS();
        } catch (RuntimeException e) {
            log.warn("Automation settings failed for autopilot_wait_s; using {}", DEFAULT_AUTOPILOT_WAIT_S, e);
            return DEFAULT_AUTOPILOT_WAIT_S;
        }
    }

    /** The time scale now (1 without a clock control). */
    public double timeScale() {
        ClockControl control = clock.getIfAvailable();
        if (control == null) {
            return 1.0;
        }
        try {
            double scale = control.timeScale();
            return Double.isFinite(scale) && scale > 0 ? scale : 1.0;
        } catch (RuntimeException e) {
            log.warn("Clock control failed; assuming time scale 1", e);
            return 1.0;
        }
    }

    public void requestTimeScale(String requester, double scale) {
        ClockControl control = clock.getIfAvailable();
        if (control != null) {
            try {
                control.requestTimeScale(requester, scale);
            } catch (RuntimeException e) {
                log.warn("Clock control refused time scale {} for {}", scale, requester, e);
            }
        }
    }

    public void releaseTimeScale(String requester) {
        ClockControl control = clock.getIfAvailable();
        if (control != null) {
            try {
                control.releaseTimeScale(requester);
            } catch (RuntimeException e) {
                log.warn("Clock control failed to release the time scale for {}", requester, e);
            }
        }
    }

    /** The capture rules' verdict, or empty when there are no capture rules. Exceptions are passed on. */
    public Optional<ChannelVerdict> judge(CaptureInput input) {
        CaptureRules current = rules.getIfAvailable();
        return current == null ? Optional.empty() : Optional.ofNullable(current.judge(input));
    }
    /** Measured windows, or empty for an unavailable/default hook. Exceptions reach the review diagnostic. */
    public List<Window> windows(CaptureInput input) {
        CaptureRules current = rules.getIfAvailable();
        if (current == null) return List.of();
        List<Window> windows = current.windows(input);
        return windows == null ? List.of() : List.copyOf(windows);
    }

}
