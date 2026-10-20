package fuelprint.cases;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Externally supplied trusted signer fingerprints. A key embedded in an evidence pack only supplies verification
 * material: it is trusted only if its fingerprint is listed here. Nothing here accepts a real key by itself.
 */
final class EvidenceTrust {
    static final String SCHEMA = "fp.evidence-trust/1";
    static final int MAX_FILE_BYTES = 16 * 1024;
    static final int MAX_ANCHORS = 64;
    static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    static final EvidenceTrust NONE = new EvidenceTrust(Set.of(), "NONE");
    private static final ObjectMapper STRICT = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final Set<String> trusted;
    final String source;

    private EvidenceTrust(Set<String> trusted, String source) { this.trusted = Set.copyOf(trusted); this.source = source; }

    /** Lower-case hex SHA-256 of the key's DER SubjectPublicKeyInfo encoding. */
    static String fingerprint(PublicKey key) { return EvidenceSigning.sha256(key.getEncoded()); }

    static EvidenceTrust of(Collection<String> fingerprints, String source) {
        if (fingerprints.size() > MAX_ANCHORS) throw new EvidenceSigning.KeyProblem("TRUST_ANCHORS_INVALID", "Too many trusted fingerprints.");
        Set<String> set = new LinkedHashSet<>();
        for (String f : fingerprints) {
            if (f == null || !FINGERPRINT.matcher(f).matches())
                throw new EvidenceSigning.KeyProblem("TRUST_ANCHORS_INVALID", "A trusted fingerprint must be 64 lower-case hex characters.");
            set.add(f);
        }
        return new EvidenceTrust(set, source);
    }

    static EvidenceTrust load(Path file, String source) {
        JsonNode root;
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file))
                throw new EvidenceSigning.KeyProblem("TRUST_ANCHORS_MISSING", "The trust anchors file is missing or unreadable.");
            // One bounded read: the validated bytes are the only bytes used.
            byte[] bytes;
            try { bytes = EvidenceJson.readBounded(file, MAX_FILE_BYTES); }
            catch (EvidenceJson.TooLarge t) { throw new EvidenceSigning.KeyProblem("TRUST_ANCHORS_INVALID", "The trust anchors file is too large."); }
            EvidenceJson.check(bytes);
            root = STRICT.readTree(EvidenceJson.utf8(bytes));
        } catch (EvidenceSigning.KeyProblem p) {
            throw p;
        } catch (Exception e) {
            throw new EvidenceSigning.KeyProblem("TRUST_ANCHORS_INVALID", "The trust anchors file is not valid JSON.");
        }
        Set<String> names = new LinkedHashSet<>();
        if (root != null && root.isObject()) root.fieldNames().forEachRemaining(names::add);
        if (!names.equals(Set.of("schema", "trusted")) || !SCHEMA.equals(root.path("schema").asText(null)) || !root.path("trusted").isArray())
            throw new EvidenceSigning.KeyProblem("TRUST_ANCHORS_INVALID", "The trust anchors file must be {schema:" + SCHEMA + ", trusted:[...]}.");
        java.util.List<String> fps = new java.util.ArrayList<>();
        for (JsonNode a : root.path("trusted")) {
            Set<String> keys = new LinkedHashSet<>();
            if (a.isObject()) a.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(Set.of("key_fingerprint_sha256", "label")) || !a.path("label").isTextual() || a.path("label").textValue().length() > 128
                    || !a.path("key_fingerprint_sha256").isTextual())
                throw new EvidenceSigning.KeyProblem("TRUST_ANCHORS_INVALID", "Each trusted entry needs exactly key_fingerprint_sha256 and label.");
            fps.add(a.path("key_fingerprint_sha256").textValue());
        }
        return of(fps, source);
    }

    boolean trusts(String fingerprint) { return fingerprint != null && trusted.contains(fingerprint); }
    /** The validated fingerprints, as an immutable copy. */
    Set<String> fingerprints() { return trusted; }
    int size() { return trusted.size(); }
}
