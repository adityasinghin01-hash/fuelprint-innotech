package fuelprint.field.world;

import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.field.profile.CaptureMethod;
import fuelprint.field.profile.WorldProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CaptureContinuityTest {
    private CaptureRun run() {
        var method = new CaptureMethod(0, 0, 30, 5, Map.of("SO2", 0.5));
        var run = new CaptureRun("attempt", "mission", "run", CaptureMethod.ID, List.of("SO2_ppb"), 0,
                method, new WorldProfile.Procedure(0, 0, 0, 0, 30, 0, 0));
        run.advance(0);
        return run;
    }

    private Reading reading(String channel, int time, double value) {
        return new Reading("drone", channel, value, value, channel.contains("flow") ? "L_min" : "ppb",
                time, List.of("OK"), Origin.SYNTHETIC_TEACHING);
    }

    @Test
    void missingFlowCannotCompleteACollection() {
        var run = run();
        for (int t = 0; t < 30; t++) run.accept(List.of(reading("SO2_ppb", t, 100)), true);
        assertThat(run.snapshot().counts().get("SO2_ppb").validSeconds()).isZero();
    }

    @Test
    void aMissingSecondBreaksTheConsecutiveRun() {
        var run = run();
        for (int t = 0; t <= 30; t++) {
            if (t == 15) continue;
            run.accept(List.of(reading("SO2_ppb", t, 100), reading("SO2_flow_L_min", t, 0.5)), true);
        }
        assertThat(run.snapshot().counts().get("SO2_ppb").validSeconds()).isEqualTo(15);
    }
}
