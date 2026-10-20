package fuelprint.cases;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * External evidence format/signing configuration ({@code fp.cases.evidence-config}, default
 * {@code server/config/evidence.json}, never committed). Read at each seal/verify so a missing or changed key is
 * noticed when signing is attempted. A missing file means format 1, UNSIGNED, no trust anchors. Keys are never
 * generated here. Supported: format 1 UNSIGNED; format 2 UNSIGNED; format 2 SIGN_REQUIRED. Anything else is refused.
 */
@Component @Profile("!stub")
final class EvidenceConfig {
    static final int MAX_FILE_BYTES = 16 * 1024;
    static final Set<String> KEYS = Set.of("format", "signing_policy", "private_key_path", "public_key_path", "trust_anchors_path");
    private static final ObjectMapper STRICT = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final byte[] PROBE = "fp-evidence key pair check".getBytes(StandardCharsets.US_ASCII);

    /** Sealing settings, or a fixed refusal {@code code} with a key-free reason. */
    record Settings(int format, String policy, EvidenceVault.Signer signer, Path trustAnchors, String code, String reason) {
        boolean ok() { return code == null; }
        EvidenceVault.Options options() { return new EvidenceVault.Options(format, signer); }
        static Settings refused(String code, String reason) { return new Settings(1, null, null, null, code, reason); }
    }

    private final Path file;
    private final Path repoRoot;
    private final String fileProblem;

    EvidenceConfig(@Value("${fp.cases.evidence-config:server/config/evidence.json}") String configured) {
        Path start = Path.of("").toAbsolutePath();
        this.repoRoot = repoRoot(start);
        Path resolved = null;
        String problem = null;
        try {
            Path p = Path.of(configured);
            resolved = p.isAbsolute() ? p : repoRoot.resolve(p);
        } catch (RuntimeException invalid) {
            // Never echo the configured text: it may be a private path.
            problem = "EVIDENCE_CONFIG_INVALID";
        }
        this.file = resolved;
        this.fileProblem = problem;
    }

    /** Sealing settings: parses the configuration and, for SIGN_REQUIRED, loads and checks the signing pair. */
    Settings settings() {
        if (fileProblem != null) return Settings.refused(fileProblem, "The evidence configuration location is not a valid path.");
        return load(file, repoRoot);
    }

    /** Trust anchors for verification: the configured file, or none (with the reason when it is unusable). */
    record Trust(EvidenceTrust trust, String problem) {}

    /** Verification trust never reads or checks the private signing key; only the bounded configuration and trust file. */
    Trust trust() {
        if (fileProblem != null) return new Trust(EvidenceTrust.NONE, fileProblem);
        return trust(file);
    }

    static Trust trust(Path file) {
        try {
            Parsed c = parse(file);
            if (!c.ok()) return new Trust(EvidenceTrust.NONE, c.code());
            if (c.trustPath() == null) return new Trust(EvidenceTrust.NONE, null);
            Path anchors = path(c.trustPath(), "TRUST_ANCHORS_INVALID");
            return new Trust(EvidenceTrust.load(anchors, "SERVER_CONFIG"), null);
        } catch (EvidenceSigning.KeyProblem p) {
            return new Trust(EvidenceTrust.NONE, p.code);
        } catch (RuntimeException unexpected) {
            return new Trust(EvidenceTrust.NONE, "TRUST_ANCHORS_INVALID");
        }
    }

