package fuelprint.science.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.*;
import fuelprint.science.detect.TeachDetector;
import fuelprint.science.rank.*;
import fuelprint.science.sensor.SensorField;
import fuelprint.science.sensor.TeachCaptureRules;
// Scenario schedules stay behind the science.world boundary.
import fuelprint.science.world.PuffWorld;
import fuelprint.science.world.ScienceWorld;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.*;

/** Offline independent full-chain calibration. Never writes the runtime cutoff resource. */
public final class FullChainCalibrationRunner {
    public static final double FIXED_SNAPSHOT_S = 300;
    public static final int DEFAULT_ELIGIBLE = 60;
    private static final Enu DEPOT = new Enu(0, 0, 2);
    private static final List<Enu> TARGETS = List.of(new Enu(0, 100, 35), new Enu(120, 100, 35));

    private FullChainCalibrationRunner() { }

    /** Namespaces are fixed independently of scenarios and of one another. */
    public static long seed(String namespace, int attempt) {
        if (attempt < 0 || attempt >= 1_000_000) throw new IllegalArgumentException("Attempt outside namespace range");
        long base = switch (namespace) {
            case "FPCAL" -> 0x465043414C000000L;
            case "FPEVAL" -> 0x4650455641000000L;
            case "FPEVAL4" -> 0x4650455634000000L;
            case "FPDEV" -> 0x4650444556000000L;
            default -> windSeedBase(namespace);
        };
        return base + attempt;
    }

    private static long windSeedBase(String namespace) {
        if (!namespace.startsWith("FPWIND/")) throw new IllegalArgumentException("Use FPCAL, FPEVAL4, historical FPEVAL, FPDEV or FPWIND/0..11");
        int cell = Integer.parseInt(namespace.substring("FPWIND/".length()));
        if (cell < 0 || cell >= 12) throw new IllegalArgumentException("Wind cell must be0..11");
        return 0x465057494E000000L + cell * 1_000_000L;
    }

    private static int windCell(Domain domain) {
        int direction = List.of(.5, 1.0, 3.0, 5.0).indexOf(domain.directionSdDeg());
        int timeScale = List.of(30.0, 200.0, 600.0).indexOf(domain.windTimeScaleS());
        if (direction < 0 || timeScale < 0) throw new IllegalArgumentException("Wind sensitivity requires a predeclared SD/time-scale cell");
        return direction * 3 + timeScale;
    }

    /** Null is a failed eligible fit and ranks as infinity; failures are never removed from m. */
    public static double quantile(List<Double> statistics) {
        if (statistics.size() < 40) throw new IllegalArgumentException("Need at least 40 eligible attempts");
        double[] sorted = statistics.stream().mapToDouble(value -> value == null || !Double.isFinite(value)
                ? Double.POSITIVE_INFINITY : value).sorted().toArray();
        return sorted[Cutoff.rankFor(sorted.length) - 1];
    }

    public record Domain(double directionSdDeg, double windTimeScaleS, double relativeSpeedSd) {
        public Domain {
            if (!Double.isFinite(directionSdDeg) || directionSdDeg < 0 || !Double.isFinite(windTimeScaleS)
                    || windTimeScaleS <= 0 || !Double.isFinite(relativeSpeedSd) || relativeSpeedSd < 0)
                throw new IllegalArgumentException("Invalid wind drift domain");
        }
        public static Domain baseline() { return new Domain(.5, 200, .01); }
    }

    /** Full public measurement episode plus generator labels. Labels are used only for coverage evaluation. */
    public record Generated(long seed, String runId, Explanation support, Map<String, List<Double>> emissions,
                            WindNow reportedWind, Enu target, List<Stage> stages, List<Window> fixedWindows,
                            List<Window> captureWindows, List<Reading> vehicleReadings,
                            List<InletTrack.Sample> vehicleTrack, Map<String, Enu> fixedInlets,
                            List<PuffWorld.WindSegment> windHistory, List<ChannelVerdict> verdicts,
                            long simulationNanos) {
        public List<Window> windows() {
            List<Window> all = new ArrayList<>(fixedWindows); all.addAll(captureWindows); return List.copyOf(all);
        }
        public InletTrack.Source inlets() {
            return (run, window) -> {
                if (!runId.equals(run)) return Optional.empty();
                Enu fixed = fixedInlets.get(window.sensorOrVehicleId());
                if (fixed != null) return Optional.of(InletTrack.fixed(fixed));
                if (window.sensorOrVehicleId().equals(id(seed, "DRONE-1"))) return Optional.of(new InletTrack(vehicleTrack, true));
                return Optional.empty();
            };
        }
    }

