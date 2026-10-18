package fuelprint.science.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import fuelprint.api.Enu;
import fuelprint.api.InletPose;
import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.api.ReadingBatch;
import fuelprint.science.truth.EmissionPlan;
import fuelprint.science.world.PuffWorld;
import fuelprint.science.world.ScienceWorld;
import fuelprint.science.world.WorldProfile;
import org.junit.jupiter.api.Test;

class SensorFieldTest {

    private final WorldProfile profile = WorldProfile.load();
    private final List<InletPose> inlets = List.of(
            new InletPose("id-n1", "N1", new Enu(-20, 60, 3)),
            new InletPose("id-n2", "N2", new Enu(120, 100, 3)),
            new InletPose("id-n3", "N3", new Enu(280, 140, 3)),
            new InletPose("id-n4", "N4", new Enu(400, -100, 3)),
            new InletPose("id-drone", "DRONE-1", new Enu(0, 0, 2)));

    private ScienceWorld emptyWorld() {
        ScienceWorld w = new ScienceWorld(e -> { });
        w.load("run", new PuffWorld(profile), new EmissionPlan(List.of()));
        return w;
    }

    private List<ReadingBatch> run(SensorField f, ScienceWorld w, int seconds) {
        List<ReadingBatch> out = new ArrayList<>();
        for (int i = 1; i <= seconds * 20; i++) {
            w.step(0.05);
            out.add(f.observe(i * 0.05, inlets));
        }
        return out;
    }

    private static double mean(List<ReadingBatch> batches, String inlet, String channel) {
        return batches.stream().flatMap(b -> b.readings().stream())
                .filter(r -> r.sensorOrVehicleId().equals(inlet) && r.channel().equals(channel))
                .mapToDouble(Reading::value).average().orElseThrow();
    }

    @Test
    void nothingIsObservedBeforeARunIsLoaded() {
        SensorField f = new SensorField(new ScienceWorld(e -> { }));
        assertThat(f.observe(1.0, inlets).readings()).isEmpty();
    }

    @Test
    void everyInletGetsGasParticleAndFlowReadingsLabelledSynthetic() {
        ScienceWorld w = emptyWorld();
        SensorField f = new SensorField(w);
        f.reset(5, ZonedDateTime.now(), false);
        w.step(0.05);
        ReadingBatch b = f.observe(0.05, inlets);
        assertThat(b.readings()).hasSize(5 * 5);
        for (InletPose p : inlets) {
            List<Reading> mine = b.readings().stream().filter(r -> r.sensorOrVehicleId().equals(p.sensorOrVehicleId()))
                    .toList();
            assertThat(mine).extracting(Reading::channel)
                    .containsExactlyInAnyOrder("SO2_ppb", "CO2_ppm", "PM_ugm3", "SO2_flow_L_min", "CO2_flow_L_min");
            assertThat(mine).allSatisfy(r -> {
                assertThat(r.origin()).isEqualTo(Origin.SYNTHETIC_TEACHING);
                assertThat(r.quality()).isNotEmpty();
                assertThat(r.value()).isNotNull();
            });
        }
        Reading so2Flow = b.readings().stream().filter(r -> r.channel().equals("SO2_flow_L_min")).findFirst().orElseThrow();
        Reading co2Flow = b.readings().stream().filter(r -> r.channel().equals("CO2_flow_L_min")).findFirst().orElseThrow();
        assertThat(so2Flow.value()).isEqualTo(0.5);
        assertThat(co2Flow.value()).isEqualTo(0.3);
        Reading pm = b.readings().stream().filter(r -> r.channel().equals("PM_ugm3")).findFirst().orElseThrow();
        assertThat(pm.unit()).isEqualTo("ug_m3");
        assertThat(pm.quality()).contains("OPTICAL_ESTIMATE");
    }

