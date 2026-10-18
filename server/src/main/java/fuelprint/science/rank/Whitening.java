package fuelprint.science.rank;

import org.apache.commons.math3.linear.CholeskyDecomposition;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;

import java.util.Arrays;

/**
 * Whitening by the covariance (R3 doc 03): the covariance must be finite and symmetric, positive semi-definite to
 * -1e-10 x max diagonal and positive definite with condition number at most 1e8; then y_w = L^-1 y and
 * A_w = L^-1 A with the Cholesky factor L L^T = covariance.
 */
public final class Whitening {

    public static final double PSD_TOLERANCE = 1e-10;
    public static final double MAX_CONDITION = 1e8;

    private final RealMatrix lower;

    private Whitening(RealMatrix lower) {
        this.lower = lower;
    }

    /** Thrown when the covariance cannot be used; the message is the reason. */
    public static final class UnusableCovariance extends Exception {
        public UnusableCovariance(String reason) {
            super(reason);
        }
    }

    public static Whitening diagonal(double[] standardUncertainties) throws UnusableCovariance {
        int n = standardUncertainties.length;
        double[][] c = new double[n][n];
        for (int i = 0; i < n; i++) {
            c[i][i] = standardUncertainties[i] * standardUncertainties[i];
        }
        return of(c);
    }

    public static Whitening of(double[][] covariance) throws UnusableCovariance {
        int n = covariance.length;
        if (n == 0) {
            throw new UnusableCovariance("EMPTY_COVARIANCE");
        }
        double maxDiag = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double v = covariance[i][j];
                if (!Double.isFinite(v)) {
                    throw new UnusableCovariance("COVARIANCE_NOT_FINITE");
                }
                if (Math.abs(v - covariance[j][i]) > 1e-12 * Math.max(1, Math.abs(v))) {
                    throw new UnusableCovariance("COVARIANCE_NOT_SYMMETRIC");
                }
            }
            maxDiag = Math.max(maxDiag, covariance[i][i]);
        }
        if (!(maxDiag > 0)) {
            throw new UnusableCovariance("COVARIANCE_ZERO");
        }
        RealMatrix c = MatrixUtils.createRealMatrix(covariance);
        boolean diagonal = true;
        for (int i = 0; i < n && diagonal; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && covariance[i][j] != 0.0) {
                    diagonal = false;
                    break;
                }
            }
        }
        double[] eig = new double[n];
        if (diagonal) {
            for (int i = 0; i < n; i++) {
                eig[i] = covariance[i][i];
            }
        } else {
            eig = new EigenDecomposition(c).getRealEigenvalues();
        }
        double min = Arrays.stream(eig).min().orElse(0);
        double max = Arrays.stream(eig).max().orElse(0);
        if (min < -PSD_TOLERANCE * maxDiag) {
            throw new UnusableCovariance("COVARIANCE_NOT_PSD");
        }
        if (!(min > 0) || max / min > MAX_CONDITION) {
            throw new UnusableCovariance("COVARIANCE_CONDITION_ABOVE_1E8");
        }
        if (diagonal) {
            RealMatrix l = MatrixUtils.createRealMatrix(n, n);
            for (int i = 0; i < n; i++) {
                l.setEntry(i, i, Math.sqrt(covariance[i][i]));
            }
            return new Whitening(l);
        }
        try {
            return new Whitening(new CholeskyDecomposition(c, 1e-12, 0).getL());
        } catch (RuntimeException e) {
            throw new UnusableCovariance("CHOLESKY_FAILED");
        }
    }

    public double[] whiten(double[] y) {
        return solve(y);
    }

    private double[] solve(double[] y) {
        int n = y.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = y[i];
            for (int k = 0; k < i; k++) {
                s -= lower.getEntry(i, k) * out[k];
            }
            out[i] = s / lower.getEntry(i, i);
        }
        return out;
    }

    /** Whitens each column of A (rows = data entries, columns = sources). */
    public double[][] whitenColumns(double[][] a) {
        int rows = a.length;
        int cols = rows == 0 ? 0 : a[0].length;
        double[][] out = new double[rows][cols];
        for (int j = 0; j < cols; j++) {
            double[] col = new double[rows];
            for (int i = 0; i < rows; i++) {
                col[i] = a[i][j];
            }
            double[] w = solve(col);
            for (int i = 0; i < rows; i++) {
                out[i][j] = w[i];
            }
        }
        return out;
    }
}
