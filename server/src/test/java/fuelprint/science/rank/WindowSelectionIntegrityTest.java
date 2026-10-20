package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.Window;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WindowSelectionIntegrityTest {
    @Test void nonfiniteTimesAndValidSecondsCannotQualifyAsAThirtySecondWindow() {
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            for(Window w:List.of(new Window("start","N2","SO2_ppb",bad,330,30,100.0,6.0,null),
                    new Window("end","N2","SO2_ppb",300,bad,30,100.0,6.0,null),
                    new Window("valid","N2","SO2_ppb",300,330,bad,100.0,6.0,null))) {
                var selection=WindowSelection.select("run",List.of(w),InletTrack.fixedNodes(WorldProfile.load()),WorldProfile.load());
                assertThat(selection.entries()).isEmpty();
                assertThat(selection.exclusions()).singleElement().satisfies(e->assertThat(e.reason()).isEqualTo("INCOMPLETE_WINDOW"));
            }
        }
    }
    @Test void aRecordedFixedWindowNeedsItsMeasuredReferenceJustLikeAVehicleWindow() {
        Window collection=new Window("col","fixed-uuid","SO2_ppb",300,330,30,100.0,6.0,"missing");
        InletTrack.Source actual=(run,w)->Optional.of(new InletTrack(List.of(new InletTrack.Sample(0,new Enu(120,100,3))),false,true));
        var selected=WindowSelection.select("run",List.of(collection),actual,WorldProfile.load());
        assertThat(selected.entries()).isEmpty();
        assertThat(selected.exclusions()).singleElement().satisfies(e->assertThat(e.reason()).isEqualTo("BACKGROUND_WINDOW_UNKNOWN"));
    }
}
