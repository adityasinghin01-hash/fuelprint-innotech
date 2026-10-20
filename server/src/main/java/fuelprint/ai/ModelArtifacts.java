package fuelprint.ai;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.store.StoredRecord;
import org.springframework.core.io.ClassPathResource;

/** Validates immutable packaged artifact bytes. Hash agreement does not establish ONNX compatibility. */
final class ModelArtifacts {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private ModelArtifacts() { }

    static ObjectNode inspect(StoredRecord version) {
        JsonNode file = version.body().path("file");
        if (!file.isObject() || !file.path("path").isTextual() || !file.path("sha256").isTextual())
            return unavailable("ARTIFACT_METADATA_MISSING", false);
        String path = file.path("path").textValue();
        // Only a packaged resource, never a workstation path, URL or traversal into other resources.
        if (!path.matches("models/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/[A-Za-z0-9_.-]+\\.onnx")
                && !path.matches("models/[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.onnx"))
            return unavailable("UNSAFE_ARTIFACT_PATH", false);
        if (path.contains("..")) return unavailable("UNSAFE_ARTIFACT_PATH", false);
        String expected = file.path("sha256").textValue();
        if (!expected.matches("[0-9a-f]{64}")) return unavailable("ARTIFACT_HASH_INVALID", false);
        var resource = new ClassPathResource(path);
        if (!resource.exists()) return unavailable("ARTIFACT_MISSING", false);
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) return unavailable("ARTIFACT_SIZE_INVALID", false);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(expected)) return unavailable("ARTIFACT_HASH_MISMATCH", false);
            // There is no trained artifact/feature ABI or ONNX runtime in this build. Never guess it.
            return unavailable("MODEL_RUNTIME_UNAVAILABLE", true);
        } catch (IOException e) {
            return unavailable("ARTIFACT_READ_FAILED", false);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ObjectNode unavailable(String reason, boolean verified) {
        return JsonNodeFactory.instance.objectNode().put("usable", false).put("reason", reason)
                .put("sha256_verified", verified);
    }
}