    @Test
    void cleanAirSitsAtFivePpbSo2And420PpmCo2() {
        ScienceWorld w = emptyWorld();
        SensorField f = new SensorField(w);
        f.reset(77, ZonedDateTime.now(), false);
        List<ReadingBatch> batches = run(f, w, 900);
        assertThat(mean(batches, "id-n2", "SO2_ppb")).isCloseTo(5.0, within(2.0));
        assertThat(mean(batches, "id-n2", "CO2_ppm")).isCloseTo(420.0, within(1.5));
        assertThat(mean(batches, "id-drone", "SO2_ppb")).isCloseTo(5.0, within(2.0));
    }

    @Test
    void theBackgroundHookMovesTheSo2Level() throws Exception {
        String json = "{\"median_ppb\": {\"1\": [" + "12,".repeat(23) + "12]}}";
        BackgroundProfile p = BackgroundProfile.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        ScienceWorld w = emptyWorld();
        SensorField f = new SensorField(w, p);
        f.reset(77, ZonedDateTime.of(2026, 1, 15, 2, 0, 0, 0, BackgroundProfile.IST), false);
        assertThat(f.backgroundSo2Ppb(10)).isEqualTo(12.0);
        List<ReadingBatch> batches = run(f, w, 900);
        assertThat(mean(batches, "id-n2", "SO2_ppb")).isCloseTo(12.0, within(2.0));
    }

    @Test
    void theSameSeedGivesTheSameReadings() {
        List<ReadingBatch> a;
        List<ReadingBatch> b;
        {
            ScienceWorld w = emptyWorld();
            SensorField f = new SensorField(w);
            f.reset(9, ZonedDateTime.now(), false);
            a = run(f, w, 30);
        }
        {
            ScienceWorld w = emptyWorld();
            SensorField f = new SensorField(w);
            f.reset(9, ZonedDateTime.now(), false);
            b = run(f, w, 30);
        }
        assertThat(a).isEqualTo(b);
    }

    @Test
    void theCollectionFaultOnlyHitsTheSo2BranchOfAVehicleAfterSecondTen() {
        ScienceWorld w = emptyWorld();
        SensorField f = new SensorField(w);
        f.reset(3, ZonedDateTime.now(), true);
        run(f, w, 20);
        f.collectionStarted("id-drone", 20.0);
        List<ReadingBatch> batches = new ArrayList<>();
        for (int i = 401; i <= 401 + 30 * 20; i++) {
            w.step(0.05);
            batches.add(f.observe(i * 0.05, inlets));
        }
        Reading before = pick(batches, "id-drone", "SO2_ppb", 29.0);
        Reading after = pick(batches, "id-drone", "SO2_ppb", 31.0);
        assertThat(before.quality()).containsExactly("OK");
        assertThat(after.quality()).containsExactly("FLOW_FAULT");
        assertThat(pick(batches, "id-drone", "SO2_flow_L_min", 31.0).value()).isZero();
        assertThat(pick(batches, "id-drone", "CO2_ppm", 31.0).quality()).containsExactly("OK");
        assertThat(pick(batches, "id-n2", "SO2_ppb", 31.0).quality()).containsExactly("OK");
        // the raw number keeps the stale value: it does not drop to zero
        Reading later = pick(batches, "id-drone", "SO2_ppb", 49.0);
        assertThat(later.rawValue()).isEqualTo(after.rawValue());
    }

    private static Reading pick(List<ReadingBatch> batches, String inlet, String channel, double t) {
        return batches.stream().flatMap(b -> b.readings().stream())
                .filter(r -> r.sensorOrVehicleId().equals(inlet) && r.channel().equals(channel)
                        && Math.abs(r.simTimeS() - t) < 1e-6)
                .findFirst().orElseThrow();
    }

    @Test
    void withoutTheFaultFlagNothingFails() {
        ScienceWorld w = emptyWorld();
        SensorField f = new SensorField(w);
        f.reset(3, ZonedDateTime.now(), false);
        f.collectionStarted("id-drone", 0.0);
        List<ReadingBatch> batches = run(f, w, 30);
        assertThat(pick(batches, "id-drone", "SO2_ppb", 25.0).quality()).containsExactly("OK");
    }
}
