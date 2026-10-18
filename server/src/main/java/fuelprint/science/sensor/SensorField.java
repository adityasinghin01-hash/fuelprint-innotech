package fuelprint.science.sensor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

import fuelprint.api.InletPose;
import fuelprint.api.ObservationProvider;
import fuelprint.api.Origin;
import fuelprint.api.Reading;
import fuelprint.api.ReadingBatch;
import fuelprint.science.world.PuffWorld;
import fuelprint.science.world.ScienceWorld;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The real {@link ObservationProvider} (R3 doc 03, S2). For every inlet it makes SO2, CO2, a particle estimate
 * and the two branch flows.
 *
 * <p>Smoke concentration at an inlet is sampled once per simulated second (the instruments report at 1 Hz) and
 * held; transport, chamber and sensor response then step every tick with the exact first-order update.
 * Ambient = background level + loading x the shared AR(1) process G + smoke. Readings are always
 * {@code SYNTHETIC_TEACHING}.
 */
@Component
@Profile("!stub")
public class SensorField implements ObservationProvider, CollectionHook {

    public static final String SO2 = "SO2_ppb";
    public static final String CO2 = "CO2_ppm";
    public static final String PM = "PM_ugm3";
    public static final String SO2_FLOW = "SO2_flow_L_min";
    public static final String CO2_FLOW = "CO2_flow_L_min";

    static final double BASE_CO2_PPM = 420.0;
    static final double G_SO2_LOADING = 1.0;
    static final double G_CO2_LOADING = 0.5;
    static final double PM_BASE = 30.0;

    private static final double T90_SO2 = 60.0;
    private static final double T90_CO2 = 30.0;

    private final ScienceWorld world;
    private final BackgroundProfile background;

    private final Map<String, Inlet> inlets = new HashMap<>();
    private final Map<String, Double> collectionStart = new HashMap<>();
    private long seed = 1;
    private ZonedDateTime startLocal = ZonedDateTime.of(2026, 12, 5, 2, 0, 0, 0, BackgroundProfile.IST);
    private SplittableRandom commonRng = new SplittableRandom(1);
    private Ar1 common;
    private long commonSecond = Long.MIN_VALUE;
    private boolean collectionFault;
    private double faultAfterS = 10.0;

    @Autowired
    public SensorField(ScienceWorld world) {
        this(world, BackgroundProfile.loadOrDefault());
    }

    public SensorField(ScienceWorld world, BackgroundProfile background) {
        this.world = world;
        this.background = background;
        reset(1, startLocal, false);
    }

    /** Starts a fresh set of instruments for a run. */
    public synchronized void reset(long seed, ZonedDateTime startLocal, boolean failSo2FlowAfterCollectionStart) {
        this.seed = seed;
        this.startLocal = startLocal;
        this.inlets.clear();
        this.collectionStart.clear();
        this.commonRng = new SplittableRandom(seed * 31 + 7);
        this.common = new Ar1(0.95, 1.0, commonRng);
        this.commonSecond = Long.MIN_VALUE;
        this.collectionFault = failSo2FlowAfterCollectionStart;
    }

    @Override
    public synchronized void collectionStarted(String vehicleId, double simTimeS) {
        collectionStart.putIfAbsent(vehicleId, simTimeS);
    }

    /** Forgets the collection start of a vehicle (a new attempt). */
    public synchronized void collectionEnded(String vehicleId) {
        collectionStart.remove(vehicleId);
    }

    /** The background SO2 level used now (ppb), after the hook. */
    public synchronized double backgroundSo2Ppb(double simTimeS) {
        return background.so2Ppb(startLocal.plusNanos((long) (simTimeS * 1e9)));
    }

    @Override
    public synchronized ReadingBatch observe(double simTimeS, List<InletPose> poses) {
        Optional<PuffWorld> w = world.current();
        if (w.isEmpty()) {
            return new ReadingBatch(simTimeS, List.of());
        }
        long second = (long) Math.floor(simTimeS + 1e-9);
        if (commonSecond == Long.MIN_VALUE) {
            commonSecond = second;
        }
        while (commonSecond < second) {
            common.next(commonRng);
            commonSecond++;
        }
        List<Reading> out = new ArrayList<>();
        for (InletPose p : poses) {
            out.addAll(observeOne(w.get(), simTimeS, second, p));
        }
        return new ReadingBatch(simTimeS, out);
    }

