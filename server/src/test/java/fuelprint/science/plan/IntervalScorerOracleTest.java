package fuelprint.science.plan;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Independent oracles for the prospective boxes (R3 doc 03). */
class IntervalScorerOracleTest {

    private static IntervalScorer.Box box(double lo, double hi) {
        return new IntervalScorer.Box(new double[]{lo}, new double[]{hi});
    }

    @Test
    void boxesZeroOneAndFourFiveAreThreeApartAndTwoAfterAnAllowanceOfOne() {
        assertThat(IntervalScorer.separation(box(0, 1), box(4, 5), 0)).isCloseTo(3, within(1e-7));
        assertThat(IntervalScorer.separation(box(0, 1), box(4, 5), 1)).isCloseTo(2, within(1e-7));
        assertThat(IntervalScorer.separation(box(4, 5), box(0, 1), 1)).isCloseTo(2, within(1e-7));
    }

    @Test
    void overlapGivesZeroAndNonFiniteGivesZeroNotInfinity() {
        assertThat(IntervalScorer.separation(box(0, 3), box(2, 5), 0)).isZero();
        assertThat(IntervalScorer.separation(box(0, 1), box(1.5, 2), 1)).isZero();
        assertThat(IntervalScorer.separation(box(0, 1), box(Double.NaN, Double.NaN), 0)).isZero();
    }

    @Test
    void euclideanGapsOverCoordinates() {
        IntervalScorer.Box a = new IntervalScorer.Box(new double[]{0, 0}, new double[]{1, 1});
        IntervalScorer.Box b = new IntervalScorer.Box(new double[]{4, 5}, new double[]{6, 6});
        assertThat(IntervalScorer.separation(a, b, 0)).isCloseTo(5, within(1e-7));
    }

    @Test
    void rankDeficientDesignIsUnavailable() {
        assertThat(IntervalScorer.inverseNormal(new double[][]{{1, 2}, {2, 4}})).isEqualTo(Optional.empty());
        assertThat(IntervalScorer.inverseNormal(new double[][]{{1, 0}, {0, 2}})).isPresent();
    }
}
