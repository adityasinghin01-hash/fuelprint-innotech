package fuelprint.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for core tests that need the Spring context. All subclasses share one context and one temporary
 * data folder, so every test uses fresh UUIDs for its records and commands.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class CoreSpringTest {

    protected static final Path DATA_DIR = TempFolders.create("fp-core-test-");
    /** Where tests put fake built apps ({@code fp.apps-dir}). */
    protected static final Path APPS_DIR = DATA_DIR.resolve("apps");

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("fp.data-dir", DATA_DIR::toString);
        // Tests drive the world loop by hand (WorldLoop.wallStep) instead of the 20 Hz thread.
        registry.add("fp.loop.auto", () -> "false");
        registry.add("fp.apps-dir", APPS_DIR::toString);
    }

    /** Temporary folders deleted when the test JVM exits. */
    public static final class TempFolders {
        private TempFolders() {
        }

        public static Path create(String prefix) {
            try {
                Path dir = Files.createTempDirectory(prefix);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir)));
                return dir;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public static void deleteTree(Path dir) {
            if (!Files.exists(dir)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
