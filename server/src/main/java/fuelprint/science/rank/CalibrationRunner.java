package fuelprint.science.rank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fuelprint.api.Enu;
import fuelprint.api.WindNow;
import fuelprint.api.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Small headless calibration runner for the FP-COMPAT-3 cutoff. It makes separate calibration episodes with its own
 * seeds (namespace "FPCAL", never the SC1-SC3 seeds), using a generator that differs structurally from the steady
 * kernel (1 Hz wind meander in direction and speed, AR(1) instrument noise, a drifting common background removed by
 * a background window), keeps only eligible episodes (some window above its floor), and records for each the
 * true-support minimum normalised SSE over the 27 nuisance members. The cutoff is the statistic of rank
 * min(m, ceil(0.95 (m + 1))) among the m sorted values.
 * <p>
 * Run from {@code server/}: {@code ./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt}
 * then {@code java -cp target/classes:$(cat target/cp.txt) fuelprint.science.rank.CalibrationRunner}. The test
 * {@code CutoffCalibrationTest} recomputes the value and checks the stored file matches.
 */
public final class CalibrationRunner {

    public static final long SEED_BASE = 0x4650_4341_4C00_0000L; // "FPCAL"
    public static final int EPISODES = 60;
    public static final int MAX_ATTEMPTS = 2000;
    public static final String DEFAULT_OUTPUT = "src/main/resources/" + Cutoff.RESOURCE;

    private static final double[][] AERIAL_POINTS = {{0, 100, 35}, {120, 100, 35}};
    /** Slow wind drift around the one reported wind (time scale about 200 s): the analysis sees one WindNow. */
    private static final double MEANDER_RHO = 0.995;
    private static final double MEANDER_DIR_SD_DEG = 0.5;
    private static final double MEANDER_SPEED_SD = 0.01;
    private static final double SO2_MIN_G_S = 0.25;
    private static final double SO2_MAX_G_S = 50.0;
    private static final double T0 = 300.0;

    /** One calibration episode: its seed, the explanation that really generated it and its statistic. */
    public record Episode(long seed, String support, double statistic) {
    }

    public record Computation(double cutoff, int rank, int attempts, List<Episode> episodes, String sha256) {
    }

    private CalibrationRunner() {
    }

    public static Computation compute(FitEngine engine) {
        WorldProfile world = engine.world();
        List<Explanation> supports = Explanation.enumerate(world.sources().stream()
                .map(WorldProfile.Source::id).toList()).stream().filter(e -> !e.sources().isEmpty()).toList();
        List<Episode> episodes = new ArrayList<>();
        int attempts = 0;
        while (episodes.size() < EPISODES && attempts < MAX_ATTEMPTS) {
            long seed = SEED_BASE + attempts;
            attempts++;
            Generated g = generate(engine, supports, seed);
            boolean eligible = g.entries.stream()
                    .anyMatch(e -> e.value() >= Math.max(e.gas().floor(), 3 * e.sigma()));
            if (!eligible) {
                continue;
            }
            FitEngine.MemberFit best = engine.fit(g.truth, g.wind, g.entries, new HashMap<>()).best();
            if (best == null) {
                continue;
            }
            episodes.add(new Episode(seed, g.truth.id(), best.normalizedSse()));
        }
        if (episodes.size() < 40) {
            throw new IllegalStateException("Only " + episodes.size() + " eligible calibration episodes; need 40");
        }
        double[] stats = episodes.stream().mapToDouble(Episode::statistic).sorted().toArray();
        int rank = Cutoff.rankFor(stats.length);
        return new Computation(stats[rank - 1], rank, attempts, episodes, sha256(stats));
    }

    private record Generated(Explanation truth, WindNow wind, List<WindowSelection.Entry> entries) {
    }

