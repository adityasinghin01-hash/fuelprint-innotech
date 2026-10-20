package fuelprint.science.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;

class FullChainCalibrationRunnerTest {
    @TempDir Path temporary;

    @Test void evaluationDefaultsToFreshV4SeedsAndRejectsReusedNamespaces() throws Exception {
        Path output = temporary.resolve("evaluation.json");
        FullChainCalibrationRunner.main(new String[]{"evaluate", "1", output.toString(), ".5", "200", "3"});
        var report = new ObjectMapper().readTree(output.toFile());
        assertThat(report.path("namespace").asText()).isEqualTo("FPEVAL4");
        assertThat(report.path("requested_eligible_reached").asBoolean()).isTrue();
        assertThat(report.path("attempts").get(0).path("seed").asLong())
                .isEqualTo(0x4650455634000000L);
        for (String reused : List.of("FPCAL", "FPDEV", "FPEVAL", "FPWIND/0")) {
            assertThatThrownBy(() -> FullChainCalibrationRunner.main(new String[]{"evaluate", "1",
                    output.toString(), ".5", "200", "3", reused})).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void explicitFreshEvaluationNamespaceIsAcceptedAndHasNoRangeOverlap() throws Exception {
        Path output = temporary.resolve("explicit-evaluation.json");
        FullChainCalibrationRunner.main(new String[]{"evaluate", "1", output.toString(), ".5", "200", "3", "FPEVAL4"});
        assertThat(new ObjectMapper().readTree(output.toFile()).path("namespace").asText()).isEqualTo("FPEVAL4");
        long first = FullChainCalibrationRunner.seed("FPEVAL4", 0);
        long last = FullChainCalibrationRunner.seed("FPEVAL4", 999999);
        List<String> others = new ArrayList<>(List.of("FPCAL", "FPEVAL", "FPDEV"));
        for (int cell = 0; cell < 12; cell++) others.add("FPWIND/" + cell);
        for (String other : others) {
            long otherFirst = FullChainCalibrationRunner.seed(other, 0);
            long otherLast = FullChainCalibrationRunner.seed(other, 999999);
            assertThat(last < otherFirst || first > otherLast).as("disjoint from " + other).isTrue();
        }
    }

    @Test void evaluationOffsetSeparatesFinalQualificationFromCliProbes() throws Exception {
        Path output = temporary.resolve("offset-evaluation.json");
        FullChainCalibrationRunner.main(new String[]{"evaluate", "1", output.toString(), ".5", "200", "3", "FPEVAL4", "20"});
        var report = new ObjectMapper().readTree(output.toFile());
        assertThat(report.path("attempt_start_index").asInt()).isEqualTo(20);
        assertThat(report.path("attempts").get(0).path("seed").asLong()).isEqualTo(0x4650455634000014L);
        for (String offset : List.of("-1", "998001", "1000000")) {
            assertThatThrownBy(() -> FullChainCalibrationRunner.main(new String[]{"evaluate", "1", output.toString(),
                    ".5", "200", "3", "FPEVAL4", offset})).isInstanceOf(IllegalArgumentException.class);
        }
    }
    private Object call(String name, Class<?>[] types, Object... arguments) {
        try {
            return Class.forName("fuelprint.science.calibration.FullChainCalibrationRunner").getMethod(name, types).invoke(null, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Full-chain calibration must retain failures and use disjoint reproducible seeds", e);
        }
    }

    @Test void namespaceSeedsAreReproducibleAndDisjointFromScenarioSeeds() {
        long cal = (Long) call("seed", new Class<?>[]{String.class, int.class}, "FPCAL", 0);
        long eval = (Long) call("seed", new Class<?>[]{String.class, int.class}, "FPEVAL", 0);
        long dev = (Long) call("seed", new Class<?>[]{String.class, int.class}, "FPDEV", 0);
        long fresh = (Long) call("seed", new Class<?>[]{String.class, int.class}, "FPEVAL4", 0);
        assertThat(List.of(cal, eval, dev, fresh)).doesNotHaveDuplicates().doesNotContain(1001L, 1002L, 1003L);
        assertThat(call("seed", new Class<?>[]{String.class, int.class}, "FPCAL", 0)).isEqualTo(cal);
        assertThat(call("seed", new Class<?>[]{String.class, int.class}, "FPCAL", 1)).isEqualTo(cal + 1);
    }

    @Test void sixtyEligibleStatisticsUseTheFiftyEighthOrderStatistic() {
        List<Double> values = new ArrayList<>();
        for (int i = 60; i > 0; i--) values.add((double) i);
        assertThat(call("quantile", new Class<?>[]{List.class}, values)).isEqualTo(58.0);
        assertThatThrownBy(() -> call("quantile", new Class<?>[]{List.class}, values.subList(0, 39)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failedEligibleFitsCannotBeDroppedFromTheConformalDenominator() {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < 57; i++) values.add(1.0);
        values.add(null); values.add(null); values.add(null);
        assertThat((Double) call("quantile", new Class<?>[]{List.class}, values)).isPositive().isInfinite();
    }
    @Test void twelveWindCellsHaveDisjointSeedsFromEachOtherAndCalibrationEvaluation() {
        List<Long> seeds = new ArrayList<>();
        seeds.add((Long) call("seed", new Class<?>[]{String.class, int.class}, "FPCAL", 0));
        seeds.add((Long) call("seed", new Class<?>[]{String.class, int.class}, "FPEVAL", 0));
        for (int cell = 0; cell < 12; cell++) {
            long first = (Long) call("seed", new Class<?>[]{String.class, int.class}, "FPWIND/" + cell, 0);
            long last = (Long) call("seed", new Class<?>[]{String.class, int.class}, "FPWIND/" + cell, 999999);
            seeds.add(first); seeds.add(last);
        }
        assertThat(seeds).doesNotHaveDuplicates();
        assertThatThrownBy(() -> call("seed", new Class<?>[]{String.class, int.class}, "FPWIND/12", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
