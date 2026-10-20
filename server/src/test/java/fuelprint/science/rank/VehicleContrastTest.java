package fuelprint.science.rank;

import fuelprint.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class VehicleContrastTest {
    private final WorldProfile world = WorldProfile.load();
    private final FitEngine engine = new FitEngine(world, ResponseModel.TEACHING);
    private final InletTrack.Source vehicle = InletTrack.history((run, id, from, to) ->
            Optional.of(List.of(new InletSample(from, new Enu(0, 100, 35)),
                    new InletSample(to, new Enu(0, 100, 35)))));

    private Window background() {
        return new Window("bg", "vehicle", "SO2_ppb", 200, 230, 30, null, 4.2, null);
    }

    private Window collection() {
        return new Window("collection", "vehicle", "SO2_ppb", 380, 410, 30, 0.0, 6.0, "bg");
    }

    @Test
    void settledSameLocationPredictsOnlyResidualBuildupRatherThanAbsolutePlume() {
        var selected = WindowSelection.select("run", List.of(background(), collection()), vehicle, world);
        assertThat(selected.entries()).hasSize(1);
        var design = engine.design(Gas.SO2, List.of("S02"), Member.all().get(13), new WindNow(270, 3),
                selected.entries(), new HashMap<>());
        double absolute = engine.unitResponse(Gas.SO2, "S02", Member.all().get(13), new WindNow(270,3),
                InletTrack.fixed(new Enu(0,100,35)), 380,410);
        assertThat(design[0][0]).isBetween(0.0,.01*absolute);
        assertThat(ResponseModel.TEACHING.windowContrast(Gas.SO2, selected.entries().get(0).track(),
                200,230,380,410,p -> 100)).isZero();
    }

    @Test
    void aVehicleWindowWithoutItsBackgroundIsExcludedInsteadOfAssumedClean() {
        var selected = WindowSelection.select("run", List.of(collection()), vehicle, world);
        assertThat(selected.entries()).isEmpty();
        assertThat(selected.exclusions()).anySatisfy(e -> {
            assertThat(e.readingWindow()).isEqualTo("collection");
            assertThat(e.reason()).isEqualTo("BACKGROUND_WINDOW_UNKNOWN");
        });
    }

    @Test
    void backgroundFromAnotherInstrumentCannotQualifyTheContrast() {
        var wrong = new Window("bg", "other-vehicle", "SO2_ppb", 200, 230, 30, null, 4.2, null);
        var selected = WindowSelection.select("run", List.of(wrong, collection()), vehicle, world);
        assertThat(selected.entries()).isEmpty();
        assertThat(selected.exclusions()).anyMatch(e -> e.readingWindow().equals("collection")
                && e.reason().equals("BACKGROUND_WINDOW_MISMATCH"));
    }
    @Test void vehicleTemporalBackgroundMeaningIsDeclaredForSignalAndNoSignalAnalyses() {
        for(double value:new double[]{0,100}) {
            Window w=new Window("collection","vehicle","SO2_ppb",380,410,30,value,6.0,"bg");
            var analysis=new CompatRanker(engine,new Cutoff(3,60,58,"test","test","test"))
                    .analyze(new AnalysisInput("run","investigation",410,List.of(background(),w),new WindNow(270,3)),vehicle).analysis();
            assertThat(analysis.limitations()).anyMatch(l->l.startsWith("MEASURED_BACKGROUND_MAY_CONTAIN_PLUME:"));
            assertThat(analysis.limitations()).anyMatch(l->l.startsWith("INITIAL_INLET_POSITION_ASSUMED:"));
            if(value==0) assertThat(analysis.outcome()).isEqualTo(Outcome.NO_SIGNAL);
        }
    }
}
