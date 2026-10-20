package fuelprint.ai;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.core.Ids;
import fuelprint.core.store.StoredRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Artifact-envelope validation only; the fixture bytes are explicitly not a trained ONNX model. */
class ModelArtifactsTest {
    JsonNode inspect(ObjectNode body)throws Exception {
        return ModelArtifacts.inspect(new StoredRecord("model_version",Ids.newId(),1,null,"2026-10-19T00:00:00Z","fixture",body));
    }
    ObjectNode model(String path,String hash){var n=JsonNodeFactory.instance.objectNode();n.putObject("file").put("path",path).put("sha256",hash);return n;}
    @ParameterizedTest @ValueSource(strings={"/etc/passwd","../models/test.onnx","models/../../secret.onnx","file:///tmp/model.onnx","https://example.com/model.onnx","models/thing\\file.onnx","models/%2e%2e/file.onnx","science/rank/cutoff.json"})
    void arbitraryFilesystemOrNetworkPathsAreNeverLoaded(String path)throws Exception {
        var result=inspect(model(path,"0".repeat(64)));
        assertThat(result.path("reason").asText()).isEqualTo("UNSAFE_ARTIFACT_PATH");
        assertThat(result.path("usable").asBoolean()).isFalse();
    }
    @Test void absentMetadataIsExplicitlyUnavailable()throws Exception {assertThat(inspect(JsonNodeFactory.instance.objectNode()).path("reason").asText()).isEqualTo("ARTIFACT_METADATA_MISSING");}
    @Test void missingPackagedArtifactIsNotAnActiveInference()throws Exception {assertThat(inspect(model("models/absent.onnx","0".repeat(64))).path("reason").asText()).isEqualTo("ARTIFACT_MISSING");}
    @Test void malformedHashIsRefused()throws Exception {assertThat(inspect(model("models/ai-test-not-a-model.onnx","not-a-hash")).path("reason").asText()).isEqualTo("ARTIFACT_HASH_INVALID");}
    @Test void exactBytesAreComparedAgainstExpectedHash()throws Exception {assertThat(inspect(model("models/ai-test-not-a-model.onnx","0".repeat(64))).path("reason").asText()).isEqualTo("ARTIFACT_HASH_MISMATCH");}
    @Test void matchingBytesStillDoNotPretendRuntimeOrModelQualification()throws Exception {
        // SHA-256 of the literal fixture bytes, independently calculated and stored below.
        var result=inspect(model("models/ai-test-not-a-model.onnx","e6314d137729e03822c8132235b930b1c2326a43cc790dffe79b0165f357b981"));
        assertThat(result.path("usable").asBoolean()).isFalse();
        assertThat(result.path("reason").asText()).isEqualTo("MODEL_RUNTIME_UNAVAILABLE");
        assertThat(result.path("sha256_verified").asBoolean()).isTrue();
    }
}
