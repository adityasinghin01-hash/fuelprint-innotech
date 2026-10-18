package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.science.sensor.GasChannel;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import fuelprint.api.InletHistory;
import fuelprint.api.InletSample;
import fuelprint.api.Window;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ResponseModelTest {
    @Test
    void hoveringDuringCollectionStillIncludesTheResponseToTheFlightIntoThePlume() {
        InletHistory history = (run, id, start, end) -> Optional.of(start < 50
                ? List.of(new InletSample(start, new Enu(0, 0, 0)),
                        new InletSample(49.95, new Enu(0, 0, 0)),
                        new InletSample(50, new Enu(1, 0, 0)), new InletSample(end, new Enu(1, 0, 0)))
                : List.of(new InletSample(start, new Enu(1, 0, 0)),
                        new InletSample(end, new Enu(1, 0, 0))));
        Window window = new Window("window", "drone", "SO2_ppb", 60, 90, 30, 75.0, 6.0, null);
        var track = InletTrack.history(history).track("run", window).orElseThrow();
        double predicted = ResponseModel.TEACHING.windowMean(Gas.SO2, track, 60, 90,
                p -> p.east() >= 1 ? 100 : 0);
        assertThat(predicted).isBetween(20.0, 90.0);
    }

    @ParameterizedTest
    @EnumSource(Gas.class)
    void stepResponseMatchesTheWorldLineChamberAndGasSensor(Gas gas) {
        double dt = 0.05;
        var rng = new SplittableRandom(1);
        var channel = new GasChannel(gas == Gas.SO2 ? 0.5 : 0.3,
                gas == Gas.SO2 ? 60 : 30, GasChannel.CHAMBER_T90_S, 0, 0, 0, rng);
        var track = new InletTrack(List.of(new InletTrack.Sample(0, new Enu(0, 0, 0)),
                new InletTrack.Sample(9.95, new Enu(0, 0, 0)),
                new InletTrack.Sample(10, new Enu(1, 0, 0))));
        double sum = 0;
        for (int tick = 1; tick <= 1200; tick++) {
            double time = tick * dt;
            sum += channel.advance(time >= 10 ? 100 : 0, 1, dt, false, rng).value();
        }
        double predicted = ResponseModel.TEACHING.windowMean(gas, track, 0, 60,
                p -> p.east() >= 1 ? 100 : 0);
        assertThat(predicted).isCloseTo(sum / 1200, offset(1e-8));
    }
}