    public static Generated generate(long seed, Domain domain) {
        long began = System.nanoTime();
        var worldProfile = fuelprint.science.world.WorldProfile.load();
        List<Explanation> supports = Explanation.enumerate(worldProfile.sources().stream().map(s -> s.id()).toList())
                .stream().filter(s -> !s.sources().isEmpty()).toList();
        SplittableRandom random = new SplittableRandom(seed);
        Explanation support = supports.get(random.nextInt(supports.size()));
        WindNow reported = new WindNow(240 + 60 * random.nextDouble(), 2 + 3 * random.nextDouble());
        Map<String, List<Double>> emissions = new LinkedHashMap<>();
        PuffWorld puffs = new PuffWorld(worldProfile);
        for (String source : support.sources()) {
            double so2 = logUniform(random, .25, 50);
            double co2 = so2 * 1000 * logUniform(random, .5, 2);
            emissions.put(source, List.of(so2, co2));
            puffs.setEmission(source, so2, co2);
        }
        ScienceWorld world = new ScienceWorld(event -> { });
        String run = id(seed, "run"), drone = id(seed, "DRONE-1");
        // Empty schedule: generated constant emissions above are independent of TruthLibrary/scenario presets.
        world.load(run, puffs);
        SensorField sensors = new SensorField(world);
        sensors.reset(seed, ZonedDateTime.of(2026, 12, 5, 2, 0, 0, 0, java.time.ZoneId.of("Asia/Kolkata")), false);
        TeachDetector detector = new TeachDetector();
        TeachCaptureRules capture = new TeachCaptureRules();
        Enu target = TARGETS.get(random.nextInt(TARGETS.size()));
        double distance = Math.sqrt(Math.pow(target.east(), 2) + Math.pow(target.north(), 2) + Math.pow(target.up() - 2, 2));
        double arrival = Math.ceil(FIXED_SNAPSHOT_S + distance / 3);
        double bgStart = arrival + 30, purgeStart = bgStart + 30, settleStart = purgeStart + 30;
        double collectStart = settleStart + 120, collectEnd = collectStart + 30;
        List<Stage> stages = List.of(new Stage("SETUP", arrival, bgStart), new Stage("BACKGROUND", bgStart, purgeStart),
                new Stage("PURGE", purgeStart, settleStart), new Stage("SETTLE", settleStart, collectStart),
                new Stage("COLLECT", collectStart, collectEnd));
        Map<String, Enu> fixed = new LinkedHashMap<>();
        List<InletPose> fixedPoses = new ArrayList<>();
        worldProfile.nodes().forEach(node -> {
            Enu position = new Enu(node.east(), node.north(), node.inletHeight());
            String instrument = id(seed, node.id());
            fixed.put(instrument, position); fixedPoses.add(new InletPose(instrument, node.id(), position));
        });
        List<Window> windows = new ArrayList<>();
        List<Reading> vehicle = new ArrayList<>();
        List<InletTrack.Sample> track = new ArrayList<>();
        track.add(new InletTrack.Sample(0, DEPOT));
        double rho = Math.exp(-1 / domain.windTimeScaleS());
        double direction = domain.directionSdDeg() * random.nextGaussian();
        double speed = domain.relativeSpeedSd() * random.nextGaussian();
        int lastWindSecond = -1;
        long ticks = Math.round(collectEnd / PuffWorld.DT);
        for (long tick = 1; tick <= ticks; tick++) {
            double time = tick * PuffWorld.DT;
            int windSecond = (int) Math.floor((tick - 1) * PuffWorld.DT + 1e-9);
            if (windSecond > lastWindSecond) {
                direction = rho * direction + domain.directionSdDeg() * Math.sqrt(1 - rho*rho) * random.nextGaussian();
                speed = rho * speed + domain.relativeSpeedSd() * Math.sqrt(1 - rho*rho) * random.nextGaussian();
                puffs.setWind(reported.fromDeg() + direction, reported.speedMS() * Math.max(.2, 1 + speed));
                lastWindSecond = windSecond;
            }
            world.step(PuffWorld.DT);
            if (puffs.capacityReached().isPresent()) throw new IllegalStateException("World capacity reached: " + puffs.capacityReached().get());
            double fraction = Math.max(0, Math.min(1, (time - FIXED_SNAPSHOT_S) / (arrival - FIXED_SNAPSHOT_S)));
            Enu inlet = new Enu(target.east() * fraction, target.north() * fraction, 2 + (target.up() - 2) * fraction);
            track.add(new InletTrack.Sample(time, inlet));
            List<InletPose> poses = new ArrayList<>(fixedPoses);
            poses.add(new InletPose(drone, "DRONE-1", inlet));
            ReadingBatch batch = sensors.observe(time, poses);
            if (tick % 20 == 0) {
                vehicle.addAll(batch.readings().stream().filter(r -> drone.equals(r.sensorOrVehicleId())).toList());
                if (time <= FIXED_SNAPSHOT_S) {
                    List<Reading> readings = batch.readings().stream().filter(r -> !drone.equals(r.sensorOrVehicleId())).toList();
                    windows.addAll(detector.evaluateDetailed(new DetectorInput(run, time, readings, List.of(), reported)).windows());
                }
            }
        }
        List<Window> measured = new ArrayList<>();
        List<ChannelVerdict> verdicts = new ArrayList<>();
        for (String channel : List.of(SensorField.SO2, SensorField.CO2)) {
            String flow = channel.equals(SensorField.SO2) ? SensorField.SO2_FLOW : SensorField.CO2_FLOW;
            CaptureInput input = new CaptureInput(id(seed, "attempt"), TeachCaptureRules.METHOD, channel, stages,
                    vehicle.stream().filter(r -> channel.equals(r.channel()) || flow.equals(r.channel())).toList());
            ChannelVerdict verdict = capture.judge(input);
            verdicts.add(verdict);
            if (verdict.status() == ChannelStatus.VALID) measured.addAll(capture.windows(input));
        }
        return new Generated(seed, run, support, Map.copyOf(emissions), reported, target, stages,
                canonicalWindows(seed, "fixed", windows), canonicalWindows(seed, "capture", measured), List.copyOf(vehicle),
                List.copyOf(track), Map.copyOf(fixed), List.copyOf(puffs.windHistory()), List.copyOf(verdicts), System.nanoTime() - began);
    }

