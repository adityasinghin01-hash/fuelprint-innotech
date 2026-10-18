package fuelprint.science.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PuffWorldTest {

    private final WorldProfile profile = WorldProfile.load();

    private PuffWorld worldWithS02() {
        PuffWorld w = new PuffWorld(profile);
        w.setEmission("S02", 0.5, 500);
        return w;
    }

    @Test
    void eachTickAddsOnePuffOfQTimesZeroPointZeroFiveSeconds() {
        PuffWorld w = worldWithS02();
        w.step(1.0);
        assertThat(w.puffCount()).isEqualTo(20);
        assertThat(w.totalMassG("S02", "SO2")).isCloseTo(0.5, within(1e-9));
        assertThat(w.totalMassG("S02", "CO2")).isCloseTo(500.0, within(1e-6));
        assertThat(w.timeS()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void puffMassIsConservedWhenTheWindChanges() {
        PuffWorld w = worldWithS02();
        w.step(30);
        double before = w.totalMassG("S02", "SO2");
        double[] centreBefore = w.puffCentre(0);
        w.setWind(180, 5.0); // now from the south
        w.step(30);
        double afterTurn = w.totalMassG("S02", "SO2");
        // 30 s more of emission only: 0.5 g/s x 30 s
        assertThat(afterTurn - before).isCloseTo(15.0, within(1e-6));
        assertThat(before).isCloseTo(15.0, within(1e-6));
        // the first puff kept its own history: 30 s east at 3 m/s, then 30 s north at 5 m/s
        double[] centreAfter = w.puffCentre(0);
        assertThat(centreAfter[0] - centreBefore[0]).isCloseTo(0.0, within(1e-6));
        assertThat(centreAfter[1] - centreBefore[1]).isCloseTo(150.0, within(1e-6));
        assertThat(w.windHistory()).hasSize(2);
    }

    @Test
    void windFromWestBlowsEastAtThreeMetresPerSecond() {
        PuffWorld w = worldWithS02();
        w.step(0.05);
        double[] c0 = w.puffCentre(0);
        w.step(10);
        double[] c1 = w.puffCentre(0);
        assertThat(c1[0] - c0[0]).isCloseTo(30.0, within(1e-6));
        assertThat(c1[1] - c0[1]).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void switchingASourceOffKeepsItsPuffs() {
        PuffWorld w = worldWithS02();
        w.step(20);
        int puffs = w.puffCount();
        double mass = w.totalMassG("S02", "SO2");
        w.setEmission("S02", 0, 0);
        w.step(20);
        assertThat(w.puffCount()).isEqualTo(puffs);
        assertThat(w.totalMassG("S02", "SO2")).isCloseTo(mass, within(1e-12));
        // the old smoke is still carried downwind and still measurable
        assertThat(w.concentrationAt(-10, 100, 35).so2Ppb()).isGreaterThan(1.0);
    }

    @Test
    void thePuffCapPausesInsteadOfDropping() {
        PuffWorld w = new PuffWorld(profile, 100, 900);
        w.setEmission("S02", 0.5, 500);
        w.setEmission("S03", 0.5, 500);
        double advanced = w.step(60);
        assertThat(w.capacityReached()).contains(PuffWorld.Capacity.PUFF_CAP);
        assertThat(w.puffCount()).isEqualTo(100);
        assertThat(advanced).isCloseTo(2.5, within(1e-9)); // 50 ticks x 2 puffs
        double t = w.timeS();
        double massS02 = w.totalMassG("S02", "SO2");
        assertThat(w.step(10)).isZero();
        assertThat(w.timeS()).isEqualTo(t);
        assertThat(w.puffCount()).isEqualTo(100);
        assertThat(w.totalMassG("S02", "SO2")).isEqualTo(massS02);
    }

    @Test
    void theLiveTimeCapPausesAt900SecondsAndKeepsEveryPuff() {
        PuffWorld w = new PuffWorld(profile, 160_000, 900);
        w.setEmission("S02", 0.5, 500);
        for (int i = 0; i < 100; i++) {
            w.step(10);
        }
        assertThat(w.timeS()).isCloseTo(900.0, within(1e-6));
        assertThat(w.capacityReached()).contains(PuffWorld.Capacity.TIME_CAP);
        assertThat(w.puffCount()).isEqualTo(18_000);
        assertThat(w.step(1)).isZero();
        assertThat(w.puffCount()).isEqualTo(18_000);
    }

    @Test
    void liveEnvelopeComesFromTheProfile() {
        assertThat(profile.liveSeconds()).isEqualTo(900.0);
        assertThat(profile.livePuffCap()).isEqualTo(160_000);
    }

    @Test
    void varianceStartsAtFourAndGrowsByTwoKTimesAge() {
        PuffWorld w = worldWithS02();
        assertThat(w.horizontalVariance(0)).isEqualTo(4.0);
        assertThat(w.horizontalVariance(10)).isCloseTo(4.0 + 2 * 3 * 10, within(1e-12));
        assertThat(w.verticalVariance(10)).isCloseTo(4.0 + 2 * 1 * 10, within(1e-12));
    }

    @Test
    void aReflectedGaussianPuffIntegratesToItsMassOverZAtLeastZero() {
        PuffWorld w = new PuffWorld(profile);
        w.setEmission("S02", 0.5, 500);
        w.step(0.05); // one puff of 0.025 g SO2
        w.step(5.0);
        double[] c = w.puffCentre(0);
        double age = w.timeS();
        double sx = Math.sqrt(w.horizontalVariance(age));
        double sz = Math.sqrt(w.verticalVariance(age));
        double h = 0.5; // grid step
        double sum = 0;
        double half = 6 * sx;
        for (double x = c[0] - half; x <= c[0] + half; x += h) {
            for (double y = c[1] - half; y <= c[1] + half; y += h) {
                for (double z = 0; z <= c[2] + 6 * sz; z += h) {
                    sum += w.kernelGramsPerM3(0, x, y, z)[0] * h * h * h;
                }
            }
        }
        // the single first puff carries 0.025 g; the other puffs are far away, so integrate only puff 0
        assertThat(sum).isCloseTo(0.5 * 0.05, within(0.5 * 0.05 * 0.02));
    }

    @Test
    void ppbConversionUsesTheStandardMolarVolume() {
        // 1 g/m3 SO2 at 298.15 K and 101325 Pa is about 381.9 ppm = 381,900 ppb; CO2 about 555.9 ppm
        assertThat(Units.so2GramsPerM3ToPpb(1.0)).isCloseTo(381_900.0, within(100.0));
        assertThat(Units.co2GramsPerM3ToPpm(1.0)).isCloseTo(555.9, within(0.5));
    }

    @Test
    void theResourceCopyMatchesTheContractProfile() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode contract = m.readTree(Files.readString(Path.of("..", "contracts", "reference", "world-profile.json")));
        JsonNode copy = m.readTree(getClass().getResourceAsStream(WorldProfile.RESOURCE));
        assertThat(copy).isEqualTo(contract);
        assertThat(profile.sources()).hasSize(6);
        assertThat(profile.source("S02").orElseThrow().effectiveHeight()).isEqualTo(35.0);
        assertThat(profile.source("S03").orElseThrow().effectiveHeight()).isEqualTo(45.0);
        assertThat(profile.nodes()).extracting(WorldProfile.Node::id).containsExactly("N1", "N2", "N3", "N4");
    }
}