    private static Generated generate(FitEngine engine, List<Explanation> supports, long seed) {
        WorldProfile world = engine.world();
        SplittableRandom rnd = new SplittableRandom(seed);
        Explanation truth = supports.get(rnd.nextInt(supports.size()));
        WindNow wind = new WindNow(240 + 60 * rnd.nextDouble(), 2 + 3 * rnd.nextDouble());
        Map<String, double[]> emissions = new LinkedHashMap<>();
        for (String s : truth.sources()) {
            double so2 = Math.exp(Math.log(SO2_MIN_G_S) + (Math.log(SO2_MAX_G_S) - Math.log(SO2_MIN_G_S))
                    * rnd.nextDouble());
            double co2 = so2 * 1000 * Math.exp(Math.log(0.5) + (Math.log(2) - Math.log(0.5)) * rnd.nextDouble());
            emissions.put(s, new double[]{so2, co2});
        }
        Map<String, Enu> inlets = new LinkedHashMap<>(world.nodeInlets());
        boolean aerial = rnd.nextBoolean();
        if (aerial) {
            double[] p = AERIAL_POINTS[rnd.nextInt(AERIAL_POINTS.length)];
            inlets.put("DRONE", new Enu(p[0], p[1], p[2]));
        }
        int seconds = (int) (T0 + 180 + 60);
        double start = T0 - 60;
        double[] dir = new double[seconds];
        double[] speed = new double[seconds];
        double dSd = MEANDER_DIR_SD_DEG * Math.sqrt(1 - MEANDER_RHO * MEANDER_RHO);
        double sSd = MEANDER_SPEED_SD * Math.sqrt(1 - MEANDER_RHO * MEANDER_RHO);
        double d = MEANDER_DIR_SD_DEG * rnd.nextGaussian();
        double v = MEANDER_SPEED_SD * rnd.nextGaussian();
        for (int i = 0; i < seconds; i++) {
            d = MEANDER_RHO * d + dSd * rnd.nextGaussian();
            v = MEANDER_RHO * v + sSd * rnd.nextGaussian();
            dir[i] = wind.fromDeg() + d;
            speed[i] = wind.speedMS() * Math.max(0.2, 1 + v);
        }
        double rho = world.noise("AR1_rho");
        double cbRho = world.commonBackground("rho");
        double cbSd = world.commonBackground("innovation_sd");
        double[] common = new double[seconds];
        double cb = cbSd / Math.sqrt(1 - cbRho * cbRho) * rnd.nextGaussian();
        for (int i = 0; i < seconds; i++) {
            cb = cbRho * cb + cbSd * rnd.nextGaussian();
            common[i] = cb;
        }
        Map<String, WorldProfile.Source> byId = new HashMap<>();
        world.sources().forEach(s -> byId.put(s.id(), s));
        List<WindowSelection.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Enu> inlet : inlets.entrySet()) {
            for (Gas gas : Gas.values()) {
                double innov = world.noise(gas.innovationKey());
                double loading = world.commonBackground(gas.loadingKey());
                double x = innov / Math.sqrt(1 - rho * rho) * rnd.nextGaussian();
                double[] series = new double[seconds];
                for (int i = 0; i < seconds; i++) {
                    x = rho * x + innov * rnd.nextGaussian();
                    double plume = 0;
                    for (Map.Entry<String, double[]> e : emissions.entrySet()) {
                        WorldProfile.Source s = byId.get(e.getKey());
                        double q = e.getValue()[gas.ordinal()];
                        plume += q * engine.kernel().unit(gas, s.east(), s.north(), s.heightM(), inlet.getValue(),
                                dir[i], speed[i]);
                    }
                    series[i] = plume + x + loading * common[i];
                }
                double background = mean(series, 0, 30) - plumeFree(series, 0, 30, emissions, byId, gas, engine,
                        inlet.getValue(), dir, speed);
                double sigma = NoiseModel.enhancementStd(world, gas);
                InletTrack track = InletTrack.fixed(inlet.getValue());
                boolean drone = inlet.getKey().equals("DRONE");
                int windows = drone ? 1 : world.limit("latest_complete_windows_per_instrument_channel");
                for (int k = 0; k < windows; k++) {
                    double ws = drone ? T0 + 150 : T0 + 30 * k;
                    int from = (int) (ws - start);
                    String id = String.format(Locale.ROOT, "cal-%x-%s-%s-%d", seed, inlet.getKey(), gas, k);
                    Window w = new Window(id, inlet.getKey(), gas.channel(), ws, ws + 30, 30,
                            mean(series, from, from + 30) - background, sigma, id + "-bg");
                    entries.add(new WindowSelection.Entry(w, gas, track));
                }
            }
        }
        return new Generated(truth, wind, entries);
    }

    /** The background window is measured at a plume-free background point: remove its plume part. */
    private static double plumeFree(double[] series, int from, int to, Map<String, double[]> emissions,
                                    Map<String, WorldProfile.Source> byId, Gas gas, FitEngine engine, Enu inlet,
                                    double[] dir, double[] speed) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            for (Map.Entry<String, double[]> e : emissions.entrySet()) {
                WorldProfile.Source s = byId.get(e.getKey());
                sum += e.getValue()[gas.ordinal()] * engine.kernel().unit(gas, s.east(), s.north(), s.heightM(),
                        inlet, dir[i], speed[i]);
            }
        }
        return sum / (to - from);
    }

    private static double mean(double[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += a[i];
        }
        return s / (to - from);
    }

    private static String sha256(double[] stats) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Arrays.toString(stats).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JSON written to {@code science/rank/cutoff.json}: the value and how it was made. */
    public static Map<String, Object> describe(Computation c, FitEngine engine) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cutoff", c.cutoff());
        out.put("episodes", c.episodes().size());
        out.put("rank", c.rank());
        out.put("attempts", c.attempts());
        out.put("statistic", "true-support minimum normalised SSE (whitened SSE / entries) over the 27 nuisance "
                + "members, per eligible calibration episode");
        out.put("rank_rule", "min(m, ceil(0.95 (m + 1)))");
        out.put("method", "fuelprint.science.rank.CalibrationRunner; " + CompatRanker.ALGORITHM + "; world "
                + engine.world().version() + "; response " + engine.response().label());
        out.put("seeds", String.format(Locale.ROOT, "java.util.SplittableRandom seeds 0x%X + i for i = 0..%d "
                + "(own calibration namespace FPCAL; never the SC1-SC3 seeds)", SEED_BASE, c.attempts() - 1));
        out.put("domain", "support uniform over the 6 singles and 15 pairs; wind from U(240, 300) deg at "
                + "U(2, 5) m/s; SO2 log-uniform 0.25 to 50 g/s per source (0.5 to 100 x nominal), CO2 = SO2 x 1000 x log-uniform(0.5, 2); "
                + "nodes N1-N4 with 6 windows each per gas, and in half the episodes one aerial window at "
                + "(0,100,35) or (120,100,35); eligible only if some window is above its floor");
        out.put("generator", String.format(Locale.ROOT, "1 Hz quasi-steady plume under slow AR(1) wind drift around "
                + "the reported wind (rho %.3f, direction sd %.1f deg, speed sd %.0f%%), AR(1) instrument noise and "
                + "drifting common background from world-profile.json, minus a plume-free background window",
                MEANDER_RHO, MEANDER_DIR_SD_DEG, 100 * MEANDER_SPEED_SD));
        double[] s = c.episodes().stream().mapToDouble(Episode::statistic).sorted().toArray();
        out.put("statistic_min", s[0]);
        out.put("statistic_median", s[s.length / 2]);
        out.put("statistic_max", s[s.length - 1]);
        out.put("statistics_sha256", c.sha256());
        return out;
    }

    public static void main(String[] args) throws IOException {
        FitEngine engine = new FitEngine(WorldProfile.load(), ResponseModel.TEACHING);
        Computation c = compute(engine);
        Path out = Path.of(args.length > 0 ? args[0] : DEFAULT_OUTPUT);
        Files.createDirectories(out.toAbsolutePath().getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), describe(c, engine));
        System.out.println("FP-COMPAT-3 cutoff " + c.cutoff() + " from " + c.episodes().size()
                + " episodes, rank " + c.rank() + ", written to " + out);
    }
}
