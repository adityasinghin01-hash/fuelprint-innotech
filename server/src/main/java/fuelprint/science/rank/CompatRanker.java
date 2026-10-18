package fuelprint.science.rank;

import fuelprint.api.Analysis;
import fuelprint.api.AnalysisInput;
import fuelprint.api.Candidate;
import fuelprint.api.CandidateStatus;
import fuelprint.api.Outcome;
import fuelprint.api.Qualification;
import fuelprint.api.WindNow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * FP-COMPAT-3 smoke-spread reasoning (AI-1): selection, NO_SIGNAL gate, 6 singles + 15 pairs (+ BACKGROUND) each
 * fitted under 27 nuisance members, survival by the calibrated cutoff on normalised SSE, the five outcomes, the
 * always-present UNKNOWN row and a deterministic plain explanation. Works only from public input.
 * <p>
 * Parsimony: a pair whose single member already survives on its own is marked REJECTED with reason NOT_NEEDED
 * (it fits only because it contains that single); the limitation list still says those mixtures fit.
 */
public final class CompatRanker {

    public static final String ALGORITHM = "FP-COMPAT-3/1";
    private static final double SIGNAL_SIGMAS = 3.0;

    public record Result(Analysis analysis, FitSnapshot snapshot) {
    }

    private final FitEngine engine;
    private final Cutoff cutoff;
    private final List<Explanation> explanations;

    public CompatRanker(FitEngine engine, Cutoff cutoff) {
        this.engine = engine;
        this.cutoff = cutoff;
        this.explanations = Explanation.enumerate(engine.world().sources().stream()
                .map(WorldProfile.Source::id).toList());
    }

    public Cutoff cutoff() {
        return cutoff;
    }

    public FitEngine engine() {
        return engine;
    }

    public String algorithmVersion() {
        return ALGORITHM + " " + cutoff.label() + " response[" + engine.response().label() + "]";
    }

    public Result analyze(AnalysisInput input, InletTrack.Source inlets) {
        WindowSelection.Result sel = WindowSelection.select(input.runId(), input.windows(), inlets, engine.world());
        List<WindowSelection.Entry> entries = sel.entries();
        List<String> ids = entries.stream().map(e -> e.window().id()).toList();
        List<String> limitations = new ArrayList<>();
        limitations.add("SYNTHETIC_SCOPE_ONLY: valid only inside this synthetic teaching model");
        limitations.add("UNREGISTERED_SOURCE_NOT_EXCLUDED: a source not in the registry is never ruled out");
        WindNow wind = input.wind();
        if (wind == null || !Double.isFinite(wind.fromDeg()) || !(wind.speedMS() > 0)) {
            limitations.add("WIND_NOT_USABLE: no usable wind in the input");
            return new Result(simple(ids, Outcome.INVALID, sel, limitations,
                    "Not enough usable data to analyse: the wind is missing or not usable."), null);
        }
        if (entries.isEmpty()) {
            return new Result(simple(ids, Outcome.INVALID, sel, limitations, String.format(Locale.ROOT,
                    "Not enough usable data to analyse: none of the %d windows sent is a complete, valid 30 s "
                            + "window with a known uncertainty and inlet.", input.windows().size())), null);
        }
        boolean signal = entries.stream()
                .anyMatch(e -> e.value() >= Math.max(e.gas().floor(), SIGNAL_SIGMAS * e.sigma()));
        if (!signal) {
            return new Result(simple(ids, Outcome.NO_SIGNAL, sel, limitations, String.format(Locale.ROOT,
                    "%d windows were usable, but none is above its floor (25 ppb SO2, 5 ppm CO2, or three times "
                            + "its uncertainty). This does not mean the factories are clean.", entries.size())),
                    null);
        }
        limitations.add("STEADY_WIND_ASSUMED: one wind for every analysed window");
        limitations.add("STATIONARITY_NOT_SUPPORTED: emissions are assumed steady over the analysed windows");
        limitations.add("COVARIANCE_DIAGONAL: windows are treated as independent");

        Map<Object, Double> cache = new HashMap<>();
        Map<String, FitEngine.ExplanationFit> fits = new LinkedHashMap<>();
        for (Explanation e : explanations) {
            fits.put(e.id(), engine.fit(e, wind, entries, cache));
        }
        double c = cutoff.value();
        Map<String, Boolean> withinCutoff = new LinkedHashMap<>();
        for (FitEngine.ExplanationFit f : fits.values()) {
            FitEngine.MemberFit best = f.best();
            withinCutoff.put(f.explanation().id(), best != null && best.normalizedSse() <= c);
        }
        List<Candidate> candidates = new ArrayList<>();
        List<FitSnapshot.Surviving> surviving = new ArrayList<>();
        List<String> notNeeded = new ArrayList<>();
        TreeSet<String> identityLimited = new TreeSet<>();
        long failures = 0;
        for (FitEngine.ExplanationFit f : fits.values()) {
            Explanation e = f.explanation();
            FitEngine.MemberFit best = f.best();
            failures += f.failures();
            Double fit = best == null ? null : best.normalizedSse();
            if (best != null && e.sources().size() > 0 && !best.fullRank(e.sources().size())) {
                identityLimited.add(e.id());
            }
            String reason;
            CandidateStatus status;
            if (best == null) {
                status = CandidateStatus.REJECTED;
                reason = "FIT_FAILED: no nuisance member could be fitted";
            } else if (!withinCutoff.get(e.id())) {
                status = CandidateStatus.REJECTED;
                reason = String.format(Locale.ROOT, "ABOVE_CUTOFF: best fit %.3f is above the cutoff %.3f",
                        fit, c);
            } else if (e.isPair() && e.sources().stream().anyMatch(s -> withinCutoff.get(s))) {
                status = CandidateStatus.REJECTED;
                String single = e.sources().stream().filter(withinCutoff::get).collect(Collectors.joining(" or "));
                reason = "NOT_NEEDED: " + single + " alone already fits within the cutoff";
                notNeeded.add(e.id());
            } else {
                status = CandidateStatus.SURVIVES;
                reason = String.format(Locale.ROOT, "FITS: best fit %.3f is within the cutoff %.3f", fit, c);
                surviving.add(new FitSnapshot.Surviving(e, f.members().stream()
                        .filter(m -> m.usable() && m.normalizedSse() <= c).toList()));
            }
            candidates.add(new Candidate(e.id(), e.sources(), fit, status, reason));
        }
        List<String> singles = surviving.stream().map(FitSnapshot.Surviving::explanation)
                .filter(Explanation::isSingle).map(Explanation::id).toList();
        List<String> pairs = surviving.stream().map(FitSnapshot.Surviving::explanation)
                .filter(Explanation::isPair).map(Explanation::id).toList();
        boolean background = surviving.stream().anyMatch(s -> s.explanation().sources().isEmpty());
        Outcome outcome;
        if (singles.isEmpty() && pairs.isEmpty() && !background) {
            outcome = Outcome.UNKNOWN_OR_MISMATCH;
        } else if (singles.size() == 1 && pairs.isEmpty() && !background) {
            outcome = Outcome.PROVISIONAL_SINGLE;
        } else {
            outcome = Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE;
        }
        candidates.add(new Candidate(Candidate.UNKNOWN, List.of(), null, CandidateStatus.SURVIVES,
                outcome == Outcome.UNKNOWN_OR_MISMATCH
                        ? "No registered explanation fits; an unregistered source may be emitting"
                        : "Always kept: a fit to registered factories never rules out an unregistered source"));
        if (background) {
            limitations.add("BACKGROUND_FITS: the readings can also be explained with no registered source");
        }
        if (!notNeeded.isEmpty()) {
            limitations.add("NOT_NEEDED_MIXTURES_ALSO_FIT: " + String.join(", ", notNeeded));
        }
        if (!identityLimited.isEmpty()) {
            limitations.add("IDENTITY_LIMITED: " + String.join(", ", identityLimited)
                    + " (a source column is zero or proportional to another at these inlets)");
        }
        if (failures > 0) {
            limitations.add("MEMBER_FAILURES: " + failures + " nuisance member fits failed and are kept as failed");
        }
        String text = explain(entries, wind, outcome, singles, pairs, background, candidates, c);
        Analysis analysis = Analysis.physics(ids, outcome, Qualification.SYNTHETIC_SCOPE_ONLY, candidates,
                sel.exclusions(), limitations, text, algorithmVersion());
        FitSnapshot snapshot = new FitSnapshot(ids, wind, c, entries.size(),
                entries.stream().map(WindowSelection.Entry::gas).collect(Collectors.toSet()), surviving);
        return new Result(analysis, snapshot);
    }

