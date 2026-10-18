package fuelprint.science.sensor;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The hook for the background SO2 level. By default 5 ppb. If
 * {@code models/background-so2-profile.json} exists on the class path (a later job may add it: median ppb per
 * month and IST hour from a real CPCB station), the level follows it.
 *
 * <p>Accepted file shape: either {@code {"median_ppb": {"1": [24 numbers], ... "12": [...]}}} or the month map
 * itself at the top level. Month keys are "1" to "12" (or "01" to "12"); the array index is the IST hour 0 to 23.
 */
public final class BackgroundProfile {

    public static final double DEFAULT_SO2_PPB = 5.0;
    public static final String RESOURCE = "/models/background-so2-profile.json";
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final BackgroundProfile DEFAULT = new BackgroundProfile(null);

    private final double[][] byMonthHour;

    private BackgroundProfile(double[][] byMonthHour) {
        this.byMonthHour = byMonthHour;
    }

    /** The constant 5 ppb profile. */
    public static BackgroundProfile constant() {
        return DEFAULT;
    }

    /** Loads the profile file if present, otherwise the 5 ppb default. */
    public static BackgroundProfile loadOrDefault() {
        try (InputStream in = BackgroundProfile.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return DEFAULT;
            }
            return parse(in).orElse(DEFAULT);
        } catch (IOException | RuntimeException e) {
            return DEFAULT;
        }
    }

    /** Parses a profile; empty if the shape is not recognised. */
    public static Optional<BackgroundProfile> parse(InputStream in) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        JsonNode months = root.has("median_ppb") ? root.get("median_ppb") : root;
        double[][] table = new double[12][];
        int found = 0;
        Iterator<Map.Entry<String, JsonNode>> it = months.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            int m;
            try {
                m = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            JsonNode arr = e.getValue();
            if (m < 1 || m > 12 || !arr.isArray() || arr.size() != 24) {
                continue;
            }
            double[] hours = new double[24];
            for (int h = 0; h < 24; h++) {
                if (!arr.get(h).isNumber()) {
                    return Optional.empty();
                }
                hours[h] = arr.get(h).asDouble();
            }
            table[m - 1] = hours;
            found++;
        }
        if (found == 0) {
            return Optional.empty();
        }
        // Months without data borrow the nearest month that has some.
        for (int m = 0; m < 12; m++) {
            if (table[m] == null) {
                for (int d = 1; d < 12 && table[m] == null; d++) {
                    double[] a = table[(m + d) % 12];
                    double[] b = table[(m - d + 12) % 12];
                    table[m] = a != null ? a : b;
                }
            }
        }
        return Optional.of(new BackgroundProfile(table));
    }

    /** True when a real profile is in use (not the 5 ppb default). */
    public boolean isFromFile() {
        return byMonthHour != null;
    }

    /** Background SO2 in ppb at an IST date and time. */
    public double so2Ppb(ZonedDateTime time) {
        if (byMonthHour == null) {
            return DEFAULT_SO2_PPB;
        }
        ZonedDateTime ist = time.withZoneSameInstant(IST);
        return byMonthHour[ist.getMonthValue() - 1][ist.getHour()];
    }
}
