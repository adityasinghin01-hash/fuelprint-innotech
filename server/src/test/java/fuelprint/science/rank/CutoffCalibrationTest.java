package fuelprint.science.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CutoffCalibrationTest {

    @Test
    void storedCutoffIsRecomputedByTheCalibrationRunnerWithItsOwnSeeds() throws Exception {
        FitEngine engine = new FitEngine(WorldProfile.load(), ResponseModel.TEACHING);
        CalibrationRunner.Computation c = CalibrationRunner.compute(engine);
        Cutoff stored = Cutoff.load();

        assertThat(c.episodes()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(c.rank()).isEqualTo(Cutoff.rankFor(c.episodes().size()));
        assertThat(stored.value()).isEqualTo(c.cutoff());
        assertThat(stored.episodes()).isEqualTo(c.episodes().size());
        assertThat(stored.rank()).isEqualTo(c.rank());
        assertThat(stored.sha256OfStatistics()).isEqualTo(c.sha256());
        assertThat(stored.value()).isNotCloseTo(Cutoff.FORBIDDEN_HISTORICAL,
                org.assertj.core.data.Offset.offset(1e-3));
        assertThat(c.episodes()).allMatch(e -> (e.seed() & 0xFFFF_FFFF_FF00_0000L) == CalibrationRunner.SEED_BASE);

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(Cutoff.RESOURCE)) {
            JsonNode n = new ObjectMapper().readTree(in);
            assertThat(n.path("seeds").asText()).contains("FPCAL").contains("never the SC1-SC3 seeds");
            assertThat(n.path("method").asText()).contains("CalibrationRunner");
            assertThat(n.path("rank_rule").asText()).isEqualTo("min(m, ceil(0.95 (m + 1)))");
            assertThat(n.path("domain").asText()).isNotBlank();
            assertThat(n.path("generator").asText()).isNotBlank();
        }
    }

    @Test
    void rankRule() {
        assertThat(Cutoff.rankFor(40)).isEqualTo(39);
        assertThat(Cutoff.rankFor(60)).isEqualTo(58);
        assertThat(Cutoff.rankFor(10)).isEqualTo(10);
    }

    @Test
    void historicalValueIsForbidden() {
        assertThatThrownBy(() -> new Cutoff(132.253985, 60, 58, "m", "s", "h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void worldProfileResourceIsAByteCopyOfTheContract() throws Exception {
        Path contract = Path.of("..", "contracts", "reference", "world-profile.json");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(WorldProfile.RESOURCE)) {
            assertThat(in.readAllBytes()).isEqualTo(Files.readAllBytes(contract));
        }
    }
}
