package fuelprint.science.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import fuelprint.api.WindNow;

/**
 * The smoke world (R3 doc 03, S1). Every tick (0.05 s) each active source adds one puff per species mass
 * {@code q x 0.05 s}. A puff is a reflected Gaussian over z >= 0; its variance starts at 4 m2 and grows by
 * {@code 2 K x age} (Kx = Ky = 3, Kz = 1 m2/s).
 *
 * <p>Wind history: the world keeps the running sum of the wind (the path a parcel of air has travelled since the
 * run started). A puff stores that sum at its emission, so its centre is always
 * {@code source + path(now) - path(emission)}. A wind change therefore moves every existing puff along its own
 * chronological wind history, and mass never changes. Switching a source off only stops future puffs. Puffs are
 * never removed (not for speed, not for distance); at the cap the world refuses to advance instead.
 */
public final class PuffWorld {

    /** One simulation tick in seconds (20 Hz). */
    public static final double DT = 0.05;

    /** Why the world stopped advancing. */
    public enum Capacity {
        TIME_CAP, PUFF_CAP
    }

    /** One entry of the chronological wind history. */
    public record WindSegment(double fromTimeS, double fromDeg, double speedMS) {
    }

    /** Concentration at a point: SO2 in ppb and CO2 in ppm (the enhancement caused by puffs only). */
    public record Concentration(double so2Ppb, double co2Ppm) {
    }

    private final WorldProfile profile;
    private final int puffCap;
    private final double timeCapS;
    private final int sourceCount;
    private final double[] srcE;
    private final double[] srcN;
    private final double[] srcH;
    private final double[] qSo2;
    private final double[] qCo2;
    private final List<WindSegment> windHistory = new ArrayList<>();

    private long ticks;
    private double pendingS;
    private double windFromDeg;
    private double windSpeed;
    private double pathE;
    private double pathN;
    private Capacity capacity;

    // puff store, struct of arrays
    private int n;
    private byte[] pSrc = new byte[1024];
    private double[] pT0 = new double[1024];
    private double[] pPathE = new double[1024];
    private double[] pPathN = new double[1024];
    private double[] pSo2 = new double[1024];
    private double[] pCo2 = new double[1024];

    public PuffWorld(WorldProfile profile) {
        this(profile, profile.livePuffCap(), profile.liveSeconds());
    }

    public PuffWorld(WorldProfile profile, int puffCap, double timeCapS) {
        if (profile.kx() != profile.ky()) {
            throw new IllegalArgumentException("The puff model uses one horizontal diffusivity (Kx = Ky)");
        }
        this.profile = profile;
        this.puffCap = puffCap;
        this.timeCapS = timeCapS;
        this.sourceCount = profile.sources().size();
        this.srcE = new double[sourceCount];
        this.srcN = new double[sourceCount];
        this.srcH = new double[sourceCount];
        this.qSo2 = new double[sourceCount];
        this.qCo2 = new double[sourceCount];
        for (int i = 0; i < sourceCount; i++) {
            WorldProfile.Source s = profile.sources().get(i);
            srcE[i] = s.east();
            srcN[i] = s.north();
            srcH[i] = s.effectiveHeight();
        }
        setWind(profile.windFromDeg(), profile.windSpeed());
    }

