package fuelprint.cases;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.StoredRecord;

/**
 * The downloadable evidence pack: a ZIP with {@code manifest.json}, optional {@code signature.json} and every payload
 * member at its manifest path, holding exactly the stored bytes. Writing prepares a complete private temporary file
 * before anything is sent. Reading never trusts header sizes, never extracts to the filesystem and refuses
 * ambiguous, malformed, oversized or undeclared content with a fixed code.
 */
final class EvidenceArchive {
    static final String MANIFEST = EvidenceVault.MANIFEST_PATH;
    static final String SIGNATURE = EvidenceVault.SIGNATURE_PATH;
    static final long ZIP_TIME = 315532800000L; // 1980-01-01, the earliest ZIP time: packs carry no wall time of their own

    /** Bounds; defaults are the reviewed protocol values. Tests may pass smaller ones. */
    record Limits(long compressedBytes, int payloadEntries, int metadataEntries, int memberBytes, int manifestBytes,
                  int signatureBytes, long expandedBytes, int nameLength) {
        static final Limits DEFAULT = new Limits(64L << 20, EvidenceVault.MAX_MEMBERS, 2, EvidenceVault.MAX_MEMBER_BYTES,
                JcsManifest.MAX_MANIFEST_BYTES, 16 * 1024, 64L << 20, 200);
    }

    /** The archive cannot be written or read; {@code code} is fixed and the message never holds secrets or local paths. */
    static final class Refusal extends RuntimeException {
        final String code;
        Refusal(String code, String text) { super(text); this.code = code; }
    }

    /** What a safely read pack holds: exact bytes, in archive order. */
    record Pack(byte[] manifest, byte[] signature, Map<String, byte[]> members, long expandedBytes) {}

    private EvidenceArchive() {}

    // ------------------------------------------------------------------ writing

    /**
     * Builds the complete ZIP for one export in a new owner-only temporary file in {@code tempDir} and returns it.
     * Every source blob is checked against the export record's locator (length and SHA-256) first; a tamper copy is
     * written as it is, so its deliberate mismatch with the manifest stays visible. On any failure the file is
     * deleted and a coded refusal is thrown.
     */
    static Path prepare(StoredRecord export, BlobStore blobs, Path tempDir) { return prepare(export, blobs, tempDir, Limits.DEFAULT); }

