package fuelprint.science.rank;

import java.util.Optional;

/** The two modelled gases. Each has its own independent non-negative source amplitudes. */
public enum Gas {
    SO2("SO2_ppb", 64.066, 1e9, 25.0, "SO2_innovation_sd_ppb", "SO2_loading_ppb"),
    CO2("CO2_ppm", 44.01, 1e6, 5.0, "CO2_innovation_sd_ppm", "CO2_loading_ppm");

    /** Universal gas constant, J/(mol K). */
    public static final double R = 8.314462618;

    private final String channel;
    private final double molarMassG;
    private final double mixingScale;
    private final double floor;
    private final String innovationKey;
    private final String loadingKey;

    Gas(String channel, double molarMassG, double mixingScale, double floor, String innovationKey, String loadingKey) {
        this.channel = channel;
        this.molarMassG = molarMassG;
        this.mixingScale = mixingScale;
        this.floor = floor;
        this.innovationKey = innovationKey;
        this.loadingKey = loadingKey;
    }

    public String channel() {
        return channel;
    }

    /** Frozen teaching floor for the NO_SIGNAL gate: 25 ppb SO2 or 5 ppm CO2 (R3 doc 03). */
    public double floor() {
        return floor;
    }

    public String innovationKey() {
        return innovationKey;
    }

    /** world-profile.json nominal emission key (g/s). */
    public String nominalKey() {
        return name() + "_g_s";
    }

    public String loadingKey() {
        return loadingKey;
    }

    /** Converts a mass concentration in g/m3 to ppb (SO2) or ppm (CO2) at the declared temperature and pressure. */
    public double fromGramsPerM3(double gPerM3, double temperatureK, double pressurePa) {
        return gPerM3 / molarMassG * (R * temperatureK / pressurePa) * mixingScale;
    }

    public static Optional<Gas> ofChannel(String channel) {
        for (Gas g : values()) {
            if (g.channel.equals(channel)) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }
}
