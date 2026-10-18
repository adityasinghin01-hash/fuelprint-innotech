package fuelprint.science.rank;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.Arrays;

/**
 * Non-negative least squares, Lawson-Hanson active set: minimise ||A x - y||^2 subject to x >= 0. Unconstrained
 * sub-problems on the passive set are solved with the SVD pseudo-inverse (Apache Commons Math), so rank deficient
 * columns still give a finite answer; the rank is reported so callers can limit identity.
 */
public final class Nnls {

    /** Singular values below this ratio of the largest count as zero (R3: ratio < 1e-8 limits identity). */
    public static final double RANK_RATIO = 1e-8;
    public static final int MAX_ITER = 200;

    /**
     * @param x          amplitudes, all >= 0
     * @param sse        sum of squared residuals
     * @param rank       numerical rank of A
     * @param iterations outer iterations used
     * @param converged  false if the iteration cap was hit or a value was not finite
     */
    public record Result(double[] x, double sse, int rank, int iterations, boolean converged) {
        public Result {
            x = x.clone();
        }

        @Override
        public double[] x() {
            return x.clone();
        }
    }

    private Nnls() {
    }

    public static Result solve(double[][] a, double[] y) {
        int m = y.length;
        int n = a.length == 0 ? 0 : a[0].length;
        double[] x = new double[n];
        if (n == 0) {
            return new Result(x, dot(y, y), 0, 0, true);
        }
        double norm = 0;
        for (double[] row : a) {
            for (double v : row) {
                norm = Math.max(norm, Math.abs(v));
            }
        }
        double tol = 10 * Math.ulp(1.0) * Math.max(norm, 1e-300) * Math.max(m, n);
        boolean[] passive = new boolean[n];
        double[] w = gradient(a, y, x);
        int iter = 0;
        boolean converged = true;
        while (true) {
            int j = -1;
            double best = tol;
            for (int k = 0; k < n; k++) {
                if (!passive[k] && w[k] > best) {
                    best = w[k];
                    j = k;
                }
            }
            if (j < 0) {
                break;
            }
            if (++iter > MAX_ITER) {
                converged = false;
                break;
            }
            passive[j] = true;
            while (true) {
                double[] s = passiveSolve(a, y, passive);
                boolean allPositive = true;
                for (int k = 0; k < n; k++) {
                    if (passive[k] && s[k] <= tol) {
                        allPositive = false;
                        break;
                    }
                }
                if (allPositive) {
                    x = s;
                    break;
                }
                double alpha = Double.POSITIVE_INFINITY;
                for (int k = 0; k < n; k++) {
                    if (passive[k] && s[k] <= tol) {
                        double denom = x[k] - s[k];
                        alpha = Math.min(alpha, denom > 0 ? x[k] / denom : 0.0);
                    }
                }
                for (int k = 0; k < n; k++) {
                    x[k] = x[k] + alpha * (s[k] - x[k]);
                    if (passive[k] && x[k] <= tol) {
                        passive[k] = false;
                        x[k] = 0.0;
                    }
                }
            }
            w = gradient(a, y, x);
        }
        for (int k = 0; k < n; k++) {
            if (x[k] < 0 || !Double.isFinite(x[k])) {
                converged = false;
                x[k] = Math.max(0.0, Double.isFinite(x[k]) ? x[k] : 0.0);
            }
        }
        double[] r = residual(a, y, x);
        double sse = dot(r, r);
        if (!Double.isFinite(sse)) {
            converged = false;
        }
        return new Result(x, sse, rank(a), iter, converged);
    }

    /** Numerical rank of A with the {@link #RANK_RATIO} rule; 0 for all-zero matrices. */
    public static int rank(double[][] a) {
        if (a.length == 0 || a[0].length == 0) {
            return 0;
        }
        double[] sv = new SingularValueDecomposition(MatrixUtils.createRealMatrix(a)).getSingularValues();
        double max = Arrays.stream(sv).max().orElse(0);
        if (!(max > 0)) {
            return 0;
        }
        int r = 0;
        for (double v : sv) {
            if (v > RANK_RATIO * max) {
                r++;
            }
        }
        return r;
    }

    private static double[] passiveSolve(double[][] a, double[] y, boolean[] passive) {
        int n = passive.length;
        int p = 0;
        for (boolean b : passive) {
            if (b) {
                p++;
            }
        }
        double[][] sub = new double[a.length][p];
        int[] cols = new int[p];
        int c = 0;
        for (int k = 0; k < n; k++) {
            if (passive[k]) {
                cols[c++] = k;
            }
        }
        for (int i = 0; i < a.length; i++) {
            for (int k = 0; k < p; k++) {
                sub[i][k] = a[i][cols[k]];
            }
        }
        double[] z = pseudoInverseSolve(MatrixUtils.createRealMatrix(sub), y);
        double[] s = new double[n];
        for (int k = 0; k < p; k++) {
            s[cols[k]] = z[k];
        }
        return s;
    }

    /** Minimum-norm least squares solution through the SVD, dropping singular values below the rank ratio. */
    static double[] pseudoInverseSolve(RealMatrix a, double[] y) {
        SingularValueDecomposition svd = new SingularValueDecomposition(a);
        double[] sv = svd.getSingularValues();
        double max = Arrays.stream(sv).max().orElse(0);
        RealMatrix u = svd.getU();
        RealMatrix v = svd.getV();
        double[] out = new double[a.getColumnDimension()];
        for (int k = 0; k < sv.length; k++) {
            if (!(sv[k] > RANK_RATIO * max) || !(max > 0)) {
                continue;
            }
            double coef = 0;
            for (int i = 0; i < y.length; i++) {
                coef += u.getEntry(i, k) * y[i];
            }
            coef /= sv[k];
            for (int j = 0; j < out.length; j++) {
                out[j] += coef * v.getEntry(j, k);
            }
        }
        return out;
    }

    private static double[] gradient(double[][] a, double[] y, double[] x) {
        double[] r = residual(a, y, x);
        int n = x.length;
        double[] w = new double[n];
        for (int i = 0; i < a.length; i++) {
            for (int k = 0; k < n; k++) {
                w[k] += a[i][k] * r[i];
            }
        }
        return w;
    }

    static double[] residual(double[][] a, double[] y, double[] x) {
        double[] r = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            double s = 0;
            for (int k = 0; k < x.length; k++) {
                s += a[i][k] * x[k];
            }
            r[i] = y[i] - s;
        }
        return r;
    }

    static double dot(double[] p, double[] q) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            s += p[i] * q[i];
        }
        return s;
    }
}
