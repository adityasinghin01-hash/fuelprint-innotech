package fuelprint.science.rank;

import fuelprint.api.Exclusion;
import fuelprint.api.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * FP-COMPAT-3 input selection: per instrument and channel, the latest at most 6 complete, non-overlapping, valid
 * 30 s windows, chosen chronologically (never by residual, schedule or outcome); at most 60 entries overall.
 * Every window left out is reported with its reason; older raw records are not deleted, only not analysed.
 */
public final class WindowSelection {

    /** One analysed data entry. */
    public record Entry(Window window, Gas gas, InletTrack track, Window background) {
        public Entry(Window window, Gas gas, InletTrack track) { this(window, gas, track, null); }
        public double value() {
            return window.meanEnhancement();
        }

        public double sigma() {
            return window.stdUncertainty();
        }
    }

    public record Result(List<Entry> entries, List<Exclusion> exclusions) {
        public Result {
            entries = List.copyOf(entries);
            exclusions = List.copyOf(exclusions);
        }
    }

    private static final double EPS = 1e-6;

    private WindowSelection() {
    }

    public static Result select(String runId, List<Window> windows, InletTrack.Source inlets, WorldProfile world) {
        double windowS = world.limit("window_s");
        int perChannel = world.limit("latest_complete_windows_per_instrument_channel");
        int maxEntries = world.limit("input_entries");
        List<Exclusion> excluded = new ArrayList<>();
        Map<String, List<Entry>> byChannel = new TreeMap<>();
        List<Window> sorted = windows.stream()
                .sorted(Comparator.comparing(Window::id, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        for (Window w : sorted) {
            Optional<Gas> gas = Gas.ofChannel(w.channel());
            String reason = null;
            if (gas.isEmpty()) {
                reason = "CHANNEL_NOT_MODELLED";
            } else if (!Double.isFinite(w.startS()) || !Double.isFinite(w.endS()) || !Double.isFinite(w.validSeconds())
                    || Math.abs((w.endS() - w.startS()) - windowS) > EPS || w.validSeconds() < windowS - EPS) {
                reason = "INCOMPLETE_WINDOW";
            } else if (w.meanEnhancement() == null || !Double.isFinite(w.meanEnhancement())) {
                reason = "NO_VALUE";
            } else if (w.stdUncertainty() == null || !Double.isFinite(w.stdUncertainty())
                    || !(w.stdUncertainty() > 0)) {
                reason = "UNCERTAINTY_NOT_USABLE";
            }
            Optional<InletTrack> track = Optional.empty();
            Window background = null;
            if (reason == null) {
                track = inlets.track(runId, w);
                if (track.isEmpty()) {
                    reason = "INLET_HISTORY_UNKNOWN";
                } else if (track.get().measured()) {
                    background = windows.stream().filter(b -> b.id().equals(w.backgroundWindowId()))
                            .findFirst().orElse(null);
                    if (background == null) reason = "BACKGROUND_WINDOW_UNKNOWN";
                    else if (!w.sensorOrVehicleId().equals(background.sensorOrVehicleId())
                            || !w.channel().equals(background.channel())) reason = "BACKGROUND_WINDOW_MISMATCH";
                    else if (!Double.isFinite(background.startS()) || !Double.isFinite(background.endS())
                            || !Double.isFinite(background.validSeconds())
                            || Math.abs(background.endS() - background.startS() - windowS) > EPS
                            || background.validSeconds() < windowS - EPS || background.endS() > w.startS() + EPS
                            || background.meanEnhancement() != null || background.stdUncertainty() == null
                            || !Double.isFinite(background.stdUncertainty()) || background.stdUncertainty() <= 0)
                        reason = "BACKGROUND_WINDOW_UNUSABLE";
                    else if (track.get().vehicle()) {
                        Window span = new Window(w.id(), w.sensorOrVehicleId(), w.channel(),
                                background.startS(), w.endS(), w.validSeconds(), w.meanEnhancement(),
                                w.stdUncertainty(), w.backgroundWindowId());
                        track = inlets.track(runId, span);
                        if (track.isEmpty() || track.get().samples().get(0).simTimeS() > background.startS() + EPS
                                || track.get().samples().get(track.get().samples().size() - 1).simTimeS() < w.endS() - EPS)
                            reason = "BACKGROUND_INLET_HISTORY_UNKNOWN";
                    }
                }
            }
            if (reason != null) {
                excluded.add(new Exclusion(w.id(), reason));
                continue;
            }
            if (background == null && !track.get().vehicle()) {
                background = windows.stream().filter(b -> b.id().equals(w.backgroundWindowId())
                        && b.sensorOrVehicleId().equals(w.sensorOrVehicleId()) && b.channel().equals(w.channel())
                        && b.meanEnhancement() == null && b.validSeconds() >= windowS - EPS
                        && Math.abs(b.endS() - b.startS() - windowS) <= EPS && b.endS() <= w.startS() + EPS)
                        .findFirst().orElse(null);
            }
            byChannel.computeIfAbsent(w.sensorOrVehicleId() + "|" + w.channel(), k -> new ArrayList<>())
                    .add(new Entry(w, gas.get(), track.get(), background));
        }
        List<Entry> chosen = new ArrayList<>();
        for (List<Entry> list : byChannel.values()) {
            list.sort(Comparator.comparingDouble((Entry e) -> e.window().endS()).reversed()
                    .thenComparing(e -> e.window().id()));
            List<Entry> kept = new ArrayList<>();
            double earliestStart = Double.POSITIVE_INFINITY;
            for (Entry e : list) {
                if (kept.size() >= perChannel) {
                    excluded.add(new Exclusion(e.window().id(), "OLDER_THAN_LATEST_" + perChannel));
                } else if (e.window().endS() > earliestStart + EPS) {
                    excluded.add(new Exclusion(e.window().id(), "OVERLAPS_LATER_WINDOW"));
                } else {
                    kept.add(e);
                    earliestStart = e.window().startS();
                }
            }
            chosen.addAll(kept);
        }
        chosen.sort(Comparator.comparingDouble((Entry e) -> e.window().endS()).reversed()
                .thenComparing(e -> e.window().id()));
        while (chosen.size() > maxEntries) {
            Entry dropped = chosen.remove(chosen.size() - 1);
            excluded.add(new Exclusion(dropped.window().id(), "ENTRY_LIMIT_" + maxEntries));
        }
        chosen.sort(Comparator.comparing((Entry e) -> e.window().sensorOrVehicleId())
                .thenComparing(e -> e.window().channel())
                .thenComparingDouble(e -> e.window().startS())
                .thenComparing(e -> e.window().id()));
        excluded.sort(Comparator.comparing(Exclusion::readingWindow, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new Result(chosen, excluded);
    }
}
