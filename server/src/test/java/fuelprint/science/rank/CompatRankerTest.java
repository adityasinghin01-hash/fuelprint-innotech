package fuelprint.science.rank;

import fuelprint.api.Analysis;
import fuelprint.api.AnalysisInput;
import fuelprint.api.Candidate;
import fuelprint.api.CandidateStatus;
import fuelprint.api.Enu;
import fuelprint.api.Exclusion;
import fuelprint.api.Outcome;
import fuelprint.api.Qualification;
import fuelprint.api.Window;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CompatRankerTest {

    private static Map<String, Candidate> byId(Analysis a) {
        return a.candidates().stream().collect(Collectors.toMap(Candidate::explanationId, c -> c));
    }

    private static List<String> surviving(Analysis a) {
        return a.candidates().stream()
                .filter(c -> c.status() == CandidateStatus.SURVIVES && !c.isUnknown())
                .map(Candidate::explanationId).toList();
    }

    @Test
    void fromN2AloneBothS01AndS02Survive() {
        Analysis a = new KernelWindows().s02AtN2().analyze().analysis();
        assertThat(surviving(a)).containsExactlyInAnyOrder("S01", "S02");
        assertThat(a.outcome()).isEqualTo(Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE);
        assertThat(byId(a).get("S03").status()).isEqualTo(CandidateStatus.REJECTED);
        assertThat(byId(a).get("S01+S02").reason()).startsWith("NOT_NEEDED");
        assertThat(a.qualification()).isEqualTo(Qualification.SYNTHETIC_SCOPE_ONLY);
    }

    @Test
    void addingTheAerialWindowLeavesOnlyS02() {
        Analysis a = new KernelWindows().s02AtN2().s02AtAerial().analyze().analysis();
        assertThat(surviving(a)).containsExactly("S02");
        assertThat(a.outcome()).isEqualTo(Outcome.PROVISIONAL_SINGLE);
        Candidate s01 = byId(a).get("S01");
        assertThat(s01.status()).isEqualTo(CandidateStatus.REJECTED);
        assertThat(s01.reason()).startsWith("ABOVE_CUTOFF");
        assertThat(s01.fit()).isGreaterThan(Cutoff.load().value());
        assertThat(byId(a).get("S02").fit()).isLessThan(1e-6);
    }

    @Test
    void unknownRowIsAlwaysPresentExactlyOnce() {
        List<Analysis> all = new ArrayList<>();
        all.add(new KernelWindows().s02AtN2().analyze().analysis());
        all.add(new KernelWindows().s02AtN2().s02AtAerial().analyze().analysis());
        all.add(invalid());
        all.add(noSignal());
        all.add(mismatch());
        for (Analysis a : all) {
            List<Candidate> unknown = a.candidates().stream().filter(Candidate::isUnknown).toList();
            assertThat(unknown).hasSize(1);
            assertThat(unknown.get(0).status()).isEqualTo(CandidateStatus.SURVIVES);
            assertThat(unknown.get(0).fit()).isNull();
        }
    }

    @Test
    void invalidWithTooLittleData() {
        Analysis a = invalid();
        assertThat(a.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(a.inputWindowIds()).isEmpty();
        assertThat(a.exclusions()).extracting(Exclusion::reason)
                .containsExactlyInAnyOrder("INCOMPLETE_WINDOW", "UNCERTAINTY_NOT_USABLE", "NO_VALUE");
        Analysis empty = new KernelWindows().ranker()
                .analyze(new AnalysisInput("r", "i", 0, List.of(), KernelWindows.WIND), (r, w) -> java.util.Optional.empty())
                .analysis();
        assertThat(empty.outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void noSignalBelowTheFloor() {
        Analysis a = noSignal();
        assertThat(a.outcome()).isEqualTo(Outcome.NO_SIGNAL);
        assertThat(a.explanationText()).contains("does not mean the factories are clean");
        assertThat(a.candidates()).hasSize(1);
    }

    @Test
    void readingsNoRegisteredSourceCanMakeGiveUnknownOrMismatch() {
        assertThat(mismatch().outcome()).isEqualTo(Outcome.UNKNOWN_OR_MISMATCH);
    }

    @Test
    void swappingHiddenLabelsWithIdenticalPublicInputGivesAnIdenticalAnalysis() {
        // Hidden world A: registered S02 emits. Hidden world B: an unregistered source stands at the same spot and
        // height and emits the same. The public windows are identical, so the analysis must be identical.
        KernelWindows a = new KernelWindows();
        WorldProfile.Source s02 = a.source("S02");
        a.add("N2", a.world.nodeInlets().get("N2"), s02.east(), s02.north(), s02.heightM(), 6, 300)
                .add("DRONE", KernelWindows.AERIAL, s02.east(), s02.north(), s02.heightM(), 1, 500);
        KernelWindows b = new KernelWindows();
        double hiddenEast = -100;
        double hiddenNorth = 100;
        double hiddenHeight = 35;
        b.add("N2", b.world.nodeInlets().get("N2"), hiddenEast, hiddenNorth, hiddenHeight, 6, 300)
                .add("DRONE", KernelWindows.AERIAL, hiddenEast, hiddenNorth, hiddenHeight, 1, 500);
        assertThat(a.windows()).isEqualTo(b.windows());
        assertThat(a.analyze().analysis()).isEqualTo(b.analyze().analysis());
    }

    @Test
    void explanationTextIsDeterministicAndIndependentOfWindowOrder() {
        KernelWindows k = new KernelWindows().s02AtN2().s02AtAerial();
        Analysis first = k.analyze().analysis();
        List<Window> shuffled = new ArrayList<>(k.windows());
        Collections.reverse(shuffled);
        Analysis second = k.ranker().analyze(new AnalysisInput("run-1", "inv-1", 600, shuffled,
                KernelWindows.WIND), k.inlets()).analysis();
        assertThat(second).isEqualTo(first);
        assertThat(first.explanationText()).isEqualTo(
                "The smoke-spread check used 14 windows from DRONE, N2, with the wind from 270 degrees at 3.0 m/s. "
                        + "S02 alone explains the readings within the cutoff; no other registered factory or pair "
                        + "does. 21 explanations were set aside (cutoff 7.096). An unregistered source is never "
                        + "ruled out. This holds only inside the synthetic teaching model.");
    }

    @Test
    void selectsTheLatestSixNonOverlappingWindowsAndReportsTheRest() {
        KernelWindows k = new KernelWindows();
        WorldProfile.Source s02 = k.source("S02");
        k.add("N2", k.world.nodeInlets().get("N2"), s02.east(), s02.north(), s02.heightM(), 8, 0);
        List<Window> windows = new ArrayList<>(k.windows());
        Window w = windows.get(7);
        windows.add(new Window("N2-overlap", "N2", w.channel(), w.startS() - 15, w.endS() - 15, 30,
                w.meanEnhancement(), w.stdUncertainty(), null));
        Analysis a = k.ranker().analyze(new AnalysisInput("r", "i", 0, windows, KernelWindows.WIND), k.inlets())
                .analysis();
        assertThat(a.inputWindowIds()).hasSize(12);
        assertThat(a.inputWindowIds()).doesNotContain("N2-SO2-0", "N2-SO2-1", "N2-CO2-0", "N2-CO2-1");
        assertThat(a.exclusions()).extracting(Exclusion::reason)
                .contains("OLDER_THAN_LATEST_6", "OVERLAPS_LATER_WINDOW");
    }

    @Test
    void vehicleWindowWithoutAnInletHistoryIsExcludedNotGuessed() {
        KernelWindows k = new KernelWindows().s02AtN2().s02AtAerial();
        Analysis a = k.ranker().analyze(k.input(), InletTrack.fixedNodes(k.world)).analysis();
        assertThat(a.exclusions()).extracting(Exclusion::reason).contains("INLET_HISTORY_UNKNOWN");
        assertThat(a.inputWindowIds()).noneMatch(id -> id.startsWith("DRONE"));
    }

    @Test
    void fittedEmissionsAreNeverNegative() {
        FitSnapshot snap = new KernelWindows().s02AtN2().analyze().snapshot();
        for (FitSnapshot.Surviving s : snap.surviving()) {
            for (FitEngine.MemberFit m : s.admitted()) {
                for (FitEngine.GasFit g : m.gases().values()) {
                    for (double q : g.amplitudes()) {
                        assertThat(q).isGreaterThanOrEqualTo(0.0);
                    }
                }
            }
        }
    }

    @Test
    void movingInletUsesTheRecordedHistoryThroughTheResponse() {
        KernelWindows k = new KernelWindows();
        WorldProfile.Source s02 = k.source("S02");
        Enu a = new Enu(0, 100, 35);
        Enu b = new Enu(0, 100, 3);
        InletTrack moving = new InletTrack(List.of(new InletTrack.Sample(0, b), new InletTrack.Sample(100, b),
                new InletTrack.Sample(110, a)));
        double viaTrack = ResponseModel.TEACHING.windowMean(moving, 300, 330,
                p -> k.engine.kernel().unit(Gas.SO2, s02.east(), s02.north(), s02.heightM(), p, 270, 3));
        double hovering = k.engine.kernel().unit(Gas.SO2, s02.east(), s02.north(), s02.heightM(), a, 270, 3);
        double before = k.engine.kernel().unit(Gas.SO2, s02.east(), s02.north(), s02.heightM(), b, 270, 3);
        assertThat(viaTrack).isLessThan(hovering).isGreaterThan(before);
        assertThat(viaTrack).isGreaterThan(0.95 * hovering);
    }

    private static Analysis invalid() {
        KernelWindows k = new KernelWindows();
        List<Window> w = List.of(
                new Window("w1", "N2", "SO2_ppb", 0, 30, 12, 80.0, 6.0, null),
                new Window("w2", "N2", "SO2_ppb", 30, 60, 30, 80.0, null, null),
                new Window("w3", "N2", "CO2_ppm", 0, 30, 30, null, 2.0, null));
        return k.ranker().analyze(new AnalysisInput("r", "i", 0, w, KernelWindows.WIND), k.inlets()).analysis();
    }

    private static Analysis noSignal() {
        KernelWindows k = new KernelWindows();
        List<Window> w = List.of(
                new Window("w1", "N2", "SO2_ppb", 0, 30, 30, 12.0, 6.0, null),
                new Window("w2", "N2", "CO2_ppm", 0, 30, 30, 3.0, 2.4, null));
        return k.ranker().analyze(new AnalysisInput("r", "i", 0, w, KernelWindows.WIND), k.inlets()).analysis();
    }

    private static Analysis mismatch() {
        // Wind from the east: no registered factory is upwind of N2 close enough to send it 400 ppb.
        KernelWindows k = new KernelWindows();
        List<Window> w = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            w.add(new Window("n2-" + i, "N2", "SO2_ppb", 30 * i, 30 * i + 30, 30, 400.0, 6.0, null));
        }
        return k.ranker().analyze(new AnalysisInput("r", "i", 0, w, new fuelprint.api.WindNow(90, 3)), k.inlets())
                .analysis();
    }
}
