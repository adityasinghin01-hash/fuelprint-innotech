package fuelprint.science.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.DoubleStream;

import fuelprint.api.ChannelStatus;
import fuelprint.api.Enu;
import fuelprint.api.Reading;
import fuelprint.api.RunContext;
import fuelprint.api.Trigger;
import fuelprint.science.support.HeadlessRun;
import fuelprint.science.sensor.SensorField;
import fuelprint.science.world.PuffWorld;
import fuelprint.science.world.WorldProfile;
import org.junit.jupiter.api.Test;

/**
 * Headless runs of the three department presets: the same calls the core world loop makes, no web server.
 * These test the world, sensors, detector and capture rules; they do not test any analysis outcome.
 */
class ScenarioRunsTest {

    private static final Enu TARGET = new Enu(0, 100, 35);

    private static double[] values(List<Reading> r) {
        return r.stream().mapToDouble(Reading::value).toArray();
    }

    @Test
    void sc1TriggersAtN2AndTheCaptureAtTheTargetShowsAClearSo2Enhancement() {
        HeadlessRun r = new HeadlessRun("SC1-NIGHT-PLUME");
        r.run(300);

        List<Trigger> n2 = r.triggersAt("N2");
        assertThat(n2).as("N2 trigger").isNotEmpty();
        Trigger t = n2.get(0);
        assertThat(t.rule()).isEqualTo("TEACH-DETECT-1");
        assertThat(t.channel()).isEqualTo("SO2_ppb");
        assertThat(t.intervals().get(0).startS()).isBetween(60.0, 200.0);
        assertThat(t.backgroundWindowId()).isNotNull();
        assertThat(r.windows).extracting(w -> w.id()).contains(t.backgroundWindowId());
        // the upwind box and the far box stay quiet
        assertThat(r.triggersAt("N1")).isEmpty();
        assertThat(r.triggersAt("N4")).isEmpty();
        // the alarm comes from SO2 only
        assertThat(r.triggers).allSatisfy(x -> assertThat(x.channel()).isEqualTo("SO2_ppb"));

        HeadlessRun.Capture c = r.flyAndCapture(TARGET, 120);
        assertThat(c.so2().status()).isEqualTo(ChannelStatus.VALID);
        assertThat(c.co2().status()).isEqualTo(ChannelStatus.VALID);
        assertThat(c.so2().validSeconds()).isEqualTo(30.0);
        double[] so2 = values(r.readings(r.droneId, SensorField.SO2, c.collectStartS(), c.collectEndS() - 1));
        double minOverCollection = DoubleStream.of(so2).min().orElseThrow();
        // clear: every collected second is far above the 5 ppb background and the ~9 ppb noise
        assertThat(minOverCollection).isGreaterThan(500.0);
        // and CO2 rises too (a separate, independent signal; it is not a fuel signature)
        assertThat(c.co2MeanPpm()).isGreaterThan(450.0);
    }

    @Test
    void theTargetReadingSeparatesS01FromS02() {
        // Steady smoke of each candidate alone: the drone at the target sees S02 much more strongly relative to
        // N2 than it would see S01, which is what the ranker relies on.
        WorldProfile p = WorldProfile.load();
        double[] ratio = new double[2];
        String[] src = {"S01", "S02"};
        double[] atTarget = new double[2];
        for (int i = 0; i < 2; i++) {
            PuffWorld w = new PuffWorld(p);
            w.setEmission(src[i], 20.0, 500.0);
            w.step(300);
            double drone = w.concentrationAt(TARGET.east(), TARGET.north(), TARGET.up()).so2Ppb();
            double n2 = w.concentrationAt(120, 100, 3).so2Ppb();
            atTarget[i] = drone;
            ratio[i] = drone / n2;
        }
        assertThat(atTarget[1]).isGreaterThan(500.0);
        assertThat(atTarget[0]).isGreaterThan(100.0);
        assertThat(atTarget[1] / atTarget[0]).as("S02 over S01 at the target").isGreaterThan(1.8);
        assertThat(ratio[1] / ratio[0]).as("target-to-N2 ratio differs between S02 and S01").isGreaterThan(1.8);
    }

    @Test
    void sc2BlockedInletRejectsSo2AndKeepsCo2Valid() {
        HeadlessRun r = new HeadlessRun("SC2-BLOCKED-INLET");
        r.run(300);
        assertThat(r.triggersAt("N2")).as("same start as SC1").isNotEmpty();

        HeadlessRun.Capture c = r.flyAndCapture(TARGET, 120);
        assertThat(c.so2().status()).isEqualTo(ChannelStatus.REJECTED);
        assertThat(c.so2().reasons()).contains("FLOW_FAULT");
        assertThat(c.so2().validSeconds()).isEqualTo(10.0);
        assertThat(c.so2().volumeL()).isBetween(0.0830, 0.0836);
        assertThat(c.co2().status()).isEqualTo(ChannelStatus.VALID);
        assertThat(c.co2().validSeconds()).isEqualTo(30.0);

        // the raw SO2 number still looks normal after the fault: stale, not zero
        List<Reading> so2 = r.readings(r.droneId, SensorField.SO2, c.collectStartS(), c.collectEndS());
        Reading atNine = so2.stream().filter(x -> Math.abs(x.simTimeS() - (c.collectStartS() + 9.0)) < 1e-6)
                .findFirst().orElseThrow();
        Reading atTwenty = so2.stream().filter(x -> Math.abs(x.simTimeS() - (c.collectStartS() + 20.0)) < 1e-6)
                .findFirst().orElseThrow();
        assertThat(atTwenty.quality()).containsExactly("FLOW_FAULT");
        assertThat(atTwenty.rawValue()).isGreaterThan(500.0);
        Reading atTen = so2.stream().filter(x -> Math.abs(x.simTimeS() - (c.collectStartS() + 10.0)) < 1e-6)
                .findFirst().orElseThrow();
        assertThat(atTwenty.rawValue()).isEqualTo(atTen.rawValue());
        assertThat(atTwenty.value()).isEqualTo(atTen.value());
        assertThat(atNine.quality()).containsExactly("OK");
        // the CO2 branch kept its flow
        assertThat(r.readings(r.droneId, SensorField.CO2_FLOW, c.collectStartS(), c.collectEndS()))
                .allSatisfy(x -> assertThat(x.value()).isEqualTo(0.3));
    }

