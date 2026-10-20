package fuelprint.cases;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/** Temporary test-only Ed25519 keys, written as PEM under a JUnit temporary folder outside the repository. Never real keys. */
final class TestKeys {
    final KeyPair pair;
    final Path privatePem, publicPem;

    private TestKeys(KeyPair pair, Path privatePem, Path publicPem) { this.pair = pair; this.privatePem = privatePem; this.publicPem = publicPem; }

    static KeyPair generate(String algorithm) {
        try { return KeyPairGenerator.getInstance(algorithm).generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static TestKeys write(Path dir, String name) { return write(dir, name, generate("Ed25519")); }

    static TestKeys write(Path dir, String name, KeyPair pair) {
        try {
            Files.createDirectories(dir);
            Path priv = dir.resolve(name + ".pk8.pem"), pub = dir.resolve(name + ".spki.pem");
            Files.writeString(priv, EvidenceSigning.pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(pub, EvidenceSigning.pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            return new TestKeys(pair, priv, pub);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    String fingerprint() { return EvidenceTrust.fingerprint(pair.getPublic()); }

    /** The private key's base64 body, used only to prove it never appears in outputs. */
    String privateBase64() { return java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()); }

    EvidenceVault.Signer signer() {
        return new EvidenceVault.Signer() {
            public String fingerprint() { return TestKeys.this.fingerprint(); }
            public byte[] publicKeySpki() { return pair.getPublic().getEncoded(); }
            public byte[] sign(byte[] manifest) { return EvidenceSigning.sign(manifest, pair.getPrivate()); }
        };
    }

    static Path trustFile(Path dir, String... fingerprints) {
        StringBuilder b = new StringBuilder("{\"schema\":\"fp.evidence-trust/1\",\"trusted\":[");
        for (int i = 0; i < fingerprints.length; i++) {
            if (i > 0) b.append(',');
            b.append("{\"key_fingerprint_sha256\":\"").append(fingerprints[i]).append("\",\"label\":\"test anchor ").append(i).append("\"}");
        }
        b.append("]}");
        try { Path p = dir.resolve("trust-" + System.nanoTime() + ".json"); Files.createDirectories(dir); Files.writeString(p, b); return p; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
