package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.Window;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class JointCovarianceFitTest {
    @Test void twoGasAmplitudesAndResidualUseTheWholeCrossGasCovariance() {
        var engine = new FitEngine(WorldProfile.load(), ResponseModel.TEACHING);
        var track = InletTrack.fixed(KernelWindows.AERIAL);
        var entries = new java.util.ArrayList<WindowSelection.Entry>();
        double[][] design = new double[4][2];
        double[] observed = new double[4];
        int row = 0;
        // Different BG/collection intervals ensure correlated residuals affect both gas amplitudes.
        for (Gas gas : Gas.values()) {
            var bg = new Window("bg-"+gas, "joint-instrument", gas.channel(), 0, 30, 30, null, 1.0, null);
            for (int sample = 0; sample < 2; sample++) {
                double start = sample == 0 ? 300 : 600;
                double unit = engine.unitWindow(gas, "S02", Member.all().get(13), KernelWindows.WIND,
                        track, 0, 30, start, start+30);
                design[row][gas.ordinal()] = unit;
                observed[row] = unit * (gas == Gas.SO2 ? 5 : 5000) + (sample == 0 ? 1 : -1);
                var window = new Window("joint-"+row, "joint-instrument", gas.channel(), start, start+30,
                        30, observed[row], gas == Gas.SO2 ? 6.0 : 3.0, bg.id());
                entries.add(new WindowSelection.Entry(window, gas, track, bg)); row++;
            }
        }
        var covariance = new org.apache.commons.math3.linear.Array2DRowRealMatrix(WindowCovariance.of(entries));
        // Direct GLS normal equations are independent of production Cholesky whitening and NNLS.
        var inverse = new org.apache.commons.math3.linear.LUDecomposition(covariance).getSolver().getInverse();
        var a = new org.apache.commons.math3.linear.Array2DRowRealMatrix(design);
        var y = new org.apache.commons.math3.linear.ArrayRealVector(observed);
        var normal = a.transpose().multiply(inverse);
        var expected = new org.apache.commons.math3.linear.LUDecomposition(normal.multiply(a))
                .getSolver().solve(normal.operate(y));
        for (double amplitude : expected.toArray()) assertThat(amplitude).isPositive();
        assertThat(covariance.getEntry(0,2)).isNotZero();
        var fit = engine.fit(new Explanation("S02", List.of("S02")), KernelWindows.WIND, entries, new HashMap<>())
                .members().get(13);
        assertThat(fit.usable()).as(fit.failure()).isTrue();
        assertThat(fit.joint().gases()).containsExactly(Gas.SO2, Gas.CO2);
        assertThat(fit.gases().get(Gas.SO2).amplitudes()[0]).isCloseTo(expected.getEntry(0), offset(1e-7));
        assertThat(fit.gases().get(Gas.CO2).amplitudes()[0]).isCloseTo(expected.getEntry(1), offset(1e-5));
        var residual = y.subtract(a.operate(expected));
        assertThat(fit.sse()).isCloseTo(residual.dotProduct(inverse.operate(residual)), offset(1e-8));
    }

    @Test void fittedAmplitudeUsesCorrelatedBackgroundRatherThanIndependentWindowWeights() {
        var engine=new FitEngine(WorldProfile.load(),ResponseModel.TEACHING);
        var track=InletTrack.fixed(KernelWindows.AERIAL);
        var bg=new Window("bg","instrument",Gas.SO2.channel(),0,30,30,null,4.2,null);
        var first=new Window("one","instrument",Gas.SO2.channel(),300,330,30,100.0,10.0,"bg");
        var second=new Window("two","instrument",Gas.SO2.channel(),600,630,30,160.0,20.0,"bg");
        var entries=List.of(new WindowSelection.Entry(first,Gas.SO2,track,bg),
                new WindowSelection.Entry(second,Gas.SO2,track,bg));
        double[][] c=WindowCovariance.of(entries);
        double a0=engine.unitWindow(Gas.SO2,"S02",Member.all().get(13),KernelWindows.WIND,track,0,30,300,330);
        double a1=engine.unitWindow(Gas.SO2,"S02",Member.all().get(13),KernelWindows.WIND,track,0,30,600,630);
        double expectedQ=(a0*(c[1][1]*100-c[0][1]*160)+a1*(c[0][0]*160-c[0][1]*100))
                /(c[1][1]*a0*a0+c[0][0]*a1*a1-2*c[0][1]*a0*a1);
        var fit=engine.fit(new Explanation("S02",List.of("S02")),KernelWindows.WIND,entries,new HashMap<>())
                .members().get(13);
        assertThat(fit.gases().get(Gas.SO2).amplitudes()[0]).isCloseTo(expectedQ,offset(1e-8));
        assertThat(fit.joint().gases()).containsExactly(Gas.SO2);
        assertThat(fit.joint().whitenedDesign()).hasDimensions(2,1);
    }
}