    private Analysis simple(List<String> ids, Outcome outcome, WindowSelection.Result sel, List<String> limitations,
                            String text) {
        List<Candidate> candidates = List.of(new Candidate(Candidate.UNKNOWN, List.of(), null,
                CandidateStatus.SURVIVES, "Not analysed (" + outcome + "); an unregistered source is never ruled out"));
        return Analysis.physics(ids, outcome, Qualification.SYNTHETIC_SCOPE_ONLY, candidates, sel.exclusions(),
                limitations, text, algorithmVersion());
    }

    private static String explain(List<WindowSelection.Entry> entries, WindNow wind, Outcome outcome,
                                  List<String> singles, List<String> pairs, boolean background,
                                  List<Candidate> candidates, double cutoff) {
        String places = entries.stream().map(e -> e.window().sensorOrVehicleId()).distinct().sorted()
                .collect(Collectors.joining(", "));
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "The smoke-spread check used %d windows from %s, with the wind from "
                + "%.0f degrees at %.1f m/s. ", entries.size(), places, wind.fromDeg(), wind.speedMS()));
        List<String> fitting = new ArrayList<>(singles);
        fitting.addAll(pairs);
        if (background) {
            fitting.add(0, "BACKGROUND");
        }
        switch (outcome) {
            case PROVISIONAL_SINGLE -> b.append(singles.get(0)).append(" alone explains the readings within the "
                    + "cutoff; no other registered factory or pair does. ");
            case AMBIGUOUS_OR_OPTIONAL_MIXTURE -> b.append("More than one explanation still fits: ")
                    .append(String.join(", ", fitting)).append(". ");
            case UNKNOWN_OR_MISMATCH -> b.append("None of the registered factories, alone or in pairs, explains "
                    + "the readings. ");
            default -> {
            }
        }
        long rejected = candidates.stream().filter(c -> c.status() == CandidateStatus.REJECTED).count();
        b.append(String.format(Locale.ROOT, "%d explanations were set aside (cutoff %.3f). ", rejected, cutoff));
        b.append("An unregistered source is never ruled out. This holds only inside the synthetic teaching model.");
        return b.toString();
    }
}
