package fuelprint.cases;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ed25519 over exact bytes (JDK 17 built-in provider), plus bounded PEM key reading. Key bytes never appear in
 * any message: every failure is a fixed {@link KeyProblem} code.
 */
final class EvidenceSigning {
    static final int MAX_KEY_FILE_BYTES = 4096;
    static final int SIGNATURE_BYTES = 64;
    /** DER of the Ed25519 algorithm identifier OID 1.3.101.112. */
    private static final byte[] ED25519_OID = {0x06, 0x03, 0x2b, 0x65, 0x70};

    private EvidenceSigning() {}

    /** A key that cannot be used; {@code code} is a fixed refusal code, the message never holds key material. */
    static final class KeyProblem extends RuntimeException {
        final String code;
        KeyProblem(String code, String text) { super(text); this.code = code; }
    }

    static byte[] sign(byte[] message, PrivateKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new KeyProblem("SIGNING_FAILED", "The manifest could not be signed.");
        }
    }

    /** True only for a valid Ed25519 signature of exactly these bytes; any malformed input is simply false. */
    static boolean verify(byte[] message, byte[] signature, PublicKey key) {
        if (message == null || signature == null || key == null || signature.length != SIGNATURE_BYTES) return false;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static boolean isEd25519(byte[] der) {
        if (der == null) return false;
        outer:
        for (int i = 0; i + ED25519_OID.length <= Math.min(der.length, 32); i++) {
            for (int j = 0; j < ED25519_OID.length; j++) if (der[i + j] != ED25519_OID[j]) continue outer;
            return true;
        }
        return false;
    }

    static PublicKey publicKey(byte[] spki) {
        if (spki == null || !isEd25519(spki)) throw new KeyProblem("UNSUPPORTED_ALGORITHM", "The public key is not an Ed25519 key.");
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)); }
        catch (GeneralSecurityException | RuntimeException e) { throw new KeyProblem("KEY_INVALID", "The public key cannot be read."); }
    }

    static PrivateKey privateKey(byte[] pkcs8) {
        if (!isEd25519(pkcs8)) throw new KeyProblem("SIGNING_KEY_UNSUPPORTED_ALGORITHM", "The signing key is not an Ed25519 key.");
        try { return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)); }
        catch (GeneralSecurityException | RuntimeException e) { throw new KeyProblem("SIGNING_KEY_INVALID", "The signing key cannot be read."); }
    }

    /** Reads one bounded PEM block of the given label. {@code prefix} names the configured key in refusal codes. */
    static byte[] pem(Path file, String label, String prefix) {
        try {
            if (!Files.exists(file)) throw new KeyProblem(prefix + "_MISSING", "The configured " + prefix.toLowerCase() + " file does not exist.");
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new KeyProblem(prefix + "_UNREADABLE", "The configured " + prefix.toLowerCase() + " file is not a readable file.");
            byte[] bytes;
            try { bytes = EvidenceJson.readBounded(file, MAX_KEY_FILE_BYTES); }
            catch (EvidenceJson.TooLarge t) { throw new KeyProblem(prefix + "_INVALID", "The configured " + prefix.toLowerCase() + " file is too large."); }
            for (byte b : bytes) if (b < 0) throw new KeyProblem(prefix + "_INVALID", "The configured " + prefix.toLowerCase() + " file is not ASCII PEM.");
            String text = new String(bytes, StandardCharsets.US_ASCII).strip();
            String begin = "-----BEGIN " + label + "-----", end = "-----END " + label + "-----";
            if (!text.startsWith(begin) || !text.endsWith(end) || text.indexOf(begin, 1) >= 0)
                throw new KeyProblem(prefix + "_INVALID", "The configured " + prefix.toLowerCase() + " file is not a single " + label + " PEM block.");
            String body = text.substring(begin.length(), text.length() - end.length()).replaceAll("\\s", "");
            return Base64.getDecoder().decode(body);
        } catch (KeyProblem p) {
            throw p;
        } catch (IOException | RuntimeException e) {
            throw new KeyProblem(prefix + "_UNREADABLE", "The configured " + prefix.toLowerCase() + " file cannot be read.");
        }
    }

    static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