    @Test
    void sc3ThePlumePassesOverTheBoxesWithoutAnyTrigger() {
        HeadlessRun r = new HeadlessRun("SC3-ABOVE-THE-BOXES");
        // a drone hovering in S03's 45 m plume sees it; the 3 m inlets of the boxes do not
        r.dronePath(t -> new Enu(280, 140, 45));
        r.run(450);
        for (String box : new String[] {"N1", "N2", "N3", "N4"}) {
            assertThat(r.triggersAt(box)).as(box).isEmpty();
        }
        assertThat(r.triggers).isEmpty();
        double[] drone = values(r.readings(r.droneId, SensorField.SO2, 300, 450));
        assertThat(DoubleStream.of(drone).average().orElseThrow()).as("plume overhead").isGreaterThan(500.0);
        double[] n3 = values(r.readings(r.boxId("N3"), SensorField.SO2, 150, 450));
        assertThat(DoubleStream.of(n3).max().orElseThrow()).as("N3 at 3 m").isLessThan(45.0);
    }

    @Test
    void sc3CaptureVerdictsStillWork() {
        HeadlessRun r = new HeadlessRun("SC3-ABOVE-THE-BOXES");
        r.run(100);
        HeadlessRun.Capture c = r.flyAndCapture(new Enu(280, 140, 45), 120);
        assertThat(c.so2().status()).isEqualTo(ChannelStatus.VALID);
        assertThat(c.so2MeanPpb()).isGreaterThan(500.0);
        assertThat(r.triggers).isEmpty();
    }

    @Test
    void aPresetAlwaysRunsTheSameWay() {
        HeadlessRun a = new HeadlessRun("SC1-NIGHT-PLUME");
        HeadlessRun b = new HeadlessRun("SC1-NIGHT-PLUME");
        a.run(120);
        b.run(120);
        assertThat(a.readings(a.boxId("N2"), SensorField.SO2, 0, 120).stream().map(Reading::value).toList())
                .isEqualTo(b.readings(b.boxId("N2"), SensorField.SO2, 0, 120).stream().map(Reading::value).toList());
    }

    @Test
    void presetsDifferAndAnUnknownPresetIsRefused() {
        HeadlessRun one = new HeadlessRun("SC1-NIGHT-PLUME");
        HeadlessRun three = new HeadlessRun("SC3-ABOVE-THE-BOXES");
        one.run(200);
        three.run(200);
        assertThat(one.world.current().orElseThrow().puffCount(S02())).isPositive();
        assertThat(three.world.current().orElseThrow().puffCount(S02())).isZero();
        assertThatThrownBy(() -> one.setup.startRun(new RunContext("r", "SC9-NOPE", "BLIND", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String S02() {
        return "S02";
    }

    @Test
    void startingARunAgainResetsTheWorldAndStoppingUnloadsIt() {
        HeadlessRun r = new HeadlessRun("SC1-NIGHT-PLUME");
        r.run(60);
        assertThat(r.world.current().orElseThrow().puffCount()).isPositive();
        r.setup.startRun(new RunContext("again", "SC3-ABOVE-THE-BOXES", "TEACHING", 1));
        assertThat(r.world.current().orElseThrow().timeS()).isZero();
        assertThat(r.world.current().orElseThrow().puffCount()).isZero();
        assertThat(r.world.runId()).contains("again");
        r.setup.stopRun("again");
        assertThat(r.world.current()).isEmpty();
        assertThat(r.sensors.observe(1.0, r.boxes).readings()).isEmpty();
    }

    @Test
    void theWindIsFromTheWestAtThreeMetresPerSecondAtTheStart() {
        HeadlessRun r = new HeadlessRun("SC1-NIGHT-PLUME");
        assertThat(r.world.wind().fromDeg()).isEqualTo(270.0);
        assertThat(r.world.wind().speedMS()).isEqualTo(3.0);
    }

    @Test
    void liveCapacityPublishesAnEventAndStopsTheWorld() {
        java.util.List<Object> events = new java.util.ArrayList<>();
        fuelprint.science.world.ScienceWorld w = new fuelprint.science.world.ScienceWorld(events::add);
        w.load("run-x", new PuffWorld(WorldProfile.load(), 10, 900),
                new fuelprint.science.truth.EmissionPlan(List.of(
                        new fuelprint.science.truth.EmissionPlan.Change(0, "S02", 1, 1))));
        w.step(2.0);
        w.step(1.0);
        assertThat(w.capacityReached()).isTrue();
        assertThat(events).hasSize(1);
        fuelprint.api.WorldCapacityReached e = (fuelprint.api.WorldCapacityReached) events.get(0);
        assertThat(e.reason()).isEqualTo("PUFF_CAP");
        assertThat(e.runId()).isEqualTo("run-x");
        assertThat(e.puffCount()).isEqualTo(10);
    }
}
