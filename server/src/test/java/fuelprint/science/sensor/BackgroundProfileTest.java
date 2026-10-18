package fuelprint.science.sensor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class BackgroundProfileTest {

    private static ZonedDateTime ist(int month, int hour) {
        return ZonedDateTime.of(2026, month, 10, hour, 30, 0, 0, BackgroundProfile.IST);
    }

    private static String hours(int base) {
        StringBuilder sb = new StringBuilder("[");
        for (int h = 0; h < 24; h++) {
            sb.append(base + h).append(h < 23 ? "," : "]");
        }
        return sb.toString();
    }

    @Test
    void theDefaultIsFivePpb() {
        assertThat(BackgroundProfile.constant().so2Ppb(ist(1, 2))).isEqualTo(5.0);
        assertThat(BackgroundProfile.constant().isFromFile()).isFalse();
    }

    @Test
    void aProfileGivesMedianPpbByMonthAndIstHour() throws Exception {
        String json = "{\"station\":\"test\",\"median_ppb\":{\"11\":" + hours(10) + ",\"1\":" + hours(30) + "}}";
        BackgroundProfile p = BackgroundProfile.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        assertThat(p.isFromFile()).isTrue();
        assertThat(p.so2Ppb(ist(11, 0))).isEqualTo(10.0);
        assertThat(p.so2Ppb(ist(11, 23))).isEqualTo(33.0);
        assertThat(p.so2Ppb(ist(1, 5))).isEqualTo(35.0);
        // a month without data borrows the nearest month that has some (December sits next to 11 and 1)
        assertThat(p.so2Ppb(ist(12, 0))).isIn(10.0, 30.0);
    }

    @Test
    void theMonthMapMayBeAtTheTopLevelAndUtcIsConvertedToIst() throws Exception {
        String json = "{\"1\":" + hours(0) + "}";
        BackgroundProfile p = BackgroundProfile.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        ZonedDateTime utc = ZonedDateTime.of(2026, 1, 10, 20, 0, 0, 0, java.time.ZoneOffset.UTC); // 01:30 IST next day
        assertThat(p.so2Ppb(utc)).isEqualTo(1.0);
    }

    @Test
    void anUnknownShapeIsIgnored() throws Exception {
        assertThat(BackgroundProfile.parse(new ByteArrayInputStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8))))
                .isEmpty();
        assertThat(BackgroundProfile.parse(new ByteArrayInputStream("{\"1\":[1,2,3]}".getBytes(StandardCharsets.UTF_8))))
                .isEmpty();
    }

    @Test
    void loadingNeverFailsWhateverIsOnTheClassPath() {
        assertThat(BackgroundProfile.loadOrDefault()).isNotNull();
    }
}
