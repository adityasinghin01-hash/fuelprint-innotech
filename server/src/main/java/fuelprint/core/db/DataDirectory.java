package fuelprint.core.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The runtime data folder ({@code fp.data-dir}). Holds the SQLite database and the blob store.
 * A relative path is resolved against the repository root, so {@code data/runtime} means the
 * same folder whether the server starts from the repo root or from {@code server/}.
 */
@Component
public class DataDirectory {

    static final String DATABASE_FILE = "fuelprint.db";

    private final Path root;

    @Autowired
    public DataDirectory(@Value("${fp.data-dir:data/runtime}") String configured) {
        this(resolve(configured, Path.of("").toAbsolutePath()));
    }

    DataDirectory(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data folder " + this.root, e);
        }
    }

    public Path root() {
        return root;
    }

    public Path databaseFile() {
        return root.resolve(DATABASE_FILE);
    }

    public Path blobFolder() {
        return root.resolve("blobs");
    }

    /** Absolute paths are used as given; relative ones are resolved against the repository root. */
    static Path resolve(String configured, Path workingDirectory) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("fp.data-dir must not be empty");
        }
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path;
        }
        return findRepoRoot(workingDirectory).resolve(path);
    }

    /** Walks up from the working directory to the folder that holds {@code contracts/AGREEMENT.md}. */
    public static Path findRepoRoot(Path start) {
        for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("contracts").resolve("AGREEMENT.md"))
                    && Files.isDirectory(dir.resolve("server"))) {
                return dir;
            }
        }
        return start.toAbsolutePath();
    }
}
