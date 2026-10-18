package fuelprint.alerts;

import fuelprint.core.CoreSpringTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * The default alerts test context: its own data folder, the test doubles of {@link AlertsTestConfig},
 * LOG_ONLY gateway with the default cap, and no midnight timer.
 */
@SpringBootTest
@ActiveProfiles("real")
@AutoConfigureMockMvc
@Import(AlertsTestConfig.class)
abstract class AlertsSpringTest extends AlertsFlow {

    protected static final Path DATA_DIR = CoreSpringTest.TempFolders.create("fp-alerts-test-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fp.data-dir", DATA_DIR::toString);
        registry.add("fp.alerts.sms-config", () -> DATA_DIR.resolve("no-such-sms.json").toString());
        registry.add("fp.alerts.scheduler-enabled", () -> "false");
        registry.add("fp.alerts.retry-delay-ms", () -> "0");
    }
}
