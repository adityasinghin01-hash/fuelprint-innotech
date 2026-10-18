package fuelprint.alerts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import fuelprint.core.CoreSpringTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for alerts tests that need their own {@code sms.json}: the subclass gives the file's text and
 * registers it with a {@code @DynamicPropertySource} that calls {@link #configure}.
 */
@SpringBootTest
@ActiveProfiles("real")
@AutoConfigureMockMvc
@Import(AlertsTestConfig.class)
abstract class AlertsConfiguredSpringTest extends AlertsFlow {

    /** A fresh data folder and an sms.json with {@code smsJson}; returns the properties to register. */
    static Path newFolder(String prefix) {
        return CoreSpringTest.TempFolders.create(prefix);
    }

    static void write(Path folder, String smsJson) {
        try {
            Files.writeString(folder.resolve("sms.json"), smsJson);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void configure(org.springframework.test.context.DynamicPropertyRegistry registry, Path folder) {
        registry.add("fp.data-dir", folder::toString);
        registry.add("fp.alerts.sms-config", () -> folder.resolve("sms.json").toString());
        registry.add("fp.alerts.scheduler-enabled", () -> "false");
        registry.add("fp.alerts.retry-delay-ms", () -> "0");
    }
}
