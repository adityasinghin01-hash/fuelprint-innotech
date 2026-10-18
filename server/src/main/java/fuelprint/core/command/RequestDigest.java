package fuelprint.core.command;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.core.Ids;

/**
 * Compares a resent command with the stored one without keeping private values.
 *
 * <p>The digest covers {@code command_type}, {@code run_id}, {@code expected_revision} and the whole
 * payload, in canonical JSON (keys sorted). Without private fields it is a plain SHA-256. With private
 * fields (a phone number) it is an HMAC with a random key that lives only in memory, so the stored digest
 * cannot be turned back into the number; after a restart such a command cannot be matched any more and a
 * resend is refused as INVALID (the sender makes a new command).
 */
final class RequestDigest {

    static final String PRIVATE_MARK = "(private)";

    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private final byte[] key;
    private final String keyId;

    RequestDigest() {
        key = new byte[32];
        new SecureRandom().nextBytes(key);
        keyId = Ids.newId().substring(0, 8);
    }

    /** The digest to store and the payload to store (private values replaced by {@value #PRIVATE_MARK}). */
    record Result(String digest, ObjectNode storedPayload) {
    }

    /**
     * @param allPrivate treat every payload field as private (used for unknown command types)
     */
    Result of(Command command, Set<String> privateFields, boolean allPrivate) {
        ObjectNode payload = command.payload();
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("command_type", command.commandType());
        request.put("run_id", command.runId());
        if (command.expectedRevision() == null) {
            request.putNull("expected_revision");
        } else {
            request.put("expected_revision", command.expectedRevision());
        }
        request.set("payload", payload);
        String canonical = canonical(request);

        ObjectNode stored = payload.deepCopy();
        boolean hasPrivate = false;
        List<String> names = new ArrayList<>();
        stored.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (allPrivate || privateFields.contains(name)) {
                stored.put(name, PRIVATE_MARK);
                hasPrivate = true;
            }
        }
        String digest = hasPrivate ? "hmac:" + keyId + ":" + hmac(canonical) : "sha256:" + sha256(canonical);
        return new Result(digest, stored);
    }

    /** True if {@code storedDigest} was keyed by an earlier server start, so it cannot be compared now. */
    boolean fromEarlierStart(String storedDigest) {
        return storedDigest.startsWith("hmac:") && !storedDigest.startsWith("hmac:" + keyId + ":");
    }

    /** Canonical JSON: object keys sorted at every level, no extra spaces. */
    static String canonical(JsonNode node) {
        try {
            return CANONICAL.writeValueAsString(sorted(node));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write canonical JSON", e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            it.forEachRemaining(fields::add);
            fields.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, JsonNode> field : fields) {
                out.set(field.getKey(), sorted(field.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(sorted(item)));
            return out;
        }
        return node;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String hmac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
