package fuelprint.science.detect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import fuelprint.api.Detector;
import fuelprint.api.DetectorInput;
import fuelprint.api.Interval;
import fuelprint.api.Reading;
import fuelprint.api.Trigger;
import fuelprint.api.Window;
import fuelprint.science.rank.Gas;
import fuelprint.science.rank.NoiseModel;
import fuelprint.science.rank.WorldProfile;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TEACH-DETECT-1, the fixed alarm rule (AGREEMENT section 11, AI-6 never replaces it).
 *
 * <ul>
 * <li>Only SO2 can trigger. CO2 is ignored by this rule.</li>
 * <li>A background is 30 consecutive valid 1 s values of the same channel (flat: the two halves differ by at
 * most max(12.5 ppb, 1.5 SD)), no older than 180 simulation seconds. Without one the answer is {@link DetectionStatus#BACKGROUND_REQUIRED}.</li>
 * <li>An enhancement is a valid second at least {@code max(25 ppb, 3 x standard uncertainty)} above the
 * background mean. The standard uncertainty is the background SD times sqrt(1 + 1/30).</li>
 * <li>15 enhancements in a row trigger. An invalid, missing, stale-flow or calibration-failed second resets
 * the count and the background buffer.</li>
 * </ul>
 *
 * <p>The background is refreshed at most every 30 s from a quiet window (it may not sit more than one SD above
 * the current background), so a rising plume cannot be absorbed into it. Seconds are read at whole simulation
 * seconds; the detector keeps its own history, so the core may hand it only the newest readings.
 * Ranking receives separate non-overlapping 30-second means of valid data after a background is available;
 * these are not conditioned on the alarm threshold. Their uncertainty uses the declared synthetic AR(1)
 * electrical allocation. Shared-background covariance is still a limitation of the current ranker.
 */
@Component
@Profile("!stub")
public class TeachDetector implements Detector {

    public static final String CHANNEL = "SO2_ppb";
    public static final int BACKGROUND_SECONDS = 30;
    public static final int TRIGGER_SECONDS = 15;
    public static final double BACKGROUND_MAX_AGE_S = 180.0;
    public static final double FLOOR_PPB = 25.0;
    public static final double UNCERTAINTY_FACTOR = 3.0;

    private static final double SECOND_TOLERANCE = 1e-6;
    private static final double WINDOW_UNCERTAINTY = NoiseModel.enhancementStd(WorldProfile.load(), Gas.SO2);

    private static final class Background {
        final double mean;
        final double sd;
        final long startS;
        final long endS;
        final String windowId;

        Background(double mean, double sd, long startS, long endS, String windowId) {
            this.mean = mean;
            this.sd = sd;
            this.startS = startS;
            this.endS = endS;
            this.windowId = windowId;
        }

        double uncertainty() {
            return sd * Math.sqrt(1.0 + 1.0 / BACKGROUND_SECONDS);
        }

        double threshold() {
            return Math.max(FLOOR_PPB, UNCERTAINTY_FACTOR * uncertainty());
        }
    }

    private static final class SensorState {
        long lastSecond = Long.MIN_VALUE;
        final Deque<Double> buffer = new ArrayDeque<>();
        Background background;
        long lastRefreshEnd = Long.MIN_VALUE;
        int count;
        long runStart;
        double runSum;
        boolean triggered;
        int averagingCount;
        double averagingSum;
        Background averagingBackground;
    }

    private String runId;
    private DetectorInput pendingInput;
    private DetectionResult pendingEvaluation;
    private final Map<String, SensorState> sensors = new LinkedHashMap<>();

    /** Forgets everything (a new run). */
    public synchronized void reset() {
        runId = null;
        pendingInput = null;
        pendingEvaluation = null;
        sensors.clear();
    }

    @Override
    public synchronized List<Window> windows(DetectorInput input) {
        if (input.equals(pendingInput)) return List.of();
        pendingEvaluation = evaluateDetailed(input);
        pendingInput = input;
        return pendingEvaluation.windows();
    }

    @Override
    public synchronized Optional<Trigger> evaluate(DetectorInput input) {
        if (input.equals(pendingInput) && pendingEvaluation != null) {
            DetectionResult result = pendingEvaluation;
            pendingEvaluation = null;
            return result.firstTrigger();
        }
        pendingEvaluation = null;
        return evaluateDetailed(input).firstTrigger();
    }

    /** Like {@link #evaluate} but also gives the status and the windows to save. */
    public synchronized DetectionResult evaluateDetailed(DetectorInput input) {
        if (!input.runId().equals(runId)) {
            sensors.clear();
            runId = input.runId();
        }
        List<Reading> seconds = new ArrayList<>();
        for (Reading r : input.readings()) {
            if (CHANNEL.equals(r.channel()) && isWholeSecond(r.simTimeS())) {
                seconds.add(r);
            }
        }
        seconds.sort(Comparator.comparingDouble(Reading::simTimeS));
        List<Trigger> triggers = new ArrayList<>();
        List<Window> windows = new ArrayList<>();
        for (Reading r : seconds) {
            SensorState st = sensors.computeIfAbsent(r.sensorOrVehicleId(), k -> new SensorState());
            long s = Math.round(r.simTimeS());
            if (s <= st.lastSecond) {
                continue; // already processed (the core may resend a rolling buffer)
            }
            if (st.lastSecond != Long.MIN_VALUE && s > st.lastSecond + 1) {
                resetCounting(st); // a missing second is a gap
            }
            st.lastSecond = s;
            process(r, s, st, triggers, windows);
        }
        Map<String, DetectionStatus> by = new LinkedHashMap<>();
        for (Map.Entry<String, SensorState> e : sensors.entrySet()) {
            by.put(e.getKey(), statusOf(e.getValue()));
        }
        return new DetectionResult(overall(by, !triggers.isEmpty()), by, triggers, windows);
    }

    private static boolean isWholeSecond(double t) {
        return Math.abs(t - Math.rint(t)) < SECOND_TOLERANCE;
    }

    private static DetectionStatus statusOf(SensorState st) {
        if (st.background == null) {
            return DetectionStatus.BACKGROUND_REQUIRED;
        }
        if (st.count > 0) {
            return DetectionStatus.ENHANCING;
        }
        return DetectionStatus.MONITORING;
    }

    private static DetectionStatus overall(Map<String, DetectionStatus> by, boolean triggered) {
        if (triggered) {
            return DetectionStatus.TRIGGERED;
        }
        if (by.containsValue(DetectionStatus.ENHANCING)) {
            return DetectionStatus.ENHANCING;
        }
        if (by.containsValue(DetectionStatus.MONITORING)) {
            return DetectionStatus.MONITORING;
        }
        return DetectionStatus.BACKGROUND_REQUIRED;
    }

    private static void resetCounting(SensorState st) {
        st.count = 0;
        st.runSum = 0;
        st.triggered = false;
        st.buffer.clear();
        st.averagingCount = 0;
        st.averagingSum = 0;
        st.averagingBackground = null;
    }

    private static boolean valid(Reading r) {
        return r.value() != null && Double.isFinite(r.value()) && r.quality().size() == 1 && "OK".equals(r.quality().get(0));
    }

    private void process(Reading r, long s, SensorState st, List<Trigger> triggers, List<Window> windows) {
        if (!valid(r)) {
            resetCounting(st);
            return;
        }
        double v = r.value();
        if (st.background != null && s - st.background.endS > BACKGROUND_MAX_AGE_S) {
            st.background = null; // too old: a fresh 30 s window is required
            resetCounting(st);
        }
        st.buffer.addLast(v);
        while (st.buffer.size() > BACKGROUND_SECONDS) {
            st.buffer.removeFirst();
        }
        Background bg = st.background;
        if (bg != null) {
            // One-second readings are right endpoints: samples 31..60 cover (30,60].
            // Average all valid values, independently of the 15-second alarm streak.
            if (st.averagingCount == 0) st.averagingBackground = bg;
            st.averagingCount++;
            st.averagingSum += v;
            if (st.averagingCount == BACKGROUND_SECONDS) {
                Background reference = st.averagingBackground;
                windows.add(new Window(newWindowId(),
                        r.sensorOrVehicleId(), CHANNEL, s - BACKGROUND_SECONDS, s, BACKGROUND_SECONDS,
                        st.averagingSum / BACKGROUND_SECONDS - reference.mean, WINDOW_UNCERTAINTY,
                        reference.windowId));
                st.averagingCount = 0;
                st.averagingSum = 0;
                st.averagingBackground = null;
            }
            double e = v - bg.mean;
            if (e >= bg.threshold()) {
                if (st.count == 0) {
                    st.runStart = s;
                    st.runSum = 0;
                }
                st.count++;
                st.runSum += e;
                if (st.count >= TRIGGER_SECONDS && !st.triggered) {
                    st.triggered = true;
                    String sensorId = r.sensorOrVehicleId();
                    triggers.add(new Trigger(CHANNEL, sensorId, List.of(new Interval(st.runStart, s)),
                            bg.windowId, Trigger.TEACH_DETECT_1));
                }
            } else {
                st.count = 0;
                st.runSum = 0;
                st.triggered = false;
            }
        }
        if (st.count == 0 && st.buffer.size() == BACKGROUND_SECONDS) {
            maybeRefreshBackground(r.sensorOrVehicleId(), s, st, windows);
        }
    }

    private void maybeRefreshBackground(String sensorId, long s, SensorState st, List<Window> windows) {
        if (st.background != null && s - st.lastRefreshEnd < BACKGROUND_SECONDS) {
            return;
        }
        double sum = 0;
        for (double x : st.buffer) {
            sum += x;
        }
        double mean = sum / st.buffer.size();
        double ss = 0;
        for (double x : st.buffer) {
            ss += (x - mean) * (x - mean);
        }
        double sd = Math.sqrt(ss / (st.buffer.size() - 1));
        // A background must be flat: a ramp (smoke arriving) is not a background, even if nothing else exists.
        double firstHalf = 0;
        double secondHalf = 0;
        int i = 0;
        for (double x : st.buffer) {
            if (i++ < BACKGROUND_SECONDS / 2) {
                firstHalf += x;
            } else {
                secondHalf += x;
            }
        }
        double drift = Math.abs(secondHalf - firstHalf) / (BACKGROUND_SECONDS / 2);
        if (drift > Math.max(FLOOR_PPB / 2, 1.5 * sd)) {
            st.lastRefreshEnd = s - BACKGROUND_SECONDS + 5; // try again in 5 s
            return;
        }
        if (st.background != null && mean - st.background.mean > st.background.sd) {
            st.lastRefreshEnd = s; // looks like a rise, not a background: keep the old one
            return;
        }
        String id = newWindowId();
        st.background = new Background(mean, sd, s - BACKGROUND_SECONDS, s, id);
        st.lastRefreshEnd = s;
        windows.add(new Window(id, sensorId, CHANNEL, s - BACKGROUND_SECONDS, s, BACKGROUND_SECONDS, null,
                WINDOW_UNCERTAINTY / Math.sqrt(2), null));
    }

    private static String newWindowId() {
        return UUID.randomUUID().toString();
    }

    /** Visible for tests: the background mean now for a sensor, if any. */
    public synchronized Optional<Double> backgroundMean(String sensorId) {
        SensorState st = sensors.get(sensorId);
        return st == null || st.background == null ? Optional.empty() : Optional.of(st.background.mean);
    }

    /** Visible for tests. */
    Map<String, Integer> counts() {
        Map<String, Integer> m = new HashMap<>();
        sensors.forEach((k, v) -> m.put(k, v.count));
        return m;
    }
}
