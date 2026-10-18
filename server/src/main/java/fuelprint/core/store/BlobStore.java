package fuelprint.core.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import fuelprint.core.db.DataDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Content-addressed blob store at {@code <data-dir>/blobs/<sha256>}. Stores the exact bytes; every read
 * checks the hash again, so a changed or damaged file is detected and never used.
 *
 * <p>Order: a blob is written and flushed to disk before any record refers to it. A blob that no record
 * refers to (for example after a rolled-back command) is harmless and kept.
 */
@Component
public class BlobStore {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path folder;

    @Autowired
    public BlobStore(DataDirectory dataDirectory) {
        this(dataDirectory.blobFolder());
    }

    public BlobStore(Path folder) {
        this.folder = folder.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.folder);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create blob folder " + this.folder, e);
        }
    }

    /**
     * Stores {@code bytes} (if not already there) and returns their SHA-256 as lower-case hex.
     *
     * @throws BlobCorruptedException if a file with this hash already exists but holds other bytes
     */
    public String put(byte[] bytes) {
        String sha = sha256(bytes);
        Path target = folder.resolve(sha);
        if (Files.exists(target)) {
            get(sha); // verifies the existing copy
            return sha;
        }
        Path staging = null;
        try {
            staging = Files.createTempFile(folder, ".staging-", ".tmp");
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target);
            }
            staging = null;
            forceDirectory(folder);
            return sha;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store blob " + sha, e);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // a leftover staging file is harmless
                }
            }
        }
    }

    /**
     * Returns the exact stored bytes for {@code sha256}.
     *
     * @throws BlobMissingException   if no blob has this hash
     * @throws BlobCorruptedException if the file no longer matches its hash
     */
    public byte[] get(String sha256) {
        Path file = pathOf(sha256);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new BlobMissingException(sha256);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read blob " + sha256, e);
        }
        String actual = sha256(bytes);
        if (!actual.equals(sha256)) {
            throw new BlobCorruptedException(sha256, actual);
        }
        return bytes;
    }

    /** True if a file for this hash exists (it is not verified; {@link #get} verifies). */
    public boolean exists(String sha256) {
        return Files.isRegularFile(pathOf(sha256));
    }

    /** The file path for a hash. Only a valid lower-case SHA-256 is accepted, so no other path can be reached. */
    public Path pathOf(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Not a lower-case SHA-256 hex string");
        }
        return folder.resolve(sha256);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Flushes the directory entry so the rename survives a power cut. Not supported on every OS. */
    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows cannot open a directory as a channel; the file itself is already flushed.
        }
    }

    /** No blob is stored under this hash. */
    public static class BlobMissingException extends RuntimeException {
        public BlobMissingException(String sha256) {
            super("No blob " + sha256);
        }
    }

    /** The stored file does not match its hash: it was changed or damaged. */
    public static class BlobCorruptedException extends RuntimeException {
        public BlobCorruptedException(String expected, String actual) {
            super("Blob " + expected + " is damaged: its bytes now hash to " + actual);
        }
    }
}
