package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** REAL mode with settings that cannot work (no password): SUPPRESSED GATEWAY_OFF, never silently dropped. */
class GatewayOffTest extends AlertsConfiguredSpringTest {

    static final Path FOLDER = newFolder("fp-alerts-off-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        write(FOLDER, "{\"mode\":\"REAL\",\"gateway_url\":\"http://127.0.0.1:9\",\"username\":\"stall\"}");
        configure(registry, FOLDER);
    }

    @Test
    void anUnusableRealGatewaySuppressesTheNotificationWithTheReason() {
        signUp(GUEST_1, NUMBER_A, "AREA-E");
        int before = notifications().size();

        openInvestigation(List.of("AREA-E"));

        List<StoredRecord> added = notificationsAfter(before);
        assertThat(added).hasSize(1);
        JsonNode body = added.get(0).body();
        assertThat(body.path("state").asText()).isEqualTo("SUPPRESSED");
        assertThat(body.path("suppressed_reason").asText()).isEqualTo("GATEWAY_OFF");
        assertThat(body.path("gateway_mode").asText()).isEqualTo("REAL");
        assertThat(body.path("recipients").asInt()).isEqualTo(1);
        assertThat(body.path("sent").asInt()).isZero();
        assertThat(gateway.sent).isEmpty();
    }
}
