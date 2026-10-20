package fuelprint.science.rank;

import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.science.calibration.FullChainCalibrationRunner;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproducible full-chain order-statistic calibration. The historical quasi-steady generator is retired. */
public final class CalibrationRunner {
    public static final long SEED_BASE = 0x465043414C000000L;
    public static final int EPISODES = 60;
    public static final int MAX_ATTEMPTS = 2000;
    public static final String DEFAULT_OUTPUT = "target/full-chain-cutoff-candidate.json";

    public record Episode(long seed, String support, double statistic) { }
    public record Computation(double cutoff, int rank, int attempts, List<Episode> episodes, String sha256,
                              List<Map<String,Object>> attemptManifest) { }

    private CalibrationRunner() { }

    public static Computation compute(FitEngine engine) { return compute(engine,.5); }

    public static Computation compute(FitEngine engine,double directionSdDeg) {
        var domain=new FullChainCalibrationRunner.Domain(directionSdDeg,200,.01);
        List<Episode> eligible=new ArrayList<>();
        List<Map<String,Object>> manifest=new ArrayList<>();
        for(int attempt=0;attempt<MAX_ATTEMPTS && eligible.size()<EPISODES;attempt++) {
            long seed=FullChainCalibrationRunner.seed("FPCAL",attempt);
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("seed",seed);
            try {
                var episode=FullChainCalibrationRunner.generate(seed,domain);
                var measured=FullChainCalibrationRunner.measure(episode,engine,null,false);
                for(String key:List.of("support","eligible","statistic","failure","selected_entries","member_failures"))
                    row.put(key,measured.get(key));
                if(Boolean.TRUE.equals(measured.get("eligible"))) {
                    Double statistic=(Double)measured.get("statistic");
                    eligible.add(new Episode(seed,episode.support().id(),statistic==null?Double.POSITIVE_INFINITY:statistic));
                }
            } catch(RuntimeException failure) {
                row.put("eligible",null); row.put("statistic",null);
                row.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());
            }
            manifest.add(row);
        }
        if(eligible.size()!=EPISODES) throw new IllegalStateException("Only "+eligible.size()+" of "+EPISODES+" requested eligible episodes");
        double cutoff=FullChainCalibrationRunner.quantile(eligible.stream().map(Episode::statistic).toList());
        if(!Double.isFinite(cutoff)) throw new IllegalStateException("Eligible failures made the calibration cutoff infinite");
        return new Computation(cutoff,Cutoff.rankFor(eligible.size()),manifest.size(),List.copyOf(eligible),
                statisticsHash(eligible.stream().mapToDouble(Episode::statistic).toArray()),List.copyOf(manifest));
    }

    /** SHA256 of sorted IEEE754 binary64 values, big endian; independent of JSON number formatting. */
    public static String statisticsHash(double[] statistics) {
        try {
            double[] sorted=statistics.clone(); java.util.Arrays.sort(sorted);
            ByteBuffer bytes=ByteBuffer.allocate(sorted.length*Double.BYTES);
            for(double value:sorted) bytes.putDouble(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Map<String,Object> describe(Computation c,FitEngine engine) { return describe(c,engine,.5); }

    public static Map<String,Object> describe(Computation c,FitEngine engine,double directionSdDeg) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("algorithm",CompatRanker.ALGORITHM); out.put("world",engine.world().version());
        out.put("response",engine.response().label());
        out.put("cutoff",c.cutoff()); out.put("episodes",c.episodes().size()); out.put("rank",c.rank()); out.put("attempts",c.attempts());
        out.put("statistic","true-support minimum joint-covariance whitened SSE / selected entries over 27 nuisance members; eligible failures retained as infinity");
        out.put("rank_rule","min(m, ceil(0.95 (m + 1)))");
        out.put("method","fuelprint.science.rank.CalibrationRunner using FullChainCalibrationRunner; "+CompatRanker.ALGORITHM
                +"; world "+engine.world().version()+"; response "+engine.response().label());
        out.put("seeds","FPCAL: java.util.SplittableRandom 0x465043414C000000 + i, i=0.."+(c.attempts()-1)+"; never the SC1-SC3 seeds");
        out.put("domain","6 singles and 15 pairs uniformly; wind U(240,300)deg/U(2,5)m/s; per-source SO2 log-uniform0.25..50g/s, CO2/SO2 log-uniform500..2000; fixed detector snapshot300s plus subsequent two-gas capture");
        out.put("generator","actual PuffWorld20Hz + SensorField20Hz + TeachDetector1Hz + TeachCaptureRules.windows; constant emissions from0; measured backgrounds retain plume; no inverse-kernel-generated observations");
        out.put("route_limit","Calibration inlet moves in a straight line at <=3m/s from(0,0,2) to(0,100,35) or(120,100,35); real field acceleration/obstacles/body-to-inlet offset are not sampled by this calibration domain");
        out.put("coverage_limit","Conditional on the declared NO_SIGNAL gate and this generator only. SC1 CO2/SO2 ratio25 is outside the declared500..2000 domain. Raw-fit coverage does not imply final-survivor coverage after NOT_NEEDED.");
        out.put("wind_direction_sd_deg",directionSdDeg); out.put("wind_time_scale_s",200); out.put("relative_speed_sd",.01);
        double[] values=c.episodes().stream().mapToDouble(Episode::statistic).sorted().toArray();
        out.put("statistic_min",values[0]); out.put("statistic_median",values[values.length/2]); out.put("statistic_max",values[values.length-1]);
        out.put("statistics_sha256",c.sha256()); out.put("statistics_sha256_format","sorted IEEE754 binary64, big endian");
        out.put("episode_statistics",c.episodes()); out.put("attempt_manifest",c.attemptManifest());
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length>0?args[0]:DEFAULT_OUTPUT);
        if(output.toAbsolutePath().normalize().toString().contains("src/main/resources"))
            throw new IllegalArgumentException("Write a reviewable offline candidate; runtime installation is a separate reviewed step");
        double sd=args.length>1?Double.parseDouble(args[1]):.5;
        var engine=new FitEngine(WorldProfile.load(),ResponseModel.TEACHING);
        var computed=compute(engine,sd);
        Files.createDirectories(output.toAbsolutePath().getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),describe(computed,engine,sd));
        System.out.println("Full-chain candidate "+computed.cutoff()+", "+computed.episodes().size()+" eligible / "+computed.attempts()+" attempts: "+output);
    }
}
