package fuelprint.science.sensor;

import java.util.ArrayList;
import java.util.List;

import fuelprint.api.CaptureInput;
import fuelprint.api.CaptureRules;
import fuelprint.api.ChannelStatus;
import fuelprint.api.ChannelVerdict;
import fuelprint.api.Reading;
import fuelprint.api.Stage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Pre-check of one capture channel for TEACH-NODE-ONLINE-2 (method-profiles.json). A person still reviews it.
 *
 * <ul>
 * <li>Settling: a SETTLE stage of at least 120 s whose modelled response error is at most 5 %
 * ({@code 10^(-t/T90)}).</li>
 * <li>Collection: 30 consecutive valid seconds. A valid second has readings with quality OK and the branch
 * flow within +-5 % of target. Disjoint good stretches are never joined.</li>
 * <li>Recovery (if a RECOVERY stage has ended): at least 120 s and modelled error at most 5 %, else LIMITED.
 * A capture that has no recovery stage yet is not failed for it.</li>
 * </ul>
 * Stage names read: SETTLE, COLLECT, RECOVERY (or RECOVER).
 */
@Component
@Profile("!stub")
public class TeachCaptureRules implements CaptureRules {

    public static final String METHOD = "TEACH-NODE-ONLINE-2";
    public static final double MIN_SETTLE_S = 120.0;
    public static final double MIN_RECOVERY_S = 120.0;
    public static final double MAX_ERROR = 0.05;
    public static final int MIN_VALID_SECONDS = 30;

    @Override
    public ChannelVerdict judge(CaptureInput in) {
        String channel = in.channel();
        double t90 = SensorField.CO2.equals(channel) ? 30.0 : 60.0;
        double targetFlow = SensorField.CO2.equals(channel) ? 0.3 : 0.5;
        String flowChannel = SensorField.CO2.equals(channel) ? SensorField.CO2_FLOW : SensorField.SO2_FLOW;
        List<String> reasons = new ArrayList<>();

        Stage collect = last(in.stages(), "COLLECT");
        if (collect == null) {
            return new ChannelVerdict(channel, null, null, ChannelStatus.REJECTED, List.of("NO_COLLECT_STAGE"));
        }
        // Settle
        Stage settle = last(in.stages(), "SETTLE");
        if (settle == null) {
            reasons.add("SETTLE_MISSING");
        } else {
            double end = settle.endS() != null ? settle.endS() : collect.startS();
            double dur = end - settle.startS();
            if (dur < MIN_SETTLE_S - 1e-9 || modelledError(dur, t90) > MAX_ERROR) {
                reasons.add("SETTLE_INCOMPLETE");
            }
        }
        // Collection seconds
        double start = collect.startS();
        double lastReading = start;
        for (Reading r : in.readings()) {
            lastReading = Math.max(lastReading, r.simTimeS());
        }
        double end = collect.endS() != null ? collect.endS() : lastReading;
        int bins = (int) Math.floor(end - start + 1e-9);
        boolean[] ok = new boolean[Math.max(bins, 0)];
        boolean[] seen = new boolean[ok.length];
        double[] flowSum = new double[ok.length];
        int[] flowN = new int[ok.length];
        boolean[] bad = new boolean[ok.length];
        boolean faultSeen = false;
        List<String> otherFlags = new ArrayList<>();
        for (Reading r : in.readings()) {
            int k = (int) Math.floor(r.simTimeS() - start + 1e-9);
            if (k < 0 || k >= ok.length) {
                continue;
            }
            if (channel.equals(r.channel())) {
                seen[k] = true;
                boolean q = r.value() != null && r.quality().size() == 1 && "OK".equals(r.quality().get(0));
                if (!q) {
                    bad[k] = true;
                    for (String f : r.quality()) {
                        if ("FLOW_FAULT".equals(f)) {
                            faultSeen = true;
                        } else if (!"OK".equals(f) && !otherFlags.contains(f)) {
                            otherFlags.add(f);
                        }
                    }
                    if (r.value() == null) {
                        bad[k] = true;
                    }
                }
            } else if (flowChannel.equals(r.channel()) && r.value() != null) {
                flowSum[k] += r.value();
                flowN[k]++;
                if (Math.abs(r.value() - targetFlow) > MAX_ERROR * targetFlow + 1e-12) {
                    bad[k] = true;
                    if (r.value() <= 0) {
                        faultSeen = true;
                    } else if (!otherFlags.contains("FLOW_OUT_OF_RANGE")) {
                        otherFlags.add("FLOW_OUT_OF_RANGE");
                    }
                }
            }
        }
        int best = 0;
        int bestStart = 0;
        int run = 0;
        for (int k = 0; k < ok.length; k++) {
            ok[k] = seen[k] && !bad[k];
            if (ok[k]) {
                run++;
                if (run > best) {
                    best = run;
                    bestStart = k - run + 1;
                }
            } else {
                run = 0;
            }
        }
        Double volume = null;
        if (best > 0) {
            boolean haveFlow = true;
            double v = 0;
            for (int k = bestStart; k < bestStart + best; k++) {
                if (flowN[k] == 0) {
                    haveFlow = false;
                    break;
                }
                v += flowSum[k] / flowN[k] / 60.0; // L per second
            }
            volume = haveFlow ? v : null;
        } else if (bins > 0) {
            volume = 0.0;
        }
        boolean someBad = false;
        for (int k = 0; k < ok.length; k++) {
            if (!ok[k] && (seen[k] || flowN[k] > 0 || collect.endS() != null)) {
                someBad = true;
            }
        }
        if (faultSeen) {
            reasons.add("FLOW_FAULT");
        }
        reasons.addAll(otherFlags);
        boolean inProgress = collect.endS() == null;
        if (best < MIN_VALID_SECONDS) {
            if (inProgress && !someBad) {
                reasons.add("COLLECTION_IN_PROGRESS");
            } else {
                reasons.add("FEWER_THAN_30_CONSECUTIVE_VALID_SECONDS");
            }
        }
        // Recovery
        Stage rec = last(in.stages(), "RECOVERY");
        if (rec == null) {
            rec = last(in.stages(), "RECOVER");
        }
        boolean recoveryShort = false;
        if (rec != null && rec.endS() != null) {
            double dur = rec.endS() - rec.startS();
            recoveryShort = dur < MIN_RECOVERY_S - 1e-9 || modelledError(dur, t90) > MAX_ERROR;
            if (recoveryShort) {
                reasons.add("RECOVERY_INCOMPLETE");
            }
        }
        ChannelStatus status;
        boolean hardFail = reasons.stream().anyMatch(x -> !x.equals("RECOVERY_INCOMPLETE")
                && !x.equals("COLLECTION_IN_PROGRESS"));
        if (hardFail) {
            status = ChannelStatus.REJECTED;
        } else if (reasons.isEmpty()) {
            status = ChannelStatus.VALID;
        } else {
            status = ChannelStatus.LIMITED;
        }
        return new ChannelVerdict(channel, (double) best, volume, status, reasons);
    }

    /** Modelled response error after {@code seconds} of a step: exp(-t/tau) = 10^(-t/T90). */
    public static double modelledError(double seconds, double t90S) {
        return Math.pow(10.0, -seconds / t90S);
    }

    private static Stage last(List<Stage> stages, String name) {
        Stage found = null;
        for (Stage s : stages) {
            if (name.equals(s.stage())) {
                found = s;
            }
        }
        return found;
    }
}
