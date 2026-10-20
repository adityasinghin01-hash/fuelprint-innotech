package fuelprint.science.plan;

import org.junit.jupiter.api.Test;
import fuelprint.api.Enu;
import fuelprint.api.Window;
import fuelprint.science.rank.FitEngine;
import fuelprint.science.rank.WorldProfile;
import fuelprint.science.rank.ResponseModel;
import fuelprint.science.rank.Gas;
import fuelprint.science.rank.InletTrack;
import fuelprint.science.rank.Member;
import fuelprint.science.rank.FitSnapshot;
import fuelprint.science.rank.Explanation;
import fuelprint.science.rank.KernelWindows;
import fuelprint.science.rank.WindowSelection;
import fuelprint.science.rank.WindowCovariance;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.EnumMap;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Independent oracles for the prospective boxes (R3 doc 03). */
class IntervalScorerOracleTest {
    @Test void prospectiveTwoGasBoxUsesJointNoiseCoordinates() {
        var engine = new FitEngine(WorldProfile.load(),
                ResponseModel.TEACHING);
        var gases = List.of(Gas.SO2, Gas.CO2);
        var track = InletTrack.fixed(new Enu(.4,100,34.6));
        var measurement = new ProspectiveMeasurement(track,374,404,554,584,480,30);
        var member = Member.all().get(13);
        double[] centre = {2,3000};
        var fits = new EnumMap<Gas,FitEngine.GasFit>(Gas.class);
        for (int i=0;i<2;i++) fits.put(gases.get(i),new FitEngine.GasFit(gases.get(i),new double[]{centre[i]}));
        var joint = new FitEngine.JointFit(gases,centre,centre,new double[][]{{1,0},{0,1}},2,0);
        var fit = new FitEngine.MemberFit(member,fits,joint,0,2,null);
        var support = new FitSnapshot.Surviving(new Explanation("S02",List.of("S02")),List.of(fit));
        var snapshot = new FitSnapshot(List.of(),KernelWindows.WIND,
                0,2,Set.copyOf(gases),List.of(support),true);
        var entries = new ArrayList<WindowSelection.Entry>();
        double[] prediction = new double[2];
        for (int i=0;i<2;i++) {
            var gas = gases.get(i);
            var bg = new Window("bg-"+gas,"forecast",gas.channel(),374,404,30,null,1.0,null);
            var window = new Window("col-"+gas,"forecast",gas.channel(),554,584,30,0.0,1e-6,bg.id());
            entries.add(new WindowSelection.Entry(window,gas,track,bg));
            prediction[i] = measurement.unit(engine,gas,"S02",member,snapshot.wind())*centre[i];
        }
        double[][] covariance = WindowCovariance.of(entries);
        assertThat(covariance[0][1]).isPositive();
        // Explicit2x2 Cholesky inverse, independent of production Whitening.
        double l00 = Math.sqrt(covariance[0][0]);
        double l10 = covariance[1][0]/l00;
        double l11 = Math.sqrt(covariance[1][1]-l10*l10);
        double first = prediction[0]/l00;
        double second = (prediction[1]-l10*first)/l11;
        var box = IntervalScorer.outerBox(engine,snapshot,support,new Enu(.4,100,34.6),gases,measurement).orElseThrow();
        assertThat(box.lo()[0]).isCloseTo(first,within(1e-8));
        assertThat(box.hi()[0]).isCloseTo(first,within(1e-8));
        assertThat(box.lo()[1]).isCloseTo(second,within(1e-8));
        assertThat(box.hi()[1]).isCloseTo(second,within(1e-8));
        assertThat(second).isNotCloseTo(prediction[1]/Math.sqrt(covariance[1][1]),within(1e-5));
    }

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
