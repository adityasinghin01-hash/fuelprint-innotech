package fuelprint.science.rank;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Independent oracles for the non-negative least squares (R3 doc 03). */
class NnlsOracleTest {

    @Test
    void proportionalColumnsBothFitExactly() {
        double[] y = {3, 6};
        Nnls.Result first = Nnls.solve(new double[][]{{1}, {2}}, y);
        Nnls.Result second = Nnls.solve(new double[][]{{2}, {4}}, y);
        assertThat(first.sse()).isCloseTo(0, within(1e-12));
        assertThat(first.x()[0]).isCloseTo(3, within(1e-12));
        assertThat(second.sse()).isCloseTo(0, within(1e-12));
        assertThat(second.x()[0]).isCloseTo(1.5, within(1e-12));
        Nnls.Result pair = Nnls.solve(new double[][]{{1, 2}, {2, 4}}, y);
        assertThat(pair.sse()).isCloseTo(0, within(1e-12));
        assertThat(pair.rank()).as("proportional columns limit identity").isEqualTo(1);
    }

    @Test
    void identityPairFitsExactlyAndSinglesLeaveTheOtherComponent() {
        double[] y = {4, 3};
        Nnls.Result pair = Nnls.solve(new double[][]{{1, 0}, {0, 1}}, y);
        assertThat(pair.sse()).isCloseTo(0, within(1e-12));
        assertThat(pair.rank()).isEqualTo(2);
        assertThat(Nnls.solve(new double[][]{{1}, {0}}, y).sse()).isCloseTo(9, within(1e-12));
        assertThat(Nnls.solve(new double[][]{{0}, {1}}, y).sse()).isCloseTo(16, within(1e-12));
    }

    @Test
    void neverReturnsANegativeEmission() {
        SplittableRandom rnd = new SplittableRandom(7);
        for (int trial = 0; trial < 500; trial++) {
            int m = 2 + rnd.nextInt(8);
            int n = 1 + rnd.nextInt(3);
            double[][] a = new double[m][n];
            double[] y = new double[m];
            for (int i = 0; i < m; i++) {
                y[i] = rnd.nextGaussian() * 10;
                for (int j = 0; j < n; j++) {
                    a[i][j] = rnd.nextGaussian();
                }
            }
            Nnls.Result r = Nnls.solve(a, y);
            assertThat(r.converged()).isTrue();
            for (double v : r.x()) {
                assertThat(v).isGreaterThanOrEqualTo(0.0);
            }
            // KKT: no column can still lower the error with a positive step.
            double[] res = Nnls.residual(a, y, r.x());
            for (int j = 0; j < n; j++) {
                double g = 0;
                for (int i = 0; i < m; i++) {
                    g += a[i][j] * res[i];
                }
                assertThat(g).isLessThanOrEqualTo(1e-8);
            }
        }
    }

    @Test
    void negativeTargetGivesZeroNotANegativeAmplitude() {
        Nnls.Result r = Nnls.solve(new double[][]{{1}, {1}}, new double[]{-2, -3});
        assertThat(r.x()[0]).isEqualTo(0.0);
        assertThat(r.sse()).isCloseTo(13, within(1e-12));
    }
}