    private int index(String sourceId) {
        for (int i = 0; i < sourceCount; i++) {
            if (profile.sources().get(i).id().equals(sourceId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown source " + sourceId);
    }

    /** Sets the emission rates of a source in g/s. Zero switches it off; its old puffs stay. */
    public void setEmission(String sourceId, double so2GramsPerSecond, double co2GramsPerSecond) {
        if (so2GramsPerSecond < 0 || co2GramsPerSecond < 0) {
            throw new IllegalArgumentException("Emission cannot be negative");
        }
        int i = index(sourceId);
        qSo2[i] = so2GramsPerSecond;
        qCo2[i] = co2GramsPerSecond;
    }

    /** Sets the wind. Existing puffs keep their past and are carried by the new wind from now on. */
    public void setWind(double fromDeg, double speedMS) {
        this.windFromDeg = fromDeg;
        this.windSpeed = speedMS;
        windHistory.add(new WindSegment(timeS(), fromDeg, speedMS));
    }

    public WindNow wind() {
        return new WindNow(windFromDeg, windSpeed);
    }

    /** The chronological wind history (every setting, with the time it started). */
    public List<WindSegment> windHistory() {
        return Collections.unmodifiableList(windHistory);
    }

    public double timeS() {
        return ticks * DT;
    }

    public int puffCount() {
        return n;
    }

    public Optional<Capacity> capacityReached() {
        return Optional.ofNullable(capacity);
    }

    /**
     * Advances by {@code dtS}, in whole ticks of 0.05 s (a remainder carries to the next call). Stops without
     * dropping anything when a cap would be passed.
     *
     * @return simulation seconds actually advanced
     */
    public double step(double dtS) {
        if (dtS < 0) {
            throw new IllegalArgumentException("dt must not be negative");
        }
        pendingS += dtS;
        long done = 0;
        while (pendingS >= DT - 1e-9) {
            if (capacity != null) {
                pendingS = 0;
                break;
            }
            if ((ticks + 1) * DT > timeCapS + 1e-9) {
                capacity = Capacity.TIME_CAP;
                pendingS = 0;
                break;
            }
            int emitting = 0;
            for (int i = 0; i < sourceCount; i++) {
                if (qSo2[i] > 0 || qCo2[i] > 0) {
                    emitting++;
                }
            }
            if (n + emitting > puffCap) {
                capacity = Capacity.PUFF_CAP;
                pendingS = 0;
                break;
            }
            emitTick();
            pathE += Units.windEast(windFromDeg, windSpeed) * DT;
            pathN += Units.windNorth(windFromDeg, windSpeed) * DT;
            ticks++;
            pendingS -= DT;
            done++;
        }
        return done * DT;
    }

    private void emitTick() {
        double t = timeS();
        for (int i = 0; i < sourceCount; i++) {
            if (qSo2[i] > 0 || qCo2[i] > 0) {
                ensureCapacity(n + 1);
                pSrc[n] = (byte) i;
                pT0[n] = t;
                pPathE[n] = pathE;
                pPathN[n] = pathN;
                pSo2[n] = qSo2[i] * DT;
                pCo2[n] = qCo2[i] * DT;
                n++;
            }
        }
    }

    private void ensureCapacity(int need) {
        if (need <= pT0.length) {
            return;
        }
        int size = Math.max(need, pT0.length * 2);
        pSrc = Arrays.copyOf(pSrc, size);
        pT0 = Arrays.copyOf(pT0, size);
        pPathE = Arrays.copyOf(pPathE, size);
        pPathN = Arrays.copyOf(pPathN, size);
        pSo2 = Arrays.copyOf(pSo2, size);
        pCo2 = Arrays.copyOf(pCo2, size);
    }

    /** Total mass in g of a species ("SO2" or "CO2") held by all puffs of one source. */
    public double totalMassG(String sourceId, String species) {
        int s = index(sourceId);
        double[] m = species.equals("SO2") ? pSo2 : species.equals("CO2") ? pCo2 : null;
        if (m == null) {
            throw new IllegalArgumentException("Unknown species " + species);
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            if (pSrc[i] == s) {
                sum += m[i];
            }
        }
        return sum;
    }

    /** Number of puffs that came from one source. */
    public int puffCount(String sourceId) {
        int s = index(sourceId);
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (pSrc[i] == s) {
                c++;
            }
        }
        return c;
    }

    /** Variance in m2 of the horizontal spread of a puff of the given age. */
    public double horizontalVariance(double ageS) {
        return profile.sigma0Sq() + 2 * profile.kx() * ageS;
    }

    /** Variance in m2 of the vertical spread of a puff of the given age. */
    public double verticalVariance(double ageS) {
        return profile.sigma0Sq() + 2 * profile.kz() * ageS;
    }

    /** Centre of puff {@code i} now, ENU metres. */
    public double[] puffCentre(int i) {
        return new double[] {srcE[pSrc[i]] + pathE - pPathE[i], srcN[pSrc[i]] + pathN - pPathN[i], srcH[pSrc[i]]};
    }

    /** Mass in g of puff {@code i} for a species. */
    public double puffMassG(int i, String species) {
        return species.equals("SO2") ? pSo2[i] : pCo2[i];
    }

    /** Concentration in g/m3 (SO2, CO2) of one puff at a point (reflected Gaussian over z >= 0). */
    double[] kernelGramsPerM3(int i, double east, double north, double up) {
        double age = timeS() - pT0[i];
        double sx2 = horizontalVariance(age);
        double sz2 = verticalVariance(age);
        double dx = east - (srcE[pSrc[i]] + pathE - pPathE[i]);
        double dy = north - (srcN[pSrc[i]] + pathN - pPathN[i]);
        double k = kernel(dx, dy, up, srcH[pSrc[i]], sx2, sz2);
        return new double[] {k * pSo2[i], k * pCo2[i]};
    }

    private static double kernel(double dx, double dy, double z, double h, double sx2, double sz2) {
        double r2 = dx * dx + dy * dy;
        if (r2 > 40 * sx2) {
            return 0;
        }
        double a = (z - h) * (z - h) / (2 * sz2);
        double b = (z + h) * (z + h) / (2 * sz2);
        if (Math.min(a, b) > 20) {
            return 0;
        }
        return Math.exp(-r2 / (2 * sx2)) / (2 * Math.PI * sx2) * (Math.exp(-a) + Math.exp(-b))
                / Math.sqrt(2 * Math.PI * sz2);
    }

    /** The smoke concentration of all puffs at a point, in ppb (SO2) and ppm (CO2). */
    public Concentration concentrationAt(double east, double north, double up) {
        double so2 = 0;
        double co2 = 0;
        double now = timeS();
        for (int i = 0; i < n; i++) {
            double age = now - pT0[i];
            double sx2 = profile.sigma0Sq() + 2 * profile.kx() * age;
            double sz2 = profile.sigma0Sq() + 2 * profile.kz() * age;
            int s = pSrc[i];
            double dx = east - (srcE[s] + pathE - pPathE[i]);
            double dy = north - (srcN[s] + pathN - pPathN[i]);
            double k = kernel(dx, dy, up, srcH[s], sx2, sz2);
            if (k != 0) {
                so2 += k * pSo2[i];
                co2 += k * pCo2[i];
            }
        }
        return new Concentration(Units.so2GramsPerM3ToPpb(so2), Units.co2GramsPerM3ToPpm(co2));
    }
}
