package fuelprint.field;

import java.nio.file.Path;

import fuelprint.field.profile.CaptureMethod;
import fuelprint.field.profile.ProfileLoader;
import fuelprint.field.profile.WorldProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Loads the world profile and the capture method from the repository once at start-up. The repository
 * root is found by walking up from the working folder, or set with {@code fp.field.repo-root}.
 */
@Configuration
@Profile("!stub")
public class FieldConfig {

    @Bean
    public WorldProfile fieldWorldProfile(@Value("${fp.field.repo-root:}") String repoRoot) {
        return ProfileLoader.load(root(repoRoot));
    }

    @Bean
    public CaptureMethod fieldCaptureMethod(@Value("${fp.field.repo-root:}") String repoRoot) {
        return CaptureMethod.load(root(repoRoot));
    }

    private static Path root(String configured) {
        return configured == null || configured.isBlank()
                ? ProfileLoader.findRepoRoot(Path.of(""))
                : Path.of(configured);
    }
}
