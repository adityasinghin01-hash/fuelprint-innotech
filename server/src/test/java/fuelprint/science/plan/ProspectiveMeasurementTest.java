package fuelprint.science.plan;

import fuelprint.api.Enu;
import fuelprint.science.rank.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class ProspectiveMeasurementTest {
    @Test void prospectiveRowUsesTheSameRecordedRouteAndTemporalBackgroundAsAttribution() {
        var engine=new FitEngine(WorldProfile.load(),ResponseModel.TEACHING);
        var track=new InletTrack(List.of(new InletTrack.Sample(0,new Enu(0,0,1.6)),
                new InletTrack.Sample(300,new Enu(0,0,1.6)),
                new InletTrack.Sample(345,new Enu(.4,100,34.6)),
                new InletTrack.Sample(735,new Enu(.4,100,34.6))),true);
        var measurement=new ProspectiveMeasurement(track,374,404,554,584,480,30);
        double expected=engine.unitWindow(Gas.SO2,"S02",Member.all().get(13),KernelWindows.WIND,track,
                374,404,554,584);
        assertThat(measurement.unit(engine,Gas.SO2,"S02",Member.all().get(13),KernelWindows.WIND))
                .isCloseTo(expected,offset(1e-12));
        assertThat(expected).isPositive().isLessThan(engine.unitResponse(Gas.SO2,"S02",Member.all().get(13),
                KernelWindows.WIND,InletTrack.fixed(new Enu(.4,100,34.6)),554,584));
        assertThat(measurement.sigma(Gas.SO2)).isGreaterThan(NoiseModel.enhancementStd(engine.world(),Gas.SO2));
    }
    @Test void computationBudgetStopsDuringInstrumentPropagation() {
        var engine=new FitEngine(WorldProfile.load(),ResponseModel.TEACHING);
        var measurement=new ProspectiveMeasurement(InletTrack.fixed(new Enu(0,100,35)),374,404,554,584,480,30);
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> measurement.unit(engine,Gas.SO2,"S02",Member.all().get(13),KernelWindows.WIND,
                () -> checks.incrementAndGet()>10)).isInstanceOf(FitEngine.BudgetExceeded.class);
        assertThat(checks.get()).isEqualTo(11);
    }
}
