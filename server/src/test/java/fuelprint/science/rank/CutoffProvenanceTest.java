package fuelprint.science.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class CutoffProvenanceTest {
    private ObjectNode artifact() {
        ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("algorithm", "FP-COMPAT-3/4"); n.put("world", "FP-WORLD-3");
        n.put("response", ResponseModel.TEACHING.label());
        n.put("method", "FullChainCalibrationRunner FP-COMPAT-3/4"); n.put("seeds", "independent");
        n.put("episodes", 40); n.put("rank", 39); n.put("cutoff", 39);
        // Independently calculated SHA256 of big-endian binary64 values1..40.
        n.put("statistics_sha256", "d3750d103754ef3429668700c852da484e61df1821fa0a9024a14bb9a9d5fb25");
        var episodes = n.putArray("episode_statistics");
        for (int i=1; i<=40; i++) episodes.addObject().put("statistic", i);
        return n;
    }

    private Cutoff parse(ObjectNode n) {
        try {
            return (Cutoff) Cutoff.class.getDeclaredMethod("fromJson", com.fasterxml.jackson.databind.JsonNode.class).invoke(null, n);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Runtime cutoff needs complete provenance validation", e); }
    }

    @Test void exactIdentityAndManifestAreRequiredBeforeRuntimeUse() {
        assertThat(parse(artifact()).value()).isEqualTo(39);
        for (String field : new String[]{"algorithm", "world", "response"}) {
            ObjectNode wrong = artifact(); wrong.put(field, wrong.path(field).asText()+"0");
            assertThatThrownBy(() -> parse(wrong)).isInstanceOf(IllegalStateException.class);
            ObjectNode missing = artifact(); missing.remove(field);
            assertThatThrownBy(() -> parse(missing)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void copiedMetadataCannotQualifyChangedStatisticsOrCutoff() {
        ObjectNode changed = artifact(); ((ObjectNode)changed.path("episode_statistics").get(0)).put("statistic", 0);
        assertThatThrownBy(() -> parse(changed)).isInstanceOf(IllegalStateException.class);
        for (String field : new String[]{"cutoff", "rank", "episodes"}) {
            ObjectNode wrong = artifact(); wrong.put(field, 38);
            assertThatThrownBy(() -> parse(wrong)).isInstanceOf(IllegalStateException.class);
        }
        ObjectNode missing = artifact(); missing.remove("episode_statistics");
        assertThatThrownBy(() -> parse(missing)).isInstanceOf(IllegalStateException.class);
    }
}
