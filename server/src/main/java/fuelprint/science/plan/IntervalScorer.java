package fuelprint.science.plan;

import fuelprint.api.Enu;
import fuelprint.science.rank.Explanation;
import fuelprint.science.rank.FitEngine;
import fuelprint.science.rank.FitSnapshot;
import fuelprint.science.rank.Gas;
import fuelprint.science.rank.InletTrack;
import fuelprint.science.rank.NoiseModel;
import fuelprint.science.rank.Whitening;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FP-PLAN-INTERVAL-1 prospective intervals. For an admitted full-rank member fit with centre x0 and
 * G = (D^T D)^-1, the outer ellipsoid has radius^2 = n x cutoff - SSE0; a prospective whitened row w gives the
 * interval {@code w x0 +/- sqrt(radius^2 w G w^T)}. Conservative outer boxes are taken over all admitted members of
 * an explanation; the separation lower bound between two boxes is the Euclidean norm of the coordinate gaps, minus
 * the supported unmodelled error, clipped at 0. Unsupported, rank deficient or non-finite cases give "unavailable",
 * which scores 0, never infinity.
 */
public final class IntervalScorer {

    /** Dimensionless numerical allowance added to every endpoint. */
    public static final double ENDPOINT_ALLOWANCE = 1e-8;
    /** Supported unmodelled error of a new window, in whitened units (one standard uncertainty). */
    public static final double UNMODELLED_ERROR = 1.0;

    /** A box in whitened prospective coordinates. */
    public record Box(double[] lo, double[] hi) {
        public Box {
            lo = lo.clone();
            hi = hi.clone();
        }

        Box union(Box o) {
            double[] l = lo.clone();
            double[] h = hi.clone();
            for (int k = 0; k < l.length; k++) {
                l[k] = Math.min(l[k], o.lo[k]);
                h[k] = Math.max(h[k], o.hi[k]);
            }
            return new Box(l, h);
        }
    }

    private IntervalScorer() {
    }

    /** Separation lower bound between two boxes minus {@code allowance}, clipped at 0; 0 if not finite. */
    public static double separation(Box a, Box b, double allowance) {
        double sum = 0;
        for (int k = 0; k < a.lo.length; k++) {
            double aLo = a.lo[k] - ENDPOINT_ALLOWANCE;
            double aHi = a.hi[k] + ENDPOINT_ALLOWANCE;
            double bLo = b.lo[k] - ENDPOINT_ALLOWANCE;
            double bHi = b.hi[k] + ENDPOINT_ALLOWANCE;
            double gap = Math.max(0, Math.max(bLo - aHi, aLo - bHi));
            sum += gap * gap;
        }
        double s = Math.sqrt(sum) - allowance;
        return Double.isFinite(s) ? Math.max(0, s) : 0.0;
    }

    /**
     * The conservative outer box of an explanation's predictions at a stationary prospective inlet (one 30 s window
     * per analysed gas, in the snapshot's gas order), or empty when any admitted member is unavailable.
     */
    public static Optional<Box> outerBox(FitEngine engine, FitSnapshot snapshot, FitSnapshot.Surviving s, Enu target,
                                         List<Gas> gases) {
        return outerBox(engine,snapshot,s,target,gases,null);
    }

    public static Optional<Box> outerBox(FitEngine engine, FitSnapshot snapshot, FitSnapshot.Surviving s, Enu target,
                                         List<Gas> gases, ProspectiveMeasurement measurement) {
        return outerBox(engine,snapshot,s,target,gases,measurement,() -> false);
    }

