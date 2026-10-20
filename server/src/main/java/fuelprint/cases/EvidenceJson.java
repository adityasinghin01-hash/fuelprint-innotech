package fuelprint.cases;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;

/**
 * Bounded reads and strict JSON byte checks shared by the evidence pack reader, the verifier and the configuration
 * loaders. Depth uses the same convention as the accepted writers ({@link CanonicalJson}, {@link JcsManifest}): the
 * root value is depth 0, each value inside a container is one deeper, and a value deeper than 64 is refused. The
 * check streams tokens, so no deep tree is built before refusal.
 */
final class EvidenceJson {
    static final int MAX_DEPTH = 64;
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH + 2).build()).build();

    private EvidenceJson() {}

    /** Bytes that are not acceptable JSON evidence; {@code code} is fixed. */
    static final class Bad extends RuntimeException {
        final String code;
        Bad(String code, String text) { super(text); this.code = code; }
    }

    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Bad("INVALID_UTF8", "The bytes are not valid UTF-8.");
        }
    }

    /** Strict UTF-8, one JSON value, no duplicate member names, no trailing content, depth at most 64. */
    static void check(byte[] bytes) {
        String text = utf8(bytes);
        try (JsonParser p = FACTORY.createParser(text)) {
            Deque<Set<String>> names = new ArrayDeque<>();
            int open = 0;
            JsonToken t = p.nextToken();
            if (t == null) throw new Bad("MALFORMED_JSON", "The bytes hold no JSON value.");
            while (t != null) {
                switch (t) {
                    case FIELD_NAME -> {
                        if (!names.peek().add(p.currentName())) throw new Bad("DUPLICATE_PROPERTY", "An object repeats a member name.");
                    }
                    case END_OBJECT -> { names.pop(); open--; }
                    case END_ARRAY -> open--;
                    default -> {
                        if (open > MAX_DEPTH) throw new Bad("JSON_TOO_DEEP", "JSON nesting is deeper than " + MAX_DEPTH + ".");
                        if (t == JsonToken.START_OBJECT) { open++; names.push(new HashSet<>()); }
                        else if (t == JsonToken.START_ARRAY) open++;
                    }
                }
                if (open == 0 && t != JsonToken.FIELD_NAME) {
                    if (p.nextToken() != null) throw new Bad("TRAILING_CONTENT", "There is content after the JSON value.");
                    return;
                }
                t = p.nextToken();
            }
            throw new Bad("MALFORMED_JSON", "The JSON value is incomplete.");
        } catch (Bad b) {
            throw b;
        } catch (com.fasterxml.jackson.core.exc.StreamConstraintsException e) {
            throw new Bad("JSON_TOO_DEEP", "JSON nesting is deeper than " + MAX_DEPTH + ".");
        } catch (IOException | RuntimeException e) {
            throw new Bad("MALFORMED_JSON", "The bytes are not well-formed JSON.");
        }
    }

    /** Reads at most {@code max} bytes; one more byte means the file is over the bound and nothing more is read. */
    static byte[] readBounded(Path file, int max) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] out = in.readNBytes(max + 1);
            if (out.length > max) throw new TooLarge();
            return out;
        }
    }

    static final class TooLarge extends IOException { TooLarge() { super("over the byte bound"); } }

    static boolean missing(IOException e) { return e instanceof NoSuchFileException; }
}
