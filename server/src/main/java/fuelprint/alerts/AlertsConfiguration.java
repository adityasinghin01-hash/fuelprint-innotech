package fuelprint.alerts;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Wires the alerts settings and the SMS gateway. Real beans only (not in the {@code stub} profile). */
@Configuration
@Profile("!stub")
class AlertsConfiguration {

    /**
     * {@code fp.alerts.sms-config}: the settings file. A relative path is resolved against the repository
     * root (the folder holding {@code contracts/AGREEMENT.md}). Tests point it at a file that does not
     * exist, which means LOG_ONLY.
     */
    @Bean
    SmsSettings smsSettings(ObjectMapper mapper,
                            @Value("${fp.alerts.sms-config:server/config/sms.json}") String configured) {
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = repoRoot(Path.of("").toAbsolutePath()).resolve(path);
        }
        return SmsSettings.load(path, mapper);
    }

    @Bean
    AlertsSmsGateway smsGateway(SmsSettings settings, ObjectMapper mapper,
                                @Value("${fp.alerts.retry-delay-ms:500}") long retryDelayMs) {
        return new AlertsSmsGateway(settings, mapper, retryDelayMs);
    }

    static Path repoRoot(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("contracts").resolve("AGREEMENT.md"))) {
                return dir;
            }
        }
        return start;
    }
}
