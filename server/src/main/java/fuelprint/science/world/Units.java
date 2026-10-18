package fuelprint.science.world;

/** Physical constants of the teaching world (R3 doc 03: 298.15 K, 101325 Pa, molar masses 64.066 and 44.0095 g/mol). */
public final class Units {

    public static final double R = 8.314462618;
    public static final double TEMPERATURE_K = 298.15;
    public static final double PRESSURE_PA = 101325.0;
    public static final double MOLAR_MASS_SO2 = 64.066;
    public static final double MOLAR_MASS_CO2 = 44.0095;
    /** Molar volume of air in m3 per mol at the default temperature and pressure. */
    public static final double MOLAR_VOLUME_M3 = R * TEMPERATURE_K / PRESSURE_PA;

    private Units() {
    }

    /** g per m3 of SO2 to parts per billion. */
    public static double so2GramsPerM3ToPpb(double gPerM3) {
        return gPerM3 / MOLAR_MASS_SO2 * MOLAR_VOLUME_M3 * 1e9;
    }

    /** g per m3 of CO2 to parts per million. */
    public static double co2GramsPerM3ToPpm(double gPerM3) {
        return gPerM3 / MOLAR_MASS_CO2 * MOLAR_VOLUME_M3 * 1e6;
    }

    /** Wind east component in m/s. {@code fromDeg} is where the wind comes FROM, clockwise from north. */
    public static double windEast(double fromDeg, double speed) {
        return -speed * Math.sin(Math.toRadians(fromDeg));
    }

    /** Wind north component in m/s. */
    public static double windNorth(double fromDeg, double speed) {
        return -speed * Math.cos(Math.toRadians(fromDeg));
    }
}
