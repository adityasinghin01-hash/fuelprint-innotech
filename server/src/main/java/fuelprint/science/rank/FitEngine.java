package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.WindNow;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fits one explanation under each of its 27 nuisance members: per gas, y_g = A_g q_g + e with q_g >= 0, whitened
 * by the window covariance, solved by NNLS. The member's normalised SSE is the total whitened SSE over both gases
 * divided by the number of data entries. Every member failure is kept, never dropped.
 */
public final class FitEngine {

    /** A column whose whitened norm is below this ratio of max(||y_w||, 1) is treated as zero (no identity). */
    public static final double ZERO_COLUMN_RATIO = 1e-8;
    /**
     * Declared physical limit: a fitted emission above 1000 times the nominal registered rate (SO2 500 g/s,
     * CO2 500 kg/s) is physically impossible here, so that member is rejected (never clipped).
     */
    public static final double MAX_PLAUSIBLE_TIMES_NOMINAL = 1000;

    /**
     * Per-gas result of one member: NNLS amplitudes (g/s, one per source), whitened data y_w and design D, SSE and
     * rank.
     */
    public record GasFit(Gas gas, double[] amplitudes, double[] whitenedData, double[][] whitenedDesign, double sse,
                         int rank, boolean converged, int zeroColumns) {
    }

    /** One nuisance member's fit; {@code failure} is non-null when the member could not be used. */
    public record MemberFit(Member member, Map<Gas, GasFit> gases, double sse, int entries, String failure) {
        public double normalizedSse() {
            return sse / entries;
        }

        public boolean usable() {
            return failure == null;
        }

        /** Full rank in every gas: each source column is identifiable. */
        public boolean fullRank(int sourceCount) {
            return gases.values().stream().allMatch(g -> g.rank() == sourceCount && g.zeroColumns() == 0);
        }
    }

    public record ExplanationFit(Explanation explanation, List<MemberFit> members) {
        public MemberFit best() {
            MemberFit best = null;
            for (MemberFit m : members) {
                if (m.usable() && (best == null || m.normalizedSse() < best.normalizedSse())) {
                    best = m;
                }
            }
            return best;
        }

        public long failures() {
            return members.stream().filter(m -> !m.usable()).count();
        }
    }

    private final WorldProfile world;
    private final PlumeKernel kernel;
    private final ResponseModel response;
    private final Map<String, WorldProfile.Source> sourcesById = new HashMap<>();

    public FitEngine(WorldProfile world, ResponseModel response) {
        this.world = world;
        this.kernel = PlumeKernel.of(world);
        this.response = response;
        for (WorldProfile.Source s : world.sources()) {
            sourcesById.put(s.id(), s);
        }
    }

    public WorldProfile world() {
        return world;
    }

    public PlumeKernel kernel() {
        return kernel;
    }

    public ResponseModel response() {
        return response;
    }

    /** Model response in the gas unit per g/s of {@code sourceId}, at a window, under a member. */
    public double unitResponse(Gas gas, String sourceId, Member member, WindNow wind, InletTrack track,
                               double startS, double endS) {
        WorldProfile.Source s = sourcesById.get(sourceId);
        double h = s.heightM() + member.heightShiftM();
        double from = wind.fromDeg() + member.directionShiftDeg();
        double speed = wind.speedMS() * member.speedFactor();
        return response.windowMean(gas, track, startS, endS,
                (Enu p) -> kernel.unit(gas, s.east(), s.north(), h, p, from, speed));
    }

    /** Raw (unwhitened) design matrix for one gas: rows = entries, columns = sources. */
    public double[][] design(Gas gas, List<String> sources, Member member, WindNow wind,
                             List<WindowSelection.Entry> entries, Map<Object, Double> cache) {
        double[][] a = new double[entries.size()][sources.size()];
        for (int i = 0; i < entries.size(); i++) {
            WindowSelection.Entry e = entries.get(i);
            for (int j = 0; j < sources.size(); j++) {
                String source = sources.get(j);
                List<Object> key = List.of(source, member, gas, e.window().id());
                a[i][j] = cache.computeIfAbsent(key, k -> unitResponse(gas, source, member, wind, e.track(),
                        e.window().startS(), e.window().endS()));
            }
        }
        return a;
    }

    /** Fits one explanation; {@code cache} holds unit responses shared by explanations of the same analysis. */
    public ExplanationFit fit(Explanation explanation, WindNow wind, List<WindowSelection.Entry> entries,
                              Map<Object, Double> cache) {
        Map<Gas, List<WindowSelection.Entry>> byGas = new EnumMap<>(Gas.class);
        for (WindowSelection.Entry e : entries) {
            byGas.computeIfAbsent(e.gas(), g -> new ArrayList<>()).add(e);
        }
        List<MemberFit> members = new ArrayList<>();
        for (Member m : Member.all()) {
            members.add(fitMember(explanation, m, wind, byGas, entries.size(), cache));
        }
        return new ExplanationFit(explanation, members);
    }

    private MemberFit fitMember(Explanation explanation, Member member, WindNow wind,
                                Map<Gas, List<WindowSelection.Entry>> byGas, int n,
                                Map<Object, Double> cache) {
        for (String id : explanation.sources()) {
            if (!member.possibleFor(wind.speedMS(), sourcesById.get(id).heightM())) {
                return new MemberFit(member, Map.of(), Double.NaN, n, "PHYSICALLY_IMPOSSIBLE_MEMBER");
            }
        }
        Map<Gas, GasFit> gases = new EnumMap<>(Gas.class);
        double total = 0;
        for (Map.Entry<Gas, List<WindowSelection.Entry>> g : byGas.entrySet()) {
            List<WindowSelection.Entry> list = g.getValue();
            double[] y = new double[list.size()];
            double[] sigma = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                y[i] = list.get(i).value();
                sigma[i] = list.get(i).sigma();
            }
            Whitening wh;
            try {
                wh = Whitening.diagonal(sigma);
            } catch (Whitening.UnusableCovariance e) {
                return new MemberFit(member, Map.of(), Double.NaN, n, e.getMessage());
            }
            double[] yw = wh.whiten(y);
            double[][] d = wh.whitenColumns(design(g.getKey(), explanation.sources(), member, wind, list, cache));
            double yNorm = Math.sqrt(Nnls.dot(yw, yw));
            int zero = 0;
            for (int j = 0; j < explanation.sources().size(); j++) {
                double cn = 0;
                for (double[] row : d) {
                    cn += row[j] * row[j];
                }
                if (Math.sqrt(cn) < ZERO_COLUMN_RATIO * Math.max(yNorm, 1.0)) {
                    zero++;
                    for (double[] row : d) {
                        row[j] = 0.0;
                    }
                }
            }
            Nnls.Result r = Nnls.solve(d, yw);
            if (!r.converged() || !Double.isFinite(r.sse())) {
                return new MemberFit(member, Map.of(), Double.NaN, n, "NNLS_NOT_CONVERGED");
            }
            double max = MAX_PLAUSIBLE_TIMES_NOMINAL * world.nominal(g.getKey().nominalKey());
            for (double q : r.x()) {
                if (q > max) {
                    return new MemberFit(member, Map.of(), Double.NaN, n, "IMPLAUSIBLE_EMISSION");
                }
            }
            gases.put(g.getKey(), new GasFit(g.getKey(), r.x(), yw, d, r.sse(), r.rank(), true, zero));
            total += r.sse();
        }
        return new MemberFit(member, gases, total, n, null);
    }
}