    static Path repoRoot(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent())
            if (Files.isRegularFile(dir.resolve("contracts").resolve("AGREEMENT.md"))) return dir;
        return start;
    }

    /** The bounded configuration text, checked for supported combinations. Reads no key and builds no path. */
    record Parsed(int format, String policy, String privatePath, String publicPath, String trustPath, String code, String reason) {
        boolean ok() { return code == null; }
        static Parsed refused(String code, String reason) { return new Parsed(1, null, null, null, null, code, reason); }
    }

    static Parsed parse(Path file) {
        if (!Files.exists(file)) return new Parsed(1, "UNSIGNED", null, null, null, null, null);
        JsonNode c;
        try {
            if (!Files.isRegularFile(file))
                return Parsed.refused("EVIDENCE_CONFIG_INVALID", "The evidence configuration is not a small regular file.");
            byte[] bytes = EvidenceJson.readBounded(file, MAX_FILE_BYTES);
            EvidenceJson.check(bytes);
            c = STRICT.readTree(EvidenceJson.utf8(bytes));
        } catch (Exception e) {
            return Parsed.refused("EVIDENCE_CONFIG_INVALID", "The evidence configuration is not valid JSON.");
        }
        if (c == null || !c.isObject()) return Parsed.refused("EVIDENCE_CONFIG_INVALID", "The evidence configuration must be a JSON object.");
        Set<String> names = new LinkedHashSet<>();
        c.fieldNames().forEachRemaining(names::add);
        if (!KEYS.containsAll(names)) return Parsed.refused("EVIDENCE_CONFIG_INVALID", "The evidence configuration has an unsupported key.");
        JsonNode f = c.path("format");
        int format = f.isMissingNode() ? 1 : (f.isIntegralNumber() && f.canConvertToInt() && (f.intValue() == 1 || f.intValue() == 2) ? f.intValue() : -1);
        if (format < 0) return Parsed.refused("EVIDENCE_CONFIG_INVALID", "format must be 1 or 2.");
        JsonNode pol = c.path("signing_policy");
        String policy = pol.isMissingNode() ? "UNSIGNED" : pol.isTextual() ? pol.textValue() : "";
        if (!Set.of("UNSIGNED", "SIGN_REQUIRED").contains(policy))
            return Parsed.refused("EVIDENCE_CONFIG_INVALID", "signing_policy must be UNSIGNED or SIGN_REQUIRED (SIGN_IF_CONFIGURED is not supported: it could silently fall back to unsigned).");
        if (format == 1 && !"UNSIGNED".equals(policy))
            return Parsed.refused("EVIDENCE_CONFIG_INVALID", "Format 1 has no signature; signing needs format 2.");
        String trust = null;
        if (c.has("trust_anchors_path")) {
            if (!c.path("trust_anchors_path").isTextual() || c.path("trust_anchors_path").textValue().isBlank())
                return Parsed.refused("EVIDENCE_CONFIG_INVALID", "trust_anchors_path must be a path.");
            trust = c.path("trust_anchors_path").textValue();
        }
        boolean keys = c.has("private_key_path") || c.has("public_key_path");
        if ("UNSIGNED".equals(policy)) {
            if (keys) return Parsed.refused("EVIDENCE_CONFIG_INVALID", "Key paths are only used with SIGN_REQUIRED.");
            return new Parsed(format, policy, null, null, trust, null, null);
        }
        if (!c.path("private_key_path").isTextual() || !c.path("public_key_path").isTextual())
            return Parsed.refused("SIGNING_KEY_MISSING", "SIGN_REQUIRED needs private_key_path and public_key_path.");
        return new Parsed(format, policy, c.path("private_key_path").textValue(), c.path("public_key_path").textValue(), trust, null, null);
    }

    /** A path from configuration text; invalid text (for example containing NUL) is a fixed, path-free refusal. */
    static Path path(String text, String code) {
        try {
            if (text == null || text.isBlank() || text.indexOf('\0') >= 0) throw new IllegalArgumentException();
            return Path.of(text);
        } catch (RuntimeException invalid) {
            throw new EvidenceSigning.KeyProblem(code, "A configured path is not a valid filesystem path.");
        }
    }

    static Settings load(Path file, Path repoRoot) {
        try {
            Parsed c = parse(file);
            if (!c.ok()) return Settings.refused(c.code(), c.reason());
            Path trust = c.trustPath() == null ? null : path(c.trustPath(), "EVIDENCE_CONFIG_INVALID");
            if ("UNSIGNED".equals(c.policy())) return new Settings(c.format(), c.policy(), null, trust, null, null);
            Path priv = outside(path(c.privatePath(), "SIGNING_KEY_INVALID"), repoRoot, "SIGNING_KEY");
            Path pub = outside(path(c.publicPath(), "PUBLIC_KEY_INVALID"), repoRoot, "PUBLIC_KEY");
            PrivateKey privateKey = EvidenceSigning.privateKey(EvidenceSigning.pem(priv, "PRIVATE KEY", "SIGNING_KEY"));
            byte[] spki = EvidenceSigning.pem(pub, "PUBLIC KEY", "PUBLIC_KEY");
            PublicKey publicKey;
            try { publicKey = EvidenceSigning.publicKey(spki); }
            catch (EvidenceSigning.KeyProblem p) { throw new EvidenceSigning.KeyProblem("PUBLIC_KEY_INVALID", "The configured public key is not a usable Ed25519 key."); }
            byte[] probe;
            try { probe = EvidenceSigning.sign(PROBE, privateKey); }
            catch (EvidenceSigning.KeyProblem p) { throw new EvidenceSigning.KeyProblem("SIGNING_KEY_INVALID", "The signing key cannot sign."); }
            if (!EvidenceSigning.verify(PROBE, probe, publicKey))
                throw new EvidenceSigning.KeyProblem("KEY_PAIR_MISMATCH", "The configured public key does not belong to the signing key.");
            String fingerprint = EvidenceTrust.fingerprint(publicKey);
            byte[] encoded = publicKey.getEncoded();
            EvidenceVault.Signer signer = new EvidenceVault.Signer() {
                public String fingerprint() { return fingerprint; }
                public byte[] publicKeySpki() { return encoded.clone(); }
                public byte[] sign(byte[] manifest) { return EvidenceSigning.sign(manifest, privateKey); }
                public String toString() { return "Ed25519 signer " + fingerprint; }
            };
            return new Settings(2, c.policy(), signer, trust, null, null);
        } catch (EvidenceSigning.KeyProblem p) {
            return Settings.refused(p.code, p.getMessage() + " Sealing is refused; it never falls back to unsigned.");
        } catch (RuntimeException unexpected) {
            return Settings.refused("EVIDENCE_CONFIG_INVALID", "The evidence configuration could not be used. Sealing is refused; it never falls back to unsigned.");
        }
    }

    private static Path outside(Path key, Path repoRoot, String prefix) {
        try {
            if (!Files.exists(key)) throw new EvidenceSigning.KeyProblem(prefix + "_MISSING", "A configured key file does not exist.");
            Path real = key.toRealPath(), root = repoRoot.toRealPath();
            if (real.startsWith(root)) throw new EvidenceSigning.KeyProblem("KEY_INSIDE_REPOSITORY", "Key files must be kept outside the repository.");
            return real;
        } catch (EvidenceSigning.KeyProblem p) {
            throw p;
        } catch (Exception e) {
            throw new EvidenceSigning.KeyProblem(prefix + "_UNREADABLE", "A configured key file cannot be read.");
        }
    }
}