    private static List<Window> canonicalWindows(long seed, String label, List<Window> windows) {
        Map<String, String> ids = new HashMap<>();
        for (int i = 0; i < windows.size(); i++) ids.put(windows.get(i).id(), id(seed, label + "-" + i));
        return windows.stream().map(w -> new Window(ids.get(w.id()), w.sensorOrVehicleId(), w.channel(), w.startS(), w.endS(),
                w.validSeconds(), w.meanEnhancement(), w.stdUncertainty(), w.backgroundWindowId() == null ? null : ids.get(w.backgroundWindowId()))).toList();
    }

    private static String id(long seed, String label) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((seed + ":" + label).getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 15) | 64); bytes[8] = (byte) ((bytes[8] & 63) | 128);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong()).toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static double logUniform(SplittableRandom random, double min, double max) {
        return Math.exp(Math.log(min) + random.nextDouble() * Math.log(max / min));
    }

    /** Every attempt is retained. Eligible failures count as uncovered/infinite; no success-only filtering. */
    public static Map<String, Object> measure(Generated episode, FitEngine engine, Double cutoff, boolean wholeRanker) {
        long began = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seed", episode.seed()); result.put("support", episode.support().id()); result.put("emissions", episode.emissions());
        result.put("reported_wind", episode.reportedWind()); result.put("target", episode.target()); result.put("stages", episode.stages());
        result.put("verdicts", episode.verdicts()); result.put("windows", episode.windows());
        Map<String, Object> measuredMeans = new LinkedHashMap<>();
        for (String gas : List.of(SensorField.SO2, SensorField.CO2)) {
            Map<String, Double> means = new LinkedHashMap<>();
            for (Stage stage : episode.stages()) if (stage.stage().equals("BACKGROUND") || stage.stage().equals("COLLECT")) {
                double mean = episode.vehicleReadings().stream().filter(r -> gas.equals(r.channel()) && r.simTimeS() >= stage.startS()
                        && r.simTimeS() < stage.endS() && r.value() != null).mapToDouble(Reading::value).average().orElse(Double.NaN);
                means.put(stage.stage(), Double.isFinite(mean) ? mean : null);
            }
            measuredMeans.put(gas, means);
        }
        result.put("measured_capture_stage_means", measuredMeans);
        result.put("track_samples", episode.vehicleTrack().size()); result.put("wind_segments", episode.windHistory().size());
        result.put("simulation_seconds", episode.simulationNanos() / 1e9);
        WindowSelection.Result selected = WindowSelection.select(episode.runId(), episode.windows(), episode.inlets(), engine.world());
        var entries = selected.entries();
        boolean eligible = entries.stream().anyMatch(e -> e.value() >= Math.max(e.gas().floor(), 3 * e.sigma()));
        result.put("eligible", eligible); result.put("selected_entries", entries.size()); result.put("exclusions", selected.exclusions());
        result.put("statistic", null); result.put("failure", null);
        if (eligible) {
            try {
                var fit = engine.fit(episode.support(), episode.reportedWind(), entries, engine.prepare(entries), new HashMap<>());
                result.put("member_failures", fit.failures());
                var best = fit.best();
                if (best == null || !Double.isFinite(best.normalizedSse())) result.put("failure", "TRUE_SUPPORT_FIT_FAILED");
                else result.put("statistic", best.normalizedSse());
                if (cutoff != null) {
                    result.put("raw_true_support_covered", best != null && best.normalizedSse() <= cutoff);
                }
            } catch (RuntimeException error) {
                result.put("failure", "ELIGIBLE_FIT_EXCEPTION: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                result.put("statistic", null);
                if (cutoff != null) { result.put("raw_true_support_covered", false); result.put("final_true_support_survives", false); }
            }
        }
        if (cutoff != null && wholeRanker) {
            try {
                CompatRanker ranker = new CompatRanker(engine, new Cutoff(cutoff, 60, 58, "offline-full-chain", "independent", "pending"));
                Analysis analysis = ranker.analyze(new AnalysisInput(episode.runId(), id(episode.seed(), "analysis"),
                        episode.stages().get(4).endS(), episode.windows(), episode.reportedWind()), episode.inlets()).analysis();
                result.put("analysis", analysis);
                result.put("raw_cutoff_compatible", analysis.candidates().stream().filter(c -> !c.isUnknown() && c.fit() != null && c.fit() <= cutoff)
                        .map(Candidate::explanationId).toList());
                result.put("final_survivors", analysis.candidates().stream().filter(c -> c.status() == CandidateStatus.SURVIVES)
                        .map(Candidate::explanationId).toList());
                result.put("final_true_support_survives", analysis.candidates().stream().anyMatch(c -> c.explanationId().equals(episode.support().id())
                        && c.status() == CandidateStatus.SURVIVES));
            } catch (RuntimeException error) {
                result.put("analysis_failure", error.getClass().getSimpleName() + ": " + error.getMessage());
                result.put("final_true_support_survives", false);
            }
        }
        result.put("fit_seconds", (System.nanoTime() - began) / 1e9);
        return result;
    }

    /** CLI: mode, count, output, SD, tau, cutoff, optional evaluation namespace (FPEVAL4), start index. */
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "version".equals(args[0])) {
            System.out.println(CompatRanker.ALGORITHM);
            return;
        }
        String mode = args.length > 0 ? args[0] : "benchmark";
        int requested = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        Path output = Path.of(args.length > 2 ? args[2] : "target/full-chain-benchmark.json");
        if (output.toAbsolutePath().normalize().toString().contains("src/main/resources"))
            throw new IllegalArgumentException("Offline runner cannot overwrite runtime resources");
        Domain domain = new Domain(args.length > 3 ? Double.parseDouble(args[3]) : .5,
                args.length > 4 ? Double.parseDouble(args[4]) : 200, .01);
        boolean sensitivity = "sensitivity".equals(mode);
        boolean calibration = "calibrate".equals(mode), evaluation = "evaluate".equals(mode) || sensitivity;
        if (!(calibration || evaluation || "benchmark".equals(mode)) || requested <= 0)
            throw new IllegalArgumentException("Unknown mode or invalid count");
        if (calibration && requested < 40) throw new IllegalArgumentException("Calibration needs at least 40 eligible episodes");
        Double cutoff = args.length > 5 ? Double.valueOf(args[5]) : null;
        if (evaluation && cutoff == null) throw new IllegalArgumentException("Evaluation requires an explicit reviewed cutoff");
        if (cutoff != null && (!Double.isFinite(cutoff) || cutoff <= 0)) throw new IllegalArgumentException("Cutoff must be finite and positive");
        if (args.length > 8 || (args.length > 6 && !"evaluate".equals(mode)))
            throw new IllegalArgumentException("Only evaluate accepts an explicit evaluation namespace");
        String evaluationNamespace = args.length > 6 ? args[6] : "FPEVAL4";
        if ("evaluate".equals(mode) && !"FPEVAL4".equals(evaluationNamespace))
            throw new IllegalArgumentException("V4 evaluation requires fresh FPEVAL4 seeds; historical or development namespaces cannot qualify it");
        int attemptStart = args.length > 7 ? Integer.parseInt(args[7]) : 0;
        if (attemptStart < 0 || attemptStart > 998000)
            throw new IllegalArgumentException("Start index must leave room for all 2000 attempts inside the namespace");
        String namespace = calibration ? "FPCAL" : sensitivity ? "FPWIND/" + windCell(domain) : evaluation ? evaluationNamespace : "FPDEV";
        FitEngine engine = new FitEngine(fuelprint.science.rank.WorldProfile.load(), ResponseModel.TEACHING);
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> attempts = new ArrayList<>(); List<Double> statistics = new ArrayList<>();
        report.put("algorithm", CompatRanker.ALGORITHM); report.put("response", engine.response().label());
        report.put("fixed_baseline_cutoff_stress_test", sensitivity);
        report.put("mode", mode); report.put("namespace", namespace); report.put("domain", domain); report.put("requested", requested);
        report.put("attempt_start_index", attemptStart);
        report.put("generator", "PuffWorld20Hz + SensorField20Hz + TeachDetector saved1Hz fixed snapshot300 + actual gas/flow capture stages; constant emissions from0; measured BG never plume-corrected");
        report.put("route_limit", "Recorded inlet moves on a straight line at at most3m/s from depot(0,0,2) after300 to one of(0,100,35)/(120,100,35), arrival ceiling to whole second; excludes field autopilot acceleration/obstacle route/body-to-inlet offset");
        report.put("emission_domain", "Uniform support over six singles and fifteen pairs; wind U(240,300)deg/U(2,5)m/s; per-source SO2 logU(.25,50)g/s; CO2=SO2*1000*logU(.5,2); constant from run0");
        report.put("coverage_domain", "Coverage conditional on the same NO_SIGNAL eligibility gate; all attempts including generation exceptions of unknown eligibility are retained separately. Raw true-support compatibility and final NOT_NEEDED-filtered survivors are distinct.");
        report.put("rank_rule", "min(m,ceil(.95*(m+1))); every eligible failed fit retained as infinity");
        report.put("attempts", attempts); report.put("cutoff", cutoff);
        int eligible = 0;
        long began = System.nanoTime();
        ObjectMapper mapper = new ObjectMapper();
        for (int attempt = attemptStart; attempt < attemptStart + (calibration || evaluation ? 2000 : requested); attempt++) {
            Map<String, Object> measurement;
            try { measurement = measure(generate(seed(namespace, attempt), domain), engine, cutoff, evaluation); }
            catch (RuntimeException error) {
                measurement = new LinkedHashMap<>(); measurement.put("seed", seed(namespace, attempt)); measurement.put("eligible", null);
                measurement.put("failure", "GENERATION_OR_FIT_EXCEPTION: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                measurement.put("statistic", null);
            }
            attempts.add(measurement);
            if (Boolean.TRUE.equals(measurement.get("eligible"))) { eligible++; statistics.add((Double) measurement.get("statistic")); }
            report.put("eligible", eligible); report.put("attempt_count", attempts.size());
            report.put("elapsed_seconds", (System.nanoTime() - began) / 1e9);
            Files.createDirectories(output.toAbsolutePath().getParent()); mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println(mode + " attempt=" + attempt + " eligible=" + eligible + " statistic=" + measurement.get("statistic")
                    + " failure=" + measurement.get("failure") + " elapsed=" + report.get("elapsed_seconds"));
            if ((calibration || evaluation) && eligible >= requested) break;
        }
        report.put("requested_eligible_reached", !(calibration || evaluation) || eligible >= requested);
        if (calibration) {
            double computed = quantile(statistics);
            report.put("rank", Cutoff.rankFor(statistics.size()));
            report.put("cutoff", Double.isFinite(computed) ? computed : null);
            report.put("cutoff_reason", Double.isFinite(computed) ? "FINITE_FULL_CHAIN_ORDER_STATISTIC" : "ELIGIBLE_FAILURES_MAKE_CUTOFF_INFINITE");
        }
        if (evaluation) {
            report.put("conditional_eligible_denominator", eligible);
            report.put("all_attempt_denominator", attempts.size());
            report.put("raw_true_support_covered", attempts.stream().filter(a -> Boolean.TRUE.equals(a.get("raw_true_support_covered"))).count());
            report.put("final_true_support_survives", attempts.stream().filter(a -> Boolean.TRUE.equals(a.get("final_true_support_survives"))).count());
        }
        report.put("generation_or_fit_exceptions", attempts.stream().filter(a -> a.get("eligible") == null).count());
        report.put("whole_ranker_failures", attempts.stream().filter(a -> a.get("analysis_failure") != null).count());
        report.put("eligible_fit_failures", attempts.stream().filter(a -> Boolean.TRUE.equals(a.get("eligible")) && a.get("statistic") == null).count());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }
}
