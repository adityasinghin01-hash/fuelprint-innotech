package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.WindNow;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fits one explanation under each of its 27 nuisance members: jointly, y = A q + e with q >= 0, whitened
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

    /** Physical amplitudes for one gas, sliced from the joint nonnegative fit. */
    public record GasFit(Gas gas, double[] amplitudes) { }

    /** The canonical joint fit, including cross-gas covariance; columns are gas-major then source. */
    public record JointFit(List<Gas> gases, double[] amplitudes, double[] whitenedData,
                           double[][] whitenedDesign, int rank, int zeroColumns) {
        public JointFit { gases = List.copyOf(gases); }
    }

    public record MemberFit(Member member, Map<Gas, GasFit> gases, JointFit joint, double sse,
                            int entries, String failure) {
        public double normalizedSse() { return sse / entries; }
        public boolean usable() { return failure == null; }
        public boolean fullRank(int sourceCount) {
            return joint != null && joint.rank() == sourceCount * joint.gases().size() && joint.zeroColumns() == 0;
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
    private final PuffPrediction puffs;
    private final Map<String, WorldProfile.Source> sourcesById = new HashMap<>();

    public FitEngine(WorldProfile world, ResponseModel response) {
        this.world = world;
        this.kernel = PlumeKernel.of(world);
        this.response = response;
        this.puffs = new PuffPrediction(world);
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

    public double unitContrast(Gas gas, String sourceId, Member member, WindNow wind, InletTrack track,
                               double backgroundStart, double backgroundEnd, double startS, double endS) {
        WorldProfile.Source s = sourcesById.get(sourceId);
        return response.windowContrast(gas, track, backgroundStart, backgroundEnd, startS, endS,
                p -> kernel.unit(gas, s.east(), s.north(), s.heightM() + member.heightShiftM(), p,
                        wind.fromDeg() + member.directionShiftDeg(), wind.speedMS() * member.speedFactor()));
    }

    /** Full public finite-age hypothesis for a recorded BG/collection operator. */
    public double unitWindow(Gas gas, String sourceId, Member member, WindNow wind, InletTrack track,
                             double bgStart, double bgEnd, double start, double end) {
        return unitWindow(gas,sourceId,member,wind,track,bgStart,bgEnd,start,end,() -> false);
    }

    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded() { super("COMPUTATION_BUDGET_EXCEEDED",null,false,false); }
    }

    public double unitWindow(Gas gas, String sourceId, Member member, WindNow wind, InletTrack track,
                             double bgStart, double bgEnd, double start, double end,
                             java.util.function.BooleanSupplier expired) {
        var source = sourcesById.get(sourceId);
        double h = source.heightM()+member.heightShiftM();
        double from = wind.fromDeg()+member.directionShiftDeg(), speed = wind.speedMS()*member.speedFactor();
        double[] series = track.stationary() ? puffs.stationarySeries(source.east(),source.north(),h,track.at(0),from,speed) : null;
        return response.puffContrast(gas,track,bgStart,bgEnd,start,end,(t,p) -> series == null
                ? puffs.unit(gas,source.east(),source.north(),h,p,from,speed,t)
                : puffs.convert(gas,series[(int)Math.round(t)]), expired);
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
                a[i][j] = cache.computeIfAbsent(key, k -> e.background() == null
                        ? unitResponse(gas, source, member, wind, e.track(), e.window().startS(), e.window().endS())
                        : unitWindow(gas, source, member, wind, e.track(), e.background().startS(),
                                e.background().endS(), e.window().startS(), e.window().endS()));
            }
        }
        return a;
    }

    /** Fits all gases together under the covariance of their actual averaging operators. */
    public ExplanationFit fit(Explanation explanation, WindNow wind, List<WindowSelection.Entry> entries,
                              Map<Object, Double> cache) {
        return fit(explanation, wind, entries, prepare(entries), cache);
    }

    public record Prepared(List<Gas> gases, Whitening whitening, double[] whitenedData, String failure) { }

    public Prepared prepare(List<WindowSelection.Entry> entries) {
        List<Gas> gases = java.util.Arrays.stream(Gas.values())
                .filter(g -> entries.stream().anyMatch(e -> e.gas() == g)).toList();
        try {
            Whitening whitening = Whitening.of(WindowCovariance.of(entries));
            return new Prepared(gases, whitening,
                    whitening.whiten(entries.stream().mapToDouble(WindowSelection.Entry::value).toArray()), null);
        } catch (Whitening.UnusableCovariance | IllegalArgumentException error) {
            return new Prepared(gases, null, null, error.getMessage());
        }
    }

    public ExplanationFit fit(Explanation explanation, WindNow wind, List<WindowSelection.Entry> entries,
                              Prepared prepared, Map<Object, Double> cache) {
        if (prepared.failure() != null) return new ExplanationFit(explanation, Member.all().stream()
                .map(m -> failed(m, entries.size(), prepared.failure())).toList());
        List<MemberFit> members = new ArrayList<>();
        for (Member m : Member.all()) members.add(fitMember(explanation, m, wind, entries, prepared.gases(), prepared.whitening(), prepared.whitenedData(), cache));
        return new ExplanationFit(explanation, members);
    }

    private static MemberFit failed(Member member, int entries, String reason) {
        return new MemberFit(member, Map.of(), null, Double.NaN, entries, reason);
    }

    private MemberFit fitMember(Explanation explanation, Member member, WindNow wind,
                                List<WindowSelection.Entry> entries, List<Gas> gases, Whitening wh,
                                double[] yw, Map<Object, Double> cache) {
        int n = entries.size(), p = explanation.sources().size();
        for (String id : explanation.sources()) {
            if (!member.possibleFor(wind.speedMS(), sourcesById.get(id).heightM()))
                return failed(member, n, "PHYSICALLY_IMPOSSIBLE_MEMBER");
        }
        double[][] a = new double[n][p*gases.size()];
        for (int k=0;k<gases.size();k++) {
            Gas gas = gases.get(k);
            List<WindowSelection.Entry> group = entries.stream().filter(e -> e.gas() == gas).toList();
            double[][] block = design(gas, explanation.sources(), member, wind, group, cache);
            int row=0;
            for (int i=0;i<n;i++) if (entries.get(i).gas() == gas)
                System.arraycopy(block[row++],0,a[i],k*p,p);
        }
        double[][] d = wh.whitenColumns(a);
        double yNorm = Math.sqrt(Nnls.dot(yw,yw));
        int zero=0;
        for (int j=0;j<p*gases.size();j++) {
            double norm=0;
            for(double[] row:d) norm += row[j]*row[j];
            if(Math.sqrt(norm) < ZERO_COLUMN_RATIO*Math.max(yNorm,1)) {
                zero++; for(double[] row:d) row[j]=0;
            }
        }
        Nnls.Result r=Nnls.solve(d,yw);
        if(!r.converged() || !Double.isFinite(r.sse())) return failed(member,n,"NNLS_NOT_CONVERGED");
        Map<Gas,GasFit> amplitudes=new EnumMap<>(Gas.class);
        for(int k=0;k<gases.size();k++) {
            double[] q=java.util.Arrays.copyOfRange(r.x(),k*p,(k+1)*p);
            double max=MAX_PLAUSIBLE_TIMES_NOMINAL*world.nominal(gases.get(k).nominalKey());
            for(double value:q) if(value>max) return failed(member,n,"IMPLAUSIBLE_EMISSION");
            amplitudes.put(gases.get(k),new GasFit(gases.get(k),q));
        }
        JointFit joint=new JointFit(gases,r.x(),yw,d,r.rank(),zero);
        return new MemberFit(member,amplitudes,joint,r.sse(),n,null);
    }
}
