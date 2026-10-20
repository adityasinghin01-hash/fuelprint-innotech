package fuelprint.core.command;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.SessionService;
import fuelprint.core.store.RecordStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/commands}: one command in, one receipt out (AGREEMENT section 3).
 *
 * <p>The sender comes only from the session ({@link SessionService#resolve}, the {@code fp_session}
 * cookie; P3 provides the bean), never from the body or a header. With no {@code SessionService} bean every
 * request is anonymous.
 * Fields like {@code actor} or {@code automatic} in the body are ignored, and no request can ever become
 * the {@code automation} actor.
 *
 * <p>HTTP status: 200 for every decided receipt (APPLIED or REJECTED), 400 when the body is not a readable
 * command, 500 when the server failed (receipt reason {@code SERVER_ERROR}, nothing saved).
 */
@RestController
public class CommandController {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    private final CommandBus bus;
    private final ObjectProvider<SessionService> sessions;
    private final ObjectReader reader;

    public CommandController(CommandBus bus, ObjectProvider<SessionService> sessions, ObjectMapper mapper) {
        this.bus = bus;
        this.sessions = sessions;
        // Numbers keep every digit the sender wrote when a double cannot hold them (see RecordStore.exactReader);
        // every ordinary number is read as a double exactly as before.
        this.reader = RecordStore.exactReader(mapper).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    @PostMapping(path = "/api/commands", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Receipt> post(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        Session session = resolveSession(request);
        Command command;
        try {
            command = parse(body);
        } catch (Unreadable e) {
            return ResponseEntity.badRequest()
                    .body(CommandBus.unsaved(e.commandId, session, ReasonCode.INVALID, e.getMessage()));
        }
        Receipt receipt = bus.send(command, session);
        HttpStatus status = ReasonCode.SERVER_ERROR.equals(receipt.reasonCode())
                ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
        return ResponseEntity.status(status).body(receipt);
    }

    /** The session service's answer; anonymous if there is none, it finds nothing or it fails. Never automation. */
    Session resolveSession(HttpServletRequest request) {
        SessionService service = sessions.getIfAvailable();
        if (service == null) {
            return Session.anonymous();
        }
        Optional<Session> found;
        try {
            found = service.resolve(request);
        } catch (RuntimeException e) {
            log.warn("The session service refused the request ({}); treating it as anonymous",
                    e.getClass().getSimpleName());
            return Session.anonymous();
        }
        if (found == null || found.isEmpty() || found.get().isAutomation()) {
            return Session.anonymous();
        }
        return found.get();
    }

    private Command parse(byte[] body) throws Unreadable {
        if (body == null || body.length == 0) {
            throw new Unreadable(null, "The request has no command.");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new Unreadable(null, "The command is larger than 64 KiB.");
        }
        JsonNode root;
        try {
            root = reader.readTree(body);
        } catch (IOException e) {
            throw new Unreadable(null, "The command is not valid JSON (or repeats a key).");
        }
        if (root == null || !root.isObject()) {
            throw new Unreadable(null, "The command must be a JSON object.");
        }
        String commandId = textOrNull(root, "command_id");
        JsonNode revision = root.get("expected_revision");
        Long expectedRevision = null;
        if (revision != null && !revision.isNull()) {
            if (!revision.isIntegralNumber() || !revision.canConvertToLong()) {
                throw new Unreadable(commandId, "expected_revision must be a whole number or null.");
            }
            expectedRevision = revision.longValue();
        }
        JsonNode payload = root.get("payload");
        if (payload != null && !payload.isNull() && !payload.isObject()) {
            throw new Unreadable(commandId, "payload must be a JSON object.");
        }
        ObjectNode payloadObject = payload == null || payload.isNull()
                ? JsonNodeFactory.instance.objectNode()
                : (ObjectNode) payload;
        for (String field : List.of("schema_version", "command_id", "command_type", "run_id")) {
            JsonNode value = root.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw new Unreadable(commandId, field + " must be a string.");
            }
        }
        return new Command(textOrNull(root, "schema_version"), commandId, textOrNull(root, "command_type"),
                textOrNull(root, "run_id"), expectedRevision, payloadObject);
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** The body cannot be read as a command. */
    private static final class Unreadable extends Exception {
        private final String commandId;

        Unreadable(String commandId, String message) {
            super(message, null, false, false);
            this.commandId = commandId;
        }
    }
}
