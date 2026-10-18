package fuelprint.field.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import fuelprint.api.Reading;
import fuelprint.api.Stage;
import fuelprint.field.profile.CaptureMethod;
import fuelprint.field.profile.WorldProfile;

/**
 * The stages of one capture attempt (AGREEMENT section 6 {@code attempt}, field plan "A mission, step by
 * step" 7): SETUP 30, BACKGROUND 30, PURGE 30, SETTLE at least 120, COLLECT until every channel has 30
 * consecutive valid seconds (at most {@value #COLLECT_MAX_S} s), RECOVER at least 120, REVIEW 30.
 * Every time is simulation seconds, so at time scale 15 everything advances together.
 *
 * <p>SETTLE (and RECOVER) go on after their minimum while the newest reading of any channel carries
 * {@value #NOT_SETTLED} (or {@value #NOT_RECOVERED}), at most {@value #SETTLE_MAX_S} s: the settle model error
 * is the science engine's, and this is how its readings report it.
 *
 * <p>One reading per channel per simulated second is kept. A second is valid when the channel reading has a
 * finite value and only the {@code OK} flag, its branch flow reading is present, OK and within the method's
 * tolerance, and the drone is in position. A gap or fault ends a run of valid seconds; two runs are never
 * glued together. Readings taken out of position get the extra flag {@value #OUT_OF_POSITION}.
 * Not thread-safe; the world guards it.
 */
final class CaptureRun {

    static final double COLLECT_MAX_S = 60;
    static final double SETTLE_MAX_S = 600;
    static final String NOT_SETTLED = "NOT_SETTLED";
    static final String NOT_RECOVERED = "NOT_RECOVERED";
    static final String OUT_OF_POSITION = "OUT_OF_POSITION";
    static final String FLOW_UNIT = "L_min";

    enum Phase { SETUP, BACKGROUND, PURGE, SETTLE, COLLECT, RECOVER, REVIEW, DONE }

    final String attemptId;
    final String missionId;
    final String runId;
    final String methodId;
    final List<String> channels;
    private final CaptureMethod method;
    private final WorldProfile.Procedure procedure;

    private final List<double[]> stageTimes = new ArrayList<>();
    private final List<Phase> stagePhases = new ArrayList<>();
    private Phase phase = Phase.SETUP;
    private double phaseStart;
    private boolean collectEnded;
    private boolean extended;

    private final Map<String, Integer> lastSecond = new HashMap<>();
    private final Map<String, List<Reading>> readings = new LinkedHashMap<>();
    private final Map<String, Reading> latest = new HashMap<>();
    private final Map<String, Counter> counters = new LinkedHashMap<>();
    private int lastCollectSecond = Integer.MIN_VALUE;

    private static final class Counter {
        int run;
        int best;
        boolean done;
        final List<Double> runValues = new ArrayList<>();
        List<Double> bestValues = List.of();
        final List<Double> background = new ArrayList<>();
        final Set<String> badFlags = new LinkedHashSet<>();
    }

    CaptureRun(String attemptId, String missionId, String runId, String methodId, List<String> channels,
               double startS, CaptureMethod method, WorldProfile.Procedure procedure) {
        this.attemptId = attemptId;
        this.missionId = missionId;
        this.runId = runId;
        this.methodId = methodId;
        this.channels = List.copyOf(channels);
        this.method = method;
        this.procedure = procedure;
        this.phaseStart = startS;
        for (String channel : this.channels) {
            readings.put(channel, new ArrayList<>());
            counters.put(channel, new Counter());
            lastSecond.put(channel, Integer.MIN_VALUE);
        }
        stagePhases.add(Phase.SETUP);
        stageTimes.add(new double[] {startS, Double.NaN});
    }

    Phase phase() {
        return phase;
    }

    boolean done() {
        return phase == Phase.DONE;
    }

