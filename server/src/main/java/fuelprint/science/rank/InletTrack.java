package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.Window;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The recorded public inlet history behind one window: where the inlet actually was, sample by sample.
 * Positions between samples are linearly interpolated; before the first sample the first position holds.
 */
public record InletTrack(List<Sample> samples) {

    public record Sample(double simTimeS, Enu inlet) {
    }

    public InletTrack {
        samples = samples.stream().sorted(Comparator.comparingDouble(Sample::simTimeS)).toList();
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("An inlet track needs at least one sample");
        }
    }

    /** A track that never moves (a fixed node, or a hovering vehicle). */
    public static InletTrack fixed(Enu inlet) {
        return new InletTrack(List.of(new Sample(0.0, inlet)));
    }

    public boolean stationary() {
        Enu first = samples.get(0).inlet();
        return samples.stream().allMatch(s -> s.inlet().equals(first));
    }

    public Enu at(double t) {
        if (t <= samples.get(0).simTimeS()) {
            return samples.get(0).inlet();
        }
        for (int i = 1; i < samples.size(); i++) {
            Sample b = samples.get(i);
            if (t <= b.simTimeS()) {
                Sample a = samples.get(i - 1);
                double f = (t - a.simTimeS()) / (b.simTimeS() - a.simTimeS());
                return new Enu(a.inlet().east() + f * (b.inlet().east() - a.inlet().east()),
                        a.inlet().north() + f * (b.inlet().north() - a.inlet().north()),
                        a.inlet().up() + f * (b.inlet().up() - a.inlet().up()));
            }
        }
        return samples.get(samples.size() - 1).inlet();
    }

    /**
     * Where the public inlet history of a window comes from. The ranker asks every registered source in order; the
     * fixed nodes N1 to N4 are always known from the world registry. Vehicle windows need a source that reads the
     * recorded vehicle poses (for example from the field attempt); without one they are excluded, never guessed.
     */
    public interface Source {
        Optional<InletTrack> track(String runId, Window window);
    }

    /** Fixed nodes from the world registry. */
    public static Source fixedNodes(WorldProfile world) {
        Map<String, Enu> nodes = world.nodeInlets();
        return (runId, window) -> Optional.ofNullable(nodes.get(window.sensorOrVehicleId())).map(InletTrack::fixed);
    }

    /** Known window id to fixed inlet position (tests, calibration, hovering vehicle captures). */
    public static Source byWindowId(Map<String, Enu> positions) {
        Map<String, Enu> copy = Map.copyOf(positions);
        return (runId, window) -> Optional.ofNullable(copy.get(window.id())).map(InletTrack::fixed);
    }

    /** Tries each source in order. */
    public static Source firstOf(List<Source> sources) {
        List<Source> copy = List.copyOf(sources);
        return (runId, window) -> {
            for (Source s : copy) {
                Optional<InletTrack> t = s.track(runId, window);
                if (t.isPresent()) {
                    return t;
                }
            }
            return Optional.empty();
        };
    }
}
