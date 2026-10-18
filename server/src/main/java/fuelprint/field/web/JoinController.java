package fuelprint.field.web;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.SessionService;
import fuelprint.core.Ids;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandBus;
import fuelprint.core.command.Receipt;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.field.FieldTime;
import fuelprint.field.command.FieldRecords;
import fuelprint.field.command.PairingHandlers;
import fuelprint.field.world.FieldWorld;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/session/join} (AGREEMENT section 3): a phone redeems a pairing code. Body {@code {"code": "KQ7TXP"}}.
 * Same origin only, at most {@value #MAX_TRIES_PER_MINUTE} tries a minute from one address. On success a new
 * visitor session is made ({@link SessionService#createVisitor}), the {@code redeem_pairing} command is sent with
 * it through the command bus, the cookie is set and the phone gets the two-word phrase to show the operator.
 * Answers: 200 joined, 400 malformed, 403 wrong origin, 404 code not valid or expired, 409 already used,
 * 429 too many tries, 503 no session service or no run.
 */
@RestController
@Profile("!stub")
public class JoinController {

    static final int MAX_TRIES_PER_MINUTE = 10;
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{6}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<SessionService> sessions;
    private final CommandBus bus;
    private final RecordStore store;
    private final FieldWorld world;
    private final FieldTime time;
    private final Map<String, Deque<Long>> tries = new ConcurrentHashMap<>();

    public JoinController(ObjectProvider<SessionService> sessions, CommandBus bus, RecordStore store,
                          FieldWorld world, FieldTime time) {
        this.sessions = sessions;
        this.bus = bus;
        this.store = store;
        this.world = world;
        this.time = time;
    }

    @PostMapping(path = "/api/session/join", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> join(@RequestBody(required = false) JsonNode body,
                                                    HttpServletRequest request, HttpServletResponse response) {
        SessionService service = sessions.getIfAvailable();
        if (service == null) {
            return answer(HttpStatus.SERVICE_UNAVAILABLE, "NO_SESSIONS", "Joining is not available on this server.");
        }
        if (!service.originAllowed(request)) {
            return answer(HttpStatus.FORBIDDEN, "WRONG_ORIGIN", "Open the controller page from this server.");
        }
        if (!allowTry(request.getRemoteAddr())) {
            return answer(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_TRIES", "Too many tries. Wait a minute.");
        }
        String code = PairingHandlers.normaliseCode(body == null ? null : body.path("code").asText(null));
        if (!CODE.matcher(code).matches()) {
            return answer(HttpStatus.BAD_REQUEST, "INVALID", "The code has 6 letters and numbers.");
        }
        Optional<String> runId = world.runId();
        if (runId.isEmpty()) {
            return answer(HttpStatus.SERVICE_UNAVAILABLE, "NO_RUN", "No run is going on.");
        }
        Optional<StoredRecord> pairing = store.list(FieldRecords.PAIRING, runId.get()).stream()
                .filter(p -> code.equals(p.body().path("code").asText()))
                .filter(p -> "CREATED".equals(FieldRecords.state(p)))
                .findFirst();
        if (pairing.isEmpty()) {
            return answer(HttpStatus.NOT_FOUND, "CODE_NOT_VALID", "That code is not valid or has expired.");
        }
        Session visitor = service.createVisitor("visitor-" + HexFormat.of().formatHex(randomBytes()));
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("pairing_id", pairing.get().id()).put("code", code);
        Receipt receipt = bus.send(new Command(Command.SCHEMA_VERSION, Ids.newId(), "redeem_pairing", runId.get(),
                null, payload), visitor);
        if (!receipt.applied()) {
            HttpStatus status = "ALREADY_USED".equals(receipt.reasonCode()) ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
            return answer(status, receipt.reasonCode(), receipt.reasonText());
        }
        service.writeCookie(response, visitor);
        StoredRecord redeemed = store.get(FieldRecords.PAIRING, pairing.get().id()).orElse(pairing.get());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pairing_id", redeemed.id());
        out.put("run_id", redeemed.runId());
        out.put("mission_id", redeemed.body().path("mission_id").asText());
        out.put("vehicle_id", redeemed.body().path("vehicle_id").asText());
        out.put("phrase", redeemed.body().path("phrase").asText());
        out.put("visitor", visitor.actorLabel());
        out.put("expires_utc", redeemed.body().path("expires_utc").asText());
        return ResponseEntity.ok(out);
    }

    private boolean allowTry(String address) {
        long now = time.monoMillis();
        Deque<Long> recent = tries.computeIfAbsent(address == null ? "?" : address, a -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > 60_000) {
                recent.pollFirst();
            }
            if (recent.size() >= MAX_TRIES_PER_MINUTE) {
                return false;
            }
            recent.addLast(now);
            return true;
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static ResponseEntity<Map<String, Object>> answer(HttpStatus status, String code, String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason_code", code);
        out.put("reason_text", text);
        return ResponseEntity.status(status).body(out);
    }
}