    /** Takes this tick's readings at the drone's inlet. */
    void accept(List<Reading> droneReadings, boolean inPosition) {
        if (phase == Phase.DONE) {
            return;
        }
        for (String channel : channels) {
            Reading reading = find(droneReadings, channel).orElse(null);
            if (reading == null) {
                continue;
            }
            int second = (int) Math.floor(reading.simTimeS() + 1e-6);
            if (second <= lastSecond.get(channel) || reading.simTimeS() < phaseStart - 1e-6 && phase == Phase.SETUP) {
                continue;
            }
            Counter counter = counters.get(channel);
            if (phase == Phase.COLLECT && lastSecond.get(channel) != Integer.MIN_VALUE
                    && second > lastSecond.get(channel) + 1 && !counter.done) {
                counter.run = 0;
                counter.runValues.clear();
                counter.badFlags.add("MISSING_SECOND");
            }
            lastSecond.put(channel, second);
            Reading flow = flowOf(droneReadings, channel).orElse(null);
            Reading kept = inPosition ? reading : flagged(reading, OUT_OF_POSITION);
            readings.get(channel).add(kept);
            if (flow != null) {
                readings.get(channel).add(flow);
            }
            latest.put(channel, reading);
            boolean valid = valid(kept, flow, channel);
            if (phase == Phase.BACKGROUND && valid) {
                counter.background.add(kept.value());
            }
            if (phase == Phase.COLLECT && reading.simTimeS() >= phaseStart - 1e-6 && !counter.done) {
                lastCollectSecond = Math.max(lastCollectSecond, second);
                if (valid) {
                    counter.run++;
                    counter.runValues.add(kept.value());
                    if (counter.run > counter.best) {
                        counter.best = counter.run;
                        counter.bestValues = List.copyOf(counter.runValues);
                    }
                    if (counter.run >= method.collectionValidS()) {
                        counter.done = true;
                    }
                } else {
                    counter.run = 0;
                    counter.runValues.clear();
                    counter.badFlags.addAll(problems(kept, flow, channel));
                }
            }
        }
    }

    /** Moves to the next stage(s) due at {@code simTimeS}. True if a stage changed. */
    boolean advance(double simTimeS) {
        boolean changed = false;
        while (phase != Phase.DONE) {
            Double end = endOfPhase(simTimeS);
            if (end == null) {
                break;
            }
            if (phase == Phase.COLLECT) {
                collectEnded = true;
            }
            stageTimes.get(stageTimes.size() - 1)[1] = end;
            phase = Phase.values()[phase.ordinal() + 1];
            phaseStart = end;
            extended = false;
            if (phase != Phase.DONE) {
                stagePhases.add(phase);
                stageTimes.add(new double[] {end, Double.NaN});
            }
            changed = true;
        }
        return changed;
    }

    private Double endOfPhase(double t) {
        double eps = 1e-6;
        return switch (phase) {
            case SETUP -> fixed(t, procedure.setupS());
            case BACKGROUND -> fixed(t, procedure.backgroundS());
            case PURGE -> fixed(t, procedure.purgeS());
            case SETTLE -> extendable(t, procedure.minimumSettleS(), NOT_SETTLED);
            case COLLECT -> {
                boolean all = counters.values().stream().allMatch(c -> c.done);
                if (all && !counters.isEmpty() && t >= lastCollectSecond + 1 - eps) {
                    yield Math.max(phaseStart, lastCollectSecond + 1.0);
                }
                yield t >= phaseStart + COLLECT_MAX_S - eps ? phaseStart + COLLECT_MAX_S : null;
            }
            case RECOVER -> extendable(t, procedure.minimumRecoveryS(), NOT_RECOVERED);
            case REVIEW -> fixed(t, procedure.reviewS());
            case DONE -> null;
        };
    }

    private Double fixed(double t, double duration) {
        return t >= phaseStart + duration - 1e-6 ? phaseStart + duration : null;
    }

    private Double extendable(double t, double minimum, String flag) {
        if (t < phaseStart + minimum - 1e-6) {
            return null;
        }
        if (t >= phaseStart + SETTLE_MAX_S - 1e-6) {
            return phaseStart + SETTLE_MAX_S;
        }
        boolean waiting = latest.values().stream().anyMatch(r -> r.quality().contains(flag));
        if (waiting) {
            extended = true;
            return null;
        }
        // Not extended: exactly the minimum. Extended: the whole second the flag cleared.
        return extended ? Math.max(phaseStart + minimum, Math.floor(t + 1e-6)) : phaseStart + minimum;
    }