    public static Optional<Box> outerBox(FitEngine engine, FitSnapshot snapshot, FitSnapshot.Surviving s, Enu target,
                                         List<Gas> gases, ProspectiveMeasurement measurement,
                                         java.util.function.BooleanSupplier expired) {
        if (snapshot.finiteAgeMeasurements() && measurement == null) return Optional.empty();
        Explanation e = s.explanation();
        if (s.admitted().isEmpty()) {
            return Optional.empty();
        }
        Box box = null;
        InletTrack track = InletTrack.fixed(target);
        if (expired.getAsBoolean()) return Optional.empty();
        Whitening prospectiveNoise;
        try {
            prospectiveNoise = measurement == null ? Whitening.diagonal(gases.stream()
                    .mapToDouble(gas -> NoiseModel.enhancementStd(engine.world(),gas)).toArray())
                    : Whitening.of(measurement.covariance(gases));
        } catch (Whitening.UnusableCovariance | IllegalArgumentException unavailable) {
            return Optional.empty();
        }
        for (FitEngine.MemberFit m : s.admitted()) {
            if (expired.getAsBoolean()) return Optional.empty();
            if (!m.fullRank(e.sources().size())) {
                return Optional.empty();
            }
            int sourceCount = e.sources().size();
            int p = sourceCount * m.joint().gases().size();
            double[] centre = new double[p];
            double[][] normal = new double[p][p];
            double sseLs = m.sse();
            if (p > 0) {
                double[][] d = m.joint().whitenedDesign();
                double[] y = m.joint().whitenedData();
                Optional<double[][]> inverse = inverseNormal(d);
                if (inverse.isEmpty()) return Optional.empty();
                normal = inverse.get();
                double[] dty = new double[p];
                for (int i=0;i<d.length;i++) for(int j=0;j<p;j++) dty[j] += d[i][j]*y[i];
                for(int i=0;i<p;i++) for(int j=0;j<p;j++) centre[i] += normal[i][j]*dty[j];
                sseLs=0;
                for(int i=0;i<d.length;i++) {
                    double residual=y[i];
                    for(int j=0;j<p;j++) residual-=d[i][j]*centre[j];
                    sseLs+=residual*residual;
                }
            }
            double radius2 = snapshot.entries()*snapshot.cutoff()-sseLs;
            if (!(radius2 >= 0)) return Optional.empty();
            double[] lo=new double[gases.size()], hi=new double[gases.size()];
            double[][] rawRows=new double[gases.size()][p];
            for(int k=0;k<gases.size() && p>0;k++) {
                Gas gas=gases.get(k);
                int gasIndex=m.joint().gases().indexOf(gas);
                if(gasIndex<0) return Optional.empty();
                try {
                    for(int j=0;j<sourceCount;j++) rawRows[k][gasIndex*sourceCount+j]=(measurement == null
                            ? engine.unitResponse(gas,e.sources().get(j),m.member(),snapshot.wind(),track,0,30)
                            : measurement.unit(engine,gas,e.sources().get(j),m.member(),snapshot.wind(),expired));
                } catch (FitEngine.BudgetExceeded timeout) { return Optional.empty(); }
            }
            double[][] rows=prospectiveNoise.whitenColumns(rawRows);
            for(int k=0;k<gases.size() && p>0;k++) {
                double[] w=rows[k];
                double prediction=0,quad=0;
                for(int i=0;i<p;i++) {
                    prediction+=w[i]*centre[i];
                    for(int j=0;j<p;j++) quad+=w[i]*normal[i][j]*w[j];
                }
                double half=Math.sqrt(radius2*Math.max(0,quad));
                if(!Double.isFinite(prediction)||!Double.isFinite(half)) return Optional.empty();
                lo[k]=measurement == null ? Math.max(0,prediction-half) : prediction-half;
                hi[k]=prediction+half;
            }
            Box b = new Box(lo, hi);
            box = box == null ? b : box.union(b);
        }
        return Optional.of(box);
    }

    /** (D^T D)^-1 through the SVD of D; empty when D is not full column rank (ratio 1e-8). */
    static Optional<double[][]> inverseNormal(double[][] d) {
        int p = d.length == 0 ? 0 : d[0].length;
        if (p == 0) {
            return Optional.empty();
        }
        SingularValueDecomposition svd = new SingularValueDecomposition(MatrixUtils.createRealMatrix(d));
        double[] sv = svd.getSingularValues();
        double max = sv[0];
        if (!(max > 0) || sv.length < p || !(sv[p - 1] > 1e-8 * max)) {
            return Optional.empty();
        }
        RealMatrix v = svd.getV();
        double[][] g = new double[p][p];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < p; j++) {
                double s = 0;
                for (int k = 0; k < p; k++) {
                    s += v.getEntry(i, k) * v.getEntry(j, k) / (sv[k] * sv[k]);
                }
                g[i][j] = s;
            }
        }
        return Optional.of(g);
    }

    /** Gas order used for prospective coordinates. */
    public static List<Gas> gasesOf(FitSnapshot snapshot) {
        List<Gas> list = new ArrayList<>();
        for (Gas g : Gas.values()) {
            if (snapshot.gases().contains(g)) {
                list.add(g);
            }
        }
        return list;
    }
}