    static Path prepare(StoredRecord export, BlobStore blobs, Path tempDir, Limits limits) {
        JsonNode e = export.body();
        List<String[]> plan = new ArrayList<>(); // {name, sha, length, what}
        plan.add(locator(e.path("manifest"), MANIFEST, "the manifest"));
        if (e.path("signature").isObject()) plan.add(locator(e.path("signature"), SIGNATURE, "the signature envelope"));
        if (!e.path("members").isArray() || e.path("members").isEmpty()) throw new Refusal(EvidenceVault.MISSING, "The export lists no members.");
        if (e.path("members").size() > limits.payloadEntries()) throw new Refusal("EVIDENCE_TOO_LARGE", "The export has too many members.");
        Set<String> seen = new HashSet<>();
        for (JsonNode m : e.path("members")) {
            String path = m.path("path").isTextual() ? m.path("path").textValue() : null;
            if (!EvidenceVault.safePath(path) || !seen.add(path.toLowerCase(Locale.ROOT)))
                throw new Refusal(EvidenceVault.UNSAFE, "The export names an unsafe or repeated member path.");
            plan.add(locator(m, path, "a member"));
        }
        Path file = null;
        try {
            file = temp(tempDir);
            long expanded = 0;
            try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
                zip.setLevel(9);
                for (String[] p : plan) {
                    int max = MANIFEST.equals(p[0]) ? limits.manifestBytes() : SIGNATURE.equals(p[0]) ? limits.signatureBytes() : limits.memberBytes();
                    long declared = Long.parseLong(p[2]);
                    // The locator's length is checked against the bound before anything is read.
                    if (declared > max) throw new Refusal("EVIDENCE_TOO_LARGE", "An entry is larger than the pack limit.");
                    if (expanded + declared > limits.expandedBytes()) throw new Refusal("EVIDENCE_TOO_LARGE", "The pack would expand beyond its limit.");
                    byte[] bytes = boundedBlob(blobs, p[1], max, p[3]);
                    if (bytes.length != declared || !EvidenceSigning.sha256(bytes).equals(p[1]))
                        throw new Refusal(EvidenceVault.STORAGE, "Stored bytes for " + p[3] + " do not match the export record.");
                    expanded += bytes.length;
                    if (expanded > limits.expandedBytes()) throw new Refusal("EVIDENCE_TOO_LARGE", "The pack would expand beyond its limit.");
                    ZipEntry entry = new ZipEntry(p[0]);
                    entry.setTime(ZIP_TIME);
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                    zip.closeEntry();
                }
                zip.finish();
            }
            if (Files.size(file) > limits.compressedBytes()) throw new Refusal("EVIDENCE_TOO_LARGE", "The pack is larger than its compressed limit.");
            return file;
        } catch (Refusal r) {
            delete(file);
            throw r;
        } catch (IOException | RuntimeException x) {
            delete(file);
            throw new Refusal(EvidenceVault.STORAGE, "The pack could not be prepared.");
        }
    }

    /**
     * Reads one stored blob through its public location with at most {@code max} bytes plus a one-byte overflow probe,
     * so an oversized or damaged blob is never loaded unboundedly. Hash and length are checked by the caller.
     */
    static byte[] boundedBlob(BlobStore blobs, String sha, int max, String what) {
        Path file;
        try { file = blobs.pathOf(sha); }
        catch (RuntimeException e) { throw new Refusal(EvidenceVault.MISSING, "Stored bytes for " + what + " are missing."); }
        try {
            if (!Files.isRegularFile(file)) throw new Refusal(EvidenceVault.MISSING, "Stored bytes for " + what + " are missing.");
            return EvidenceJson.readBounded(file, max);
        } catch (EvidenceJson.TooLarge e) {
            throw new Refusal(EvidenceVault.STORAGE, "Stored bytes for " + what + " are larger than the export record allows.");
        } catch (IOException e) {
            throw new Refusal(EvidenceJson.missing(e) ? EvidenceVault.MISSING : EvidenceVault.STORAGE, "Stored bytes for " + what + " are missing or unreadable.");
        }
    }

    private static String[] locator(JsonNode loc, String name, String what) {
        String sha = loc.path("sha256").isTextual() ? loc.path("sha256").textValue() : null;
        if (sha == null || !EvidenceVault.SHA256.matcher(sha).matches() || !loc.path("length").isIntegralNumber()
                || !loc.path("length").canConvertToLong() || loc.path("length").longValue() < 0)
            throw new Refusal(EvidenceVault.MISSING, "The export record's locator for " + what + " is malformed.");
        return new String[]{name, sha, Long.toString(loc.path("length").longValue()), what};
    }

    private static Path temp(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
            return Files.createTempFile(dir, "fp-pack-", ".zip", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.createTempFile(dir, "fp-pack-", ".zip");
    }

    static void delete(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); } catch (IOException ignored) { /* best effort; the folder is private temp */ }
    }

    // ------------------------------------------------------------------ reading

    /** True for the two metadata names or a payload path in the evidence member grammar (ASCII, lower case, no traversal). */
    static boolean allowedName(String name, Limits limits) {
        if (name == null || name.isEmpty() || name.length() > limits.nameLength()) return false;
        return MANIFEST.equals(name) || SIGNATURE.equals(name) || EvidenceVault.safePath(name);
    }

    /**
     * Reads a pack file with every bound enforced while reading. The central directory and the local entry stream
     * must list the same names in the same order; anything else (prefix data, hidden entries, duplicates) is refused.
     */
    static Pack read(Path zipFile, Limits limits) {
        long size;
        try { size = Files.size(zipFile); }
        catch (IOException e) { throw new Refusal("PACK_UNREADABLE", "The pack file cannot be read."); }
        if (size > limits.compressedBytes()) throw new Refusal("PACK_TOO_LARGE", "The pack file is larger than " + limits.compressedBytes() + " bytes.");
        List<String> central = new ArrayList<>();
        Map<String, ZipEntry> centralEntries = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                central.add(entry.getName());
                centralEntries.put(entry.getName(), new ZipEntry(entry));
                if (central.size() > limits.payloadEntries() + limits.metadataEntries())
                    throw new Refusal("TOO_MANY_ENTRIES", "The pack has more entries than allowed.");
            }
        } catch (Refusal r) {
            throw r;
        } catch (IOException | RuntimeException e) {
            throw new Refusal("MALFORMED_ARCHIVE", "The pack is not a complete, readable ZIP archive.");
        }
        checkNames(central, limits);
        List<String> local = new ArrayList<>();
        Map<String, byte[]> members = new LinkedHashMap<>();
        byte[] manifest = null, signature = null;
        long expanded = 0;
        try (InputStream in = Files.newInputStream(zipFile); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                local.add(name);
                if (local.size() > central.size() || !name.equals(central.get(local.size() - 1)))
                    throw new Refusal("ENTRY_LIST_MISMATCH", "The pack's local entries differ from its directory.");
                if (entry.isDirectory()) throw new Refusal("DIRECTORY_ENTRY", "The pack contains a directory entry.");
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)
                    throw new Refusal("UNSUPPORTED_ENTRY", "The pack uses an unsupported compression method.");
                int max = MANIFEST.equals(name) ? limits.manifestBytes() : SIGNATURE.equals(name) ? limits.signatureBytes() : limits.memberBytes();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(max, 64 * 1024));
                long count = 0;
                int n;
                while ((n = zin.read(buffer)) > 0) {
                    count += n;
                    expanded += n;
                    if (count > max) throw new Refusal("ENTRY_TOO_LARGE", "An entry expands beyond its limit.");
                    if (expanded > limits.expandedBytes()) throw new Refusal("EXPANDED_TOO_LARGE", "The pack expands beyond its total limit.");
                    out.write(buffer, 0, n);
                }
                ZipEntry declared = centralEntries.get(name);
                if ((entry.getSize() >= 0 && entry.getSize() != count) || declared == null || declared.getSize() != count)
                    throw new Refusal("SIZE_MISMATCH", "An entry's recorded size differs from its bytes.");
                // ZipInputStream validates the local entry/data descriptor. Bind the directory metadata to
                // that same verified entry so two ZIP consumers cannot interpret different byte streams.
                if (declared.getMethod() != entry.getMethod() || declared.getCrc() != entry.getCrc()
                        || declared.getCompressedSize() != entry.getCompressedSize())
                    throw new Refusal("ENTRY_METADATA_MISMATCH", "An entry's directory metadata differs from its local entry.");
                byte[] bytes = out.toByteArray();
                // Resource bound: no entry may nest JSON deeper than 64 (checked by streaming, before any tree is built).
                // Other JSON defects (invalid UTF-8, duplicate names, trailing content, malformed text) are refused by
                // the shared verifier as MEMBER_UNPARSEABLE / MANIFEST_UNPARSEABLE, so a tampered member is still named.
                try { EvidenceJson.check(bytes); }
                catch (EvidenceJson.Bad bad) {
                    if ("JSON_TOO_DEEP".equals(bad.code)) throw new Refusal(bad.code, "Entry " + name + " nests JSON deeper than " + EvidenceJson.MAX_DEPTH + ".");
                }
                if (MANIFEST.equals(name)) manifest = bytes;
                else if (SIGNATURE.equals(name)) signature = bytes;
                else members.put(name, bytes);
            }
        } catch (Refusal r) {
            throw r;
        } catch (java.util.zip.ZipException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            throw new Refusal(m.contains("encrypt") ? "ENCRYPTED_ENTRY" : "MALFORMED_ARCHIVE", m.contains("encrypt")
                    ? "The pack contains an encrypted entry." : "The pack is damaged or truncated.");
        } catch (IOException | RuntimeException e) {
            throw new Refusal("MALFORMED_ARCHIVE", "The pack is damaged or truncated.");
        }
        if (!local.equals(central)) throw new Refusal("ENTRY_LIST_MISMATCH", "The pack's local entries differ from its directory.");
        if (manifest == null) throw new Refusal("MANIFEST_MISSING", "The pack has no manifest.json.");
        if (members.size() > limits.payloadEntries()) throw new Refusal("TOO_MANY_ENTRIES", "The pack has too many payload entries.");
        return new Pack(manifest, signature, Collections.unmodifiableMap(members), expanded);
    }

    private static void checkNames(List<String> names, Limits limits) {
        Set<String> exact = new HashSet<>(), folded = new HashSet<>();
        int metadata = 0, payload = 0;
        for (String name : names) {
            if (!allowedName(name, limits)) throw new Refusal("UNSAFE_NAME", "The pack contains an entry whose name is not allowed.");
            if (!exact.add(name)) throw new Refusal("DUPLICATE_ENTRY", "The pack repeats an entry name.");
            if (!folded.add(name.toLowerCase(Locale.ROOT))) throw new Refusal("DUPLICATE_ENTRY", "The pack has entry names that differ only by case.");
            if (MANIFEST.equals(name) || SIGNATURE.equals(name)) metadata++; else payload++;
        }
        if (metadata > limits.metadataEntries() || payload > limits.payloadEntries())
            throw new Refusal("TOO_MANY_ENTRIES", "The pack has more entries than allowed.");
    }
}
