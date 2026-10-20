package fuelprint.science.rank;

import fuelprint.api.*;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("real")
class ScienceRankerHistoryTest {
    private static List<String> surviving(Analysis analysis) {
        return analysis.candidates().stream().filter(c -> c.status() == CandidateStatus.SURVIVES && !c.isUnknown())
                .map(Candidate::explanationId).toList();
    }

    @Test
    void apiHistorySuppliesTheDroneWindowAfterTheFixedNodes() {
        List<String> requests = new ArrayList<>();
        InletHistory history = (run, vehicle, start, end) -> {
            requests.add(run + "/" + vehicle + "/" + start + "/" + end);
            return Optional.of(List.of(new InletSample(start, new Enu(-500, -500, 3)),
                    new InletSample(420, new Enu(-500, -500, 3)),
                    new InletSample(450, KernelWindows.AERIAL), new InletSample(end, KernelWindows.AERIAL)));
        };
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("real");
            context.registerBean(InletHistory.class, () -> history);
            context.register(ScienceRanker.class);
            context.refresh();
            ScienceRanker ranker = context.getBean(ScienceRanker.class);
            KernelWindows windows = new KernelWindows().s02AtN2();
            assertThat(surviving(ranker.analyze(windows.input()))).containsExactlyInAnyOrder("S01", "S02");
            assertThat(requests).isEmpty();
            List<Window> observations = new ArrayList<>(windows.windows());
            for (Gas gas : Gas.values()) {
                String bg = "BG-" + gas;
                observations.add(new Window(bg, "DRONE", gas.channel(), 360, 390, 30, null, 4.2, null));
                var track = new InletTrack(List.of(new InletTrack.Sample(0, new Enu(-500, -500, 3)),
                        new InletTrack.Sample(420, new Enu(-500, -500, 3)),
                        new InletTrack.Sample(450, KernelWindows.AERIAL),
                        new InletTrack.Sample(530, KernelWindows.AERIAL)), true);
                double response = windows.engine.unitWindow(gas, "S02", Member.all().get(13),
                        KernelWindows.WIND, track, 360, 390, 500, 530);
                double q = gas == Gas.SO2 ? KernelWindows.Q_SO2 : 1000 * KernelWindows.Q_SO2;
                observations.add(new Window("DRONE-" + gas + "-0", "DRONE", gas.channel(), 500, 530,
                        30, q * response, NoiseModel.enhancementStd(windows.world, gas), bg));
            }
            Analysis result = ranker.analyze(new AnalysisInput("run-1", "inv-1", 600, observations, KernelWindows.WIND));
            assertThat(surviving(result)).containsExactly("S02");
            assertThat(result.inputWindowIds()).contains("DRONE-SO2-0", "DRONE-CO2-0");
            assertThat(requests).containsExactly("run-1/DRONE/0.0/530.0", "run-1/DRONE/0.0/530.0",
                    "run-1/DRONE/0.0/530.0", "run-1/DRONE/0.0/530.0");
        }
    }

    @Test
    void missingHistoryBeanAndUnknownTracksExcludeTheDroneWithoutGuessing() {
        for (boolean register : new boolean[]{false, true}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles("real");
                if (register) context.registerBean(InletHistory.class, () -> (r, v, s, e) -> Optional.empty());
                context.register(ScienceRanker.class);
                context.refresh();
                Analysis result = context.getBean(ScienceRanker.class)
                        .analyze(new KernelWindows().s02AtN2().s02AtAerial().input());
                assertThat(surviving(result)).containsExactlyInAnyOrder("S01", "S02");
                assertThat(result.exclusions()).filteredOn(e -> e.readingWindow().startsWith("DRONE"))
                        .hasSize(2).allSatisfy(e -> assertThat(e.reason()).isEqualTo("INLET_HISTORY_UNKNOWN"));
            }
        }
    }
}
