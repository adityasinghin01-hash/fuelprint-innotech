package fuelprint.field.pilot;

import java.io.IOException;
import java.security.Principal;

import fuelprint.field.world.FieldWorld;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code WS /ws/pilot}: one socket per phone or keyboard. The handshake was already checked by
 * {@link PilotHandshakeFilter} (session cookie and same origin), which put a {@link PilotPrincipal} on it.
 * On open and on close the drone goes neutral; old input is never replayed. Only rejected messages get an
 * answer: {@code {"message_type": "pilot_status", "accepted": false, "reason": "STALE_SEQUENCE"}}.
 */
public class PilotSocket extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(PilotSocket.class);
    static final long IDLE_TIMEOUT_MS = 30_000;

    private final PilotInputService input;

    public PilotSocket(PilotInputService input) {
        this.input = input;
    }

    /** The checked sender of a pilot socket. */
    public record PilotPrincipal(String actor) implements Principal {
        @Override
        public String getName() {
            return actor;
        }
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        Principal principal = session.getUserPrincipal();
        if (!(principal instanceof PilotPrincipal pilot)) {
            close(session, new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "not signed in"));
            return;
        }
        session.setMaxIdleTimeout(IDLE_TIMEOUT_MS);
        session.setMaxTextMessageBufferSize(PilotInputService.MAX_MESSAGE_CHARS);
        input.connected(pilot.actor());
        session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text -> {
            FieldWorld.InputResult result = input.accept(pilot.actor(), text);
            if (result != FieldWorld.InputResult.ACCEPTED && session.isOpen()) {
                session.getAsyncRemote().sendText("{\"message_type\":\"pilot_status\",\"accepted\":false,\"reason\":\""
                        + result.name() + "\"}");
            }
        });
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        if (session.getUserPrincipal() instanceof PilotPrincipal pilot) {
            input.disconnected(pilot.actor());
        }
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.debug("Pilot socket error", error);
        if (session.getUserPrincipal() instanceof PilotPrincipal pilot) {
            input.disconnected(pilot.actor());
        }
    }

    private static void close(Session session, CloseReason reason) {
        try {
            session.close(reason);
        } catch (IOException ignored) {
            // already closed
        }
    }
}
