package fuelprint.science.rank;

import fuelprint.api.Enu;

/**
 * The FP-COMPAT-3 steady candidate kernel: a ground-reflected Gaussian plume per unit emission (1 g/s).
 * <p>
 * With wind-from angle phi the air moves along d = (-sin phi, -cos phi). For a receptor at alongwind distance x
 * and lateral distance l from the source: sy2 = s0 + 2 Ky x / u, sz2 = s0 + 2 Kz x / u and
 * {@code C = exp(-l2/2sy2) / (2 pi u sy sz) * [exp(-(z-H)2/2sz2) + exp(-(z+H)2/2sz2)]} in g/m3 per g/s;
 * 0 when x <= 0 (the receptor is not downwind).
 */
public final class PlumeKernel {

    private final double sigma0Sq;
    private final double ky;
    private final double kz;
    private final double temperatureK;
    private final double pressurePa;

    public PlumeKernel(double sigma0Sq, double ky, double kz, double temperatureK, double pressurePa) {
        this.sigma0Sq = sigma0Sq;
        this.ky = ky;
        this.kz = kz;
        this.temperatureK = temperatureK;
        this.pressurePa = pressurePa;
    }

    public static PlumeKernel of(WorldProfile world) {
        return new PlumeKernel(world.nominal("sigma0_sq_m2"), world.nominal("Ky_m2_s"), world.nominal("Kz_m2_s"),
                world.nominal("temperature_K"), world.nominal("pressure_Pa"));
    }

    /** Unit concentration in g/m3 per g/s at the receptor. */
    public double unitGramsPerM3(double sourceEast, double sourceNorth, double heightM, Enu receptor,
                                 double windFromDeg, double speedMS) {
        double phi = Math.toRadians(windFromDeg);
        double de = -Math.sin(phi);
        double dn = -Math.cos(phi);
        double re = receptor.east() - sourceEast;
        double rn = receptor.north() - sourceNorth;
        double x = re * de + rn * dn;
        if (!(x > 0)) {
            return 0.0;
        }
        double l = -re * dn + rn * de;
        double sy2 = sigma0Sq + 2 * ky * x / speedMS;
        double sz2 = sigma0Sq + 2 * kz * x / speedMS;
        double z = receptor.up();
        double vertical = Math.exp(-(z - heightM) * (z - heightM) / (2 * sz2))
                + Math.exp(-(z + heightM) * (z + heightM) / (2 * sz2));
        return Math.exp(-l * l / (2 * sy2)) / (2 * Math.PI * speedMS * Math.sqrt(sy2 * sz2)) * vertical;
    }

    /** Unit response in the gas unit (ppb SO2 or ppm CO2) per g/s. */
    public double unit(Gas gas, double sourceEast, double sourceNorth, double heightM, Enu receptor,
                       double windFromDeg, double speedMS) {
        return gas.fromGramsPerM3(unitGramsPerM3(sourceEast, sourceNorth, heightM, receptor, windFromDeg, speedMS),
                temperatureK, pressurePa);
    }
}
