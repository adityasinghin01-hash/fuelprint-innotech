package fuelprint.science.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class SensorChainTest {

    @Test
    void responseReaches90PercentOfAStepAtT90() {
        for (double t90 : new double[] {60.0, 30.0}) {
            FirstOrderResponse r = new FirstOrderResponse(t90, 0.0);
            int steps = (int) Math.round(t90 / 0.05);
            double v = 0;
            for (int i = 0; i < steps; i++) {
                v = r.update(100.0, 0.05);
            }
            assertThat(v).as("T90 %s", t90).isCloseTo(90.0, within(1e-6));
        }
    }

    @Test
    void theExactUpdateDoesNotDependOnTheStepLength() {
        FirstOrderResponse a = new FirstOrderResponse(60, 0);
        FirstOrderResponse b = new FirstOrderResponse(60, 0);
        for (int i = 0; i < 1200; i++) {
            a.update(100, 0.05);
        }
        for (int i = 0; i < 60; i++) {
            b.update(100, 1.0);
        }
        assertThat(a.value()).isCloseTo(b.value(), within(1e-9));
    }

    @Test
    void theGasChannelReaches90PercentAtT90WithoutChamberOrNoise() {
        GasChannel c = new GasChannel(0.5, 60.0, 0.0, 0.0, 0.8, 0.0, new SplittableRandom(1));
        SplittableRandom rng = new SplittableRandom(2);
        GasChannel.Output o = null;
        // the 18.85 mL line holds 2.26 s of air at 0.5 L/min, so the step reaches the sensor 2.26 s late
        for (int i = 0; i < 1200 + 46; i++) {
            o = c.advance(100.0, 1.0, 0.05, i % 20 == 0, rng);
        }
        assertThat(c.responseState()).isBetween(89.0, 91.0);
        assertThat(o.quality()).containsExactly("OK");
    }

    @Test
    void aFailedFlowKeepsTheStaleValueAndRawNumberAndAddsNoVolume() {
        GasChannel c = new GasChannel(0.5, 60.0, 8.0, 5.0, 0.8, 5.0, new SplittableRandom(1));
        SplittableRandom rng = new SplittableRandom(3);
        GasChannel.Output last = null;
        double volume = 0;
        for (int i = 0; i < 400; i++) {
            last = c.advance(80.0, 1.0, 0.05, i % 20 == 0, rng);
            volume += last.volumeL();
        }
        assertThat(volume).isCloseTo(0.5 * 20.0 / 60.0, within(1e-9));
        double stale = last.value();
        double staleRaw = last.raw();
        double state = c.responseState();
        for (int i = 0; i < 400; i++) {
            GasChannel.Output o = c.advance(500.0, 0.0, 0.05, i % 20 == 0, rng);
            assertThat(o.quality()).containsExactly("FLOW_FAULT");
            assertThat(o.value()).isEqualTo(stale);
            assertThat(o.raw()).isEqualTo(staleRaw);
            assertThat(o.volumeL()).isZero();
            assertThat(o.flowLMin()).isZero();
        }
        assertThat(c.responseState()).isEqualTo(state);
        // flow back: the line still holds old air, then the sensor moves again
        GasChannel.Output back = c.advance(500.0, 1.0, 0.05, false, rng);
        assertThat(back.quality()).containsExactly("OK");
        assertThat(back.volumeL()).isGreaterThan(0.0);
    }

    @Test
    void aFlowOutsideFivePercentIsFlagged() {
        GasChannel c = new GasChannel(0.5, 60.0, 8.0, 5.0, 0.8, 5.0, new SplittableRandom(1));
        GasChannel.Output o = c.advance(5.0, 0.90, 0.05, false, new SplittableRandom(1));
        assertThat(o.quality()).containsExactly("FLOW_OUT_OF_RANGE");
        assertThat(o.flowLMin()).isCloseTo(0.45, within(1e-12));
    }

    @Test
    void transportFollowsTheSampledVolumeNotAClock() {
        InletLine line = new InletLine(0.0);
        assertThat(InletLine.VOLUME_L).isCloseTo(0.0188496, within(1e-7));
        // 0.5 L/min: 18.85 mL needs 2.262 s
        double dv = 0.5 * 0.05 / 60.0;
        int firstClean = -1;
        for (int i = 1; i <= 100; i++) {
            double out = line.push(100.0, dv);
            if (out > 50 && firstClean < 0) {
                firstClean = i;
            }
        }
        assertThat(firstClean * 0.05).isBetween(2.2, 2.4);
        // with the pump stopped nothing moves, however long it waits
        InletLine stopped = new InletLine(0.0);
        for (int i = 0; i < 1000; i++) {
            assertThat(stopped.push(100.0, 0.0)).isNaN();
        }
        assertThat(stopped.heldL()).isCloseTo(InletLine.VOLUME_L, within(1e-12));
        // a slow flow delays the same air for longer
        InletLine slow = new InletLine(0.0);
        double dvSlow = 0.25 * 0.05 / 60.0;
        int slowFirst = -1;
        for (int i = 1; i <= 200 && slowFirst < 0; i++) {
            if (slow.push(100.0, dvSlow) > 50) {
                slowFirst = i;
            }
        }
        assertThat(slowFirst).isGreaterThan(firstClean);
    }

    @Test
    void theNoiseHasTheStationarySpreadOfAnAr1() {
        SplittableRandom rng = new SplittableRandom(11);
        Ar1 a = new Ar1(0.8, 5.0, rng);
        double sum = 0;
        double sq = 0;
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            double v = a.next(rng);
            sum += v;
            sq += v * v;
        }
        double sd = Math.sqrt(sq / n - Math.pow(sum / n, 2));
        assertThat(sd).isCloseTo(5.0 / Math.sqrt(1 - 0.64), within(0.2));
    }
}
