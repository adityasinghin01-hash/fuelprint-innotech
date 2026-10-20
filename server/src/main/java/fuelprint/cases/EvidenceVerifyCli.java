package fuelprint.cases;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Standalone offline evidence-pack verifier (Java 17, no server, database, network or environment keys).
 *
 * <pre>
 * java -cp fuelprint-server.jar -Dloader.main=fuelprint.cases.EvidenceVerifyCli \
 *      org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --pack PACK.zip [--trust-sha256 HEX]... [--trust-file TRUST.json] [--allow-unsigned]
 * </pre>
 *
 * Prints one bounded JSON object on standard output. Exit codes, highest priority first when several apply:
 * 2 usage, 3 archive refused, 4 unsupported version, 5 integrity FAIL, 6 lineage FAIL, 7 signature invalid or
 * unverified, 8 untrusted signer (or unsigned without --allow-unsigned), 0 PASS; 1 is an internal error.
 * {@code --allow-unsigned} only permits an integrity-only pass for a pack that declares no signing; it never
 * excuses a missing, invalid or untrusted signature.
 */
public final class EvidenceVerifyCli {
    private EvidenceVerifyCli() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ObjectMapper json = new ObjectMapper();
        try {
            Path pack = null;
            List<String> fingerprints = new ArrayList<>();
            Path trustFile = null;
            boolean allowUnsigned = false;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--pack" -> { if (pack != null || i + 1 >= args.length) return usage(json, out, err, "--pack needs exactly one path");
                        pack = path(args[++i]); if (pack == null) return usage(json, out, err, "--pack is not a valid path"); }
                    case "--trust-sha256" -> { if (i + 1 >= args.length) return usage(json, out, err, "--trust-sha256 needs a value"); fingerprints.add(args[++i]); }
                    case "--trust-file" -> { if (trustFile != null || i + 1 >= args.length) return usage(json, out, err, "--trust-file needs exactly one path");
                        trustFile = path(args[++i]); if (trustFile == null) return usage(json, out, err, "--trust-file is not a valid path"); }
                    case "--allow-unsigned" -> allowUnsigned = true;
                    default -> { return usage(json, out, err, "unknown argument"); }
                }
            }
            if (pack == null) return usage(json, out, err, "--pack is required");
            if (!Files.isRegularFile(pack) || !Files.isReadable(pack)) return usage(json, out, err, "the pack file is not a readable file");
            EvidenceTrust trust;
            try {
                List<String> all = new ArrayList<>(fingerprints);
                // The trust file is validated strictly first; only then are its fingerprints merged with the arguments.
                // One bounded strict read; the fingerprints actually validated are the ones used (never re-read).
                if (trustFile != null) all.addAll(EvidenceTrust.load(trustFile, "CLI_ARGUMENT").fingerprints());
                if (all.size() > EvidenceTrust.MAX_ANCHORS) return usage(json, out, err, "too many trusted fingerprints");
                trust = all.isEmpty() ? EvidenceTrust.NONE : EvidenceTrust.of(all, "CLI_ARGUMENT");
            } catch (EvidenceSigning.KeyProblem p) {
                return usage(json, out, err, "trusted fingerprints are invalid (" + p.code + ")");
            }
            EvidenceOffline.Result r = EvidenceOffline.verify(pack, trust, allowUnsigned);
            out.println(json.writeValueAsString(r.json()));
            return r.exitCode();
        } catch (Throwable t) {
            try {
                ObjectNode o = json.createObjectNode().put("verifier", EvidenceVerifier.VERSION).put("result", "ERROR").put("exit_code", 1)
                        .put("reason", "Internal verifier error (" + t.getClass().getSimpleName() + ").");
                out.println(json.writeValueAsString(o));
            } catch (Exception ignored) { /* nothing more can be reported */ }
            return 1;
        }
    }

    /** Path text, or null when it cannot be a path (for example it contains NUL); the text is never echoed. */
    private static Path path(String text) {
        try { return text == null || text.isEmpty() || text.indexOf('\0') >= 0 ? null : Path.of(text); }
        catch (RuntimeException invalid) { return null; }
    }

    private static int usage(ObjectMapper json, PrintStream out, PrintStream err, String problem) {
        err.println("usage: EvidenceVerifyCli --pack PACK.zip [--trust-sha256 HEX]... [--trust-file TRUST.json] [--allow-unsigned]");
        err.println("problem: " + problem);
        try {
            out.println(json.writeValueAsString(json.createObjectNode().put("verifier", EvidenceVerifier.VERSION).put("result", "USAGE_ERROR")
                    .put("exit_code", 2).put("reason", problem)));
        } catch (Exception ignored) { /* still exit 2 */ }
        return 2;
    }
}