    private double[] ambientNow(PuffWorld w, double simTimeS, InletPose p) {
        PuffWorld.Concentration c = w.concentrationAt(p.inlet().east(), p.inlet().north(), p.inlet().up());
        double g = common.value();
        double so2 = backgroundSo2Ppb(simTimeS) + G_SO2_LOADING * g + c.so2Ppb();
        double co2 = BASE_CO2_PPM + G_CO2_LOADING * g + c.co2Ppm();
        return new double[] {so2, co2, c.so2Ppb()};
    }

    private List<Reading> observeOne(PuffWorld w, double simTimeS, long second, InletPose p) {
        Inlet in = inlets.get(p.sensorOrVehicleId());
        double[] amb;
        if (in == null || simTimeS - in.lastTimeS > 5.0) {
            amb = ambientNow(w, simTimeS, p);
            long inletSeed = seed * 1_000_003L + p.label().hashCode() * 7919L + p.sensorOrVehicleId().hashCode();
            in = new Inlet(p.sensorOrVehicleId(), p.label(), inletSeed, amb[0], amb[1], simTimeS,
                    new Inlet.NoiseSettings(0.8, 5.0, 2.0), T90_SO2, T90_CO2);
            in.lastSecond = second;
            inlets.put(p.sensorOrVehicleId(), in);
            in.pmRaw = pmRaw(in, amb[2]);
            in.cached = readings(in, simTimeS, in.so2.advance(amb[0], 1.0, 0, false, in.rng),
                    in.co2.advance(amb[1], 1.0, 0, false, in.rng));
            return in.cached;
        }
        double dt = simTimeS - in.lastTimeS;
        if (dt <= 1e-9) {
            return in.cached;
        }
        boolean boundary = second > in.lastSecond;
        if (boundary) {
            amb = ambientNow(w, simTimeS, p);
            in.ambientSo2 = amb[0];
            in.ambientCo2 = amb[1];
            in.pmRaw = pmRaw(in, amb[2]);
            in.lastSecond = second;
        }
        double so2Factor = 1.0;
        Double started = collectionStart.get(in.id);
        if (collectionFault && !in.fixed && started != null && simTimeS >= started + faultAfterS - 1e-9) {
            so2Factor = 0.0;
        }
        GasChannel.Output so2 = in.so2.advance(in.ambientSo2, so2Factor, dt, boundary, in.rng);
        GasChannel.Output co2 = in.co2.advance(in.ambientCo2, 1.0, dt, boundary, in.rng);
        in.lastTimeS = simTimeS;
        in.cached = readings(in, simTimeS, so2, co2);
        return in.cached;
    }

    private double pmRaw(Inlet in, double plumeSo2) {
        return PM_BASE + 2.0 * common.value() + 0.1 * plumeSo2 + in.pmNoise.next(in.rng);
    }

    private static List<Reading> readings(Inlet in, double t, GasChannel.Output so2, GasChannel.Output co2) {
        Origin o = Origin.SYNTHETIC_TEACHING;
        List<Reading> r = new ArrayList<>(5);
        r.add(new Reading(in.id, SO2, so2.value(), so2.raw(), "ppb", t, so2.quality(), o));
        r.add(new Reading(in.id, CO2, co2.value(), co2.raw(), "ppm", t, co2.quality(), o));
        r.add(new Reading(in.id, PM, in.pmRaw, in.pmRaw, "ug_m3", t, List.of("OK", "OPTICAL_ESTIMATE"), o));
        r.add(new Reading(in.id, SO2_FLOW, so2.flowLMin(), so2.flowLMin(), "L_min", t, so2.quality(), o));
        r.add(new Reading(in.id, CO2_FLOW, co2.flowLMin(), co2.flowLMin(), "L_min", t, co2.quality(), o));
        return List.copyOf(r);
    }
}