    AttemptSnapshot snapshot() {
        List<Stage> stages = new ArrayList<>();
        for (int i = 0; i < stagePhases.size(); i++) {
            double[] times = stageTimes.get(i);
            stages.add(new Stage(stagePhases.get(i).name(), times[0], Double.isNaN(times[1]) ? null : times[1]));
        }
        Map<String, List<Reading>> copies = new LinkedHashMap<>();
        Map<String, AttemptSnapshot.ChannelCount> counts = new LinkedHashMap<>();
        for (String channel : channels) {
            copies.put(channel, List.copyOf(readings.get(channel)));
            counts.put(channel, count(channel));
        }
        return new AttemptSnapshot(attemptId, missionId, runId, methodId, channels, stages, collectEnded,
                phase == Phase.DONE, copies, counts);
    }

    private AttemptSnapshot.ChannelCount count(String channel) {
        Counter counter = counters.get(channel);
        int seconds = (int) Math.min(counter.best, method.collectionValidS());
        Double volume = method.flowFor(channel).map(flow -> seconds * flow / 60.0).orElse(null);
        Double enhancement = null;
        String note = null;
        if (counter.bestValues.isEmpty()) {
            note = "NO_VALID_COLLECT_SECONDS";
        } else if (counter.background.isEmpty()) {
            note = "NO_VALID_BACKGROUND_SECONDS";
        } else {
            enhancement = mean(counter.bestValues.subList(0, Math.min(seconds, counter.bestValues.size())))
                    - mean(counter.background);
        }
        return new AttemptSnapshot.ChannelCount(seconds, volume, enhancement, note, List.copyOf(counter.badFlags));
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private boolean valid(Reading reading, Reading flow, String channel) {
        return problems(reading, flow, channel).isEmpty();
    }

    private List<String> problems(Reading reading, Reading flow, String channel) {
        List<String> out = new ArrayList<>();
        if (reading.value() == null || !Double.isFinite(reading.value())) {
            out.add("NO_VALUE");
        }
        reading.quality().stream().filter(q -> !"OK".equals(q)).forEach(out::add);
        if (reading.quality().isEmpty()) {
            out.add("NO_QUALITY");
        }
        if (flow == null) {
            out.add("FLOW_UNKNOWN");
        } else {
            flow.quality().stream().filter(q -> !"OK".equals(q)).forEach(out::add);
            Optional<Double> nominal = method.flowFor(channel);
            if (flow.quality().isEmpty()) out.add("FLOW_QUALITY_UNKNOWN");
            if (flow.value() == null || !Double.isFinite(flow.value())) {
                out.add("FLOW_UNKNOWN");
            } else if (nominal.isPresent()
                    && Math.abs(flow.value() - nominal.get()) > nominal.get() * method.flowTolerancePercent() / 100.0) {
                out.add("FLOW_OUT_OF_TOLERANCE");
            }
        }
        return out;
    }

    private static Optional<Reading> find(List<Reading> list, String channel) {
        return list.stream().filter(r -> channel.equals(r.channel())).findFirst();
    }

    /** The branch flow reading for {@code channel}: unit {@code L_min} and a name starting with its gas, like {@code SO2_flow_L_min}. */
    static Optional<Reading> flowOf(List<Reading> list, String channel) {
        String prefix = CaptureMethod.gasOf(channel) + "_";
        return list.stream()
                .filter(r -> FLOW_UNIT.equals(r.unit()) && r.channel() != null && r.channel().startsWith(prefix)
                        && !r.channel().equals(channel))
                .findFirst();
    }

    private static Reading flagged(Reading r, String flag) {
        List<String> quality = new ArrayList<>(r.quality());
        quality.remove("OK");
        quality.add(flag);
        return new Reading(r.sensorOrVehicleId(), r.channel(), r.value(), r.rawValue(), r.unit(), r.simTimeS(),
                quality, r.origin());
    }
}
