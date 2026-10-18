package fuelprint.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobStoreTest {

    @TempDir
    Path dir;

    // 11
    @Test
    void returnsTheExactBytesUnderTheirSha256() throws Exception {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        byte[] bytes = new byte[70_000];
        new Random(7).nextBytes(bytes);

        String sha = blobs.put(bytes);

        assertThat(sha).isEqualTo(BlobStore.sha256(bytes)).matches("[0-9a-f]{64}");
        assertThat(blobs.get(sha)).isEqualTo(bytes);
        assertThat(Files.readAllBytes(dir.resolve("blobs").resolve(sha))).isEqualTo(bytes);
        assertThat(blobs.put(bytes)).as("storing again is a no-op").isEqualTo(sha);
        String empty = blobs.put(new byte[0]);
        assertThat(blobs.get(empty)).isEmpty();
    }

    // 11
    @Test
    void aChangedBlobFileIsDetected() throws Exception {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        String sha = blobs.put("evidence bytes".getBytes());
        Path file = blobs.pathOf(sha);
        byte[] changed = Files.readAllBytes(file);
        changed[0] ^= 1;
        Files.write(file, changed);

        assertThatThrownBy(() -> blobs.get(sha)).isInstanceOf(BlobStore.BlobCorruptedException.class);
        assertThatThrownBy(() -> blobs.put("evidence bytes".getBytes()))
                .isInstanceOf(BlobStore.BlobCorruptedException.class);
    }

    @Test
    void aMissingBlobIsReportedAndBadHashesCannotReachOtherPaths() {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        String absent = "0".repeat(64);
        assertThat(blobs.exists(absent)).isFalse();
        assertThatThrownBy(() -> blobs.get(absent)).isInstanceOf(BlobStore.BlobMissingException.class);
        assertThatThrownBy(() -> blobs.get("../fuelprint.db")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> blobs.get("A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noStagingFilesAreLeftBehind() throws Exception {
        BlobStore blobs = new BlobStore(dir.resolve("blobs"));
        blobs.put("a".getBytes());
        blobs.put("b".getBytes());
        try (var files = Files.list(dir.resolve("blobs"))) {
            assertThat(files.map(p -> p.getFileName().toString())).allMatch(name -> name.matches("[0-9a-f]{64}"));
        }
    }
}
