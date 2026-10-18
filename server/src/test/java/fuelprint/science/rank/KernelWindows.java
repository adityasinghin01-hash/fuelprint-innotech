package fuelprint.science.rank;

import fuelprint.api.AnalysisInput;
import fuelprint.api.Enu;
import fuelprint.api.WindNow;
import fuelprint.api.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds noise-free synthetic windows with the ranker's own public kernel ("S02 emits, wind from 270 at 3 m/s").
 * No truth package is involved: the emitting source is just a test parameter here.
 */
public final class KernelWindows {

    public static final WindNow WIND = new WindNow(270, 3);
    public static final Enu AERIAL = new Enu(0, 100, 35);
    /** SO2 g/s; CO2 is 1000 times that (nominal ratio). Inside the calibration domain (0.25 to 50 g/s). */
    public static final double Q_SO2 = 50;

    public final WorldProfile world = WorldProfile.load();
    public final FitEngine engine = new FitEngine(world, ResponseModel.TEACHING);
    private final List<Window> windows = new ArrayList<>();
    private final Map<String, Enu> positions = new HashMap<>();

    /** Adds {@code count} consecutive 30 s windows per gas at a place, as emitted by a source at a position. */
    public KernelWindows add(String place, Enu inlet, double sourceEast, double sourceNorth, double heightM,
                             int count, double startS) {
        for (Gas gas : Gas.values()) {
            double q = gas == Gas.SO2 ? Q_SO2 : 1000 * Q_SO2;
            double value = q * engine.kernel().unit(gas, sourceEast, sourceNorth, heightM, inlet, WIND.fromDeg(),
                    WIND.speedMS());
            for (int k = 0; k < count; k++) {
                double s = startS + 30 * k;
                String id = place + "-" + gas + "-" + k;
                windows.add(new Window(id, place, gas.channel(), s, s + 30, 30, value,
                        NoiseModel.enhancementStd(world, gas), "bg-" + place));
                positions.put(id, inlet);
            }
        }
        return this;
    }

    /** S02 emitting, seen at N2 (6 windows per gas). */
    public KernelWindows s02AtN2() {
        WorldProfile.Source s02 = source("S02");
        return add("N2", world.nodeInlets().get("N2"), s02.east(), s02.north(), s02.heightM(), 6, 300);
    }

    /** S02 emitting, seen by the drone at (0, 100, 35) (one window per gas). */
    public KernelWindows s02AtAerial() {
        WorldProfile.Source s02 = source("S02");
        return add("DRONE", AERIAL, s02.east(), s02.north(), s02.heightM(), 1, 500);
    }

    public WorldProfile.Source source(String id) {
        return world.sources().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    public List<Window> windows() {
        return List.copyOf(windows);
    }

    public InletTrack.Source inlets() {
        return InletTrack.firstOf(List.of(InletTrack.byWindowId(positions), InletTrack.fixedNodes(world)));
    }

    public AnalysisInput input() {
        return new AnalysisInput("run-1", "inv-1", 600, windows(), WIND);
    }

    public CompatRanker ranker() {
        return new CompatRanker(engine, Cutoff.load());
    }

    public CompatRanker.Result analyze() {
        return ranker().analyze(input(), inlets());
    }
}
