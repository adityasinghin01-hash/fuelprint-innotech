package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.Window;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class WindowCovarianceTest {
    private static WindowSelection.Entry contrast(String instrument, Gas gas, String bgId, double start) {
        Window bg = new Window(bgId, instrument, gas.channel(), 0, 30, 30, null, 4.2, null);
        Window c = new Window(instrument + gas + start, instrument, gas.channel(), start, start+30, 30,
                100.0, 0.1, bgId);
        return new WindowSelection.Entry(c, gas, InletTrack.fixed(new Enu(0,0,3)), bg);
    }

    @Test void reusedBackgroundProducesPositiveCovarianceAcrossSeparatedCollections() {
        var a = contrast("one", Gas.SO2, "bg", 300);
        var b = contrast("one", Gas.SO2, "bg", 600);
        double[][] covariance = WindowCovariance.of(List.of(a,b));
        double bgVariance = NoiseModel.ar1MeanVariance(.8, 5, 30);
        assertThat(covariance[0][1]).isGreaterThan(bgVariance);
        assertThat(covariance[0][1]).isCloseTo(covariance[1][0], offset(1e-12));
        assertThat(covariance[0][0]).isGreaterThan(2*bgVariance);
    }

    @Test void commonAmbientFluctuationCorrelatesDifferentInstrumentsAndGases() {
        var a = contrast("one", Gas.SO2, "bg1", 300);
        var b = contrast("two", Gas.CO2, "bg2", 300);
        double[][] covariance = WindowCovariance.of(List.of(a,b));
        assertThat(covariance[0][1]).isGreaterThan(0);
        assertThat(covariance[0][1]).isLessThan(Math.sqrt(covariance[0][0]*covariance[1][1]));
    }

    @Test void legacyWindowsWithoutTheirSampleOperatorKeepTheirDeclaredDiagonalUncertainty() {
        Window w = new Window("legacy", "test", Gas.SO2.channel(), 300,330,30,100.0,7.0,null);
        var e = new WindowSelection.Entry(w, Gas.SO2, InletTrack.fixed(new Enu(0,0,3)));
        assertThat(WindowCovariance.of(List.of(e))[0][0]).isEqualTo(49);
    }
    @Test void filteredCommonCovarianceMatchesIndependentInnovationBasisThroughRealGasChannels() {
        var first=contrast("one",Gas.SO2,"bg1",30);
        var second=contrast("two",Gas.CO2,"bg2",30);
        double expected=0;
        for(int shock=0;shock<=60;shock++) {
            double common=shock==0?1/Math.sqrt(1-.95*.95):0;
            var rng=new java.util.SplittableRandom(0);
            var so2=fuelprint.science.sensor.GasSettings.SO2.channel(0,0,common,rng);
            var co2=fuelprint.science.sensor.GasSettings.CO2.channel(0,0,.5*common,rng);
            double contrastSo2=0,contrastCo2=0;
            for(int tick=1;tick<=1200;tick++) {
                int secondTime=tick/20;
                if(tick%20==0) common=.95*common+(shock==secondTime?1:0);
                double a=so2.advance(common,1,.05,false,rng).value();
                double b=co2.advance(.5*common,1,.05,false,rng).value();
                if(tick%20==0) {
                    double weight=secondTime<=30?-1.0/30:1.0/30;
                    contrastSo2+=weight*a; contrastCo2+=weight*b;
                }
            }
            expected+=contrastSo2*contrastCo2;
        }
        assertThat(WindowCovariance.of(List.of(first,second))[0][1]).isCloseTo(expected,offset(1e-9));
    }
}
