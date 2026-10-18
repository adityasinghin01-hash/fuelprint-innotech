package fuelprint.core.live;

import java.net.URI;
import java.util.Map;

import fuelprint.api.SessionService;
import fuelprint.core.Ids;
import jakarta.websocket.Session;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code WS /ws/live?run_id=&after=}: read-only. Incoming messages are ignored. The handshake is refused for a
 * foreign web origin ({@link SessionService#originAllowed}). No login is needed to watch: the stream holds no
 * truth and no phone number.
 */
@Component
public class LiveSocket extends TextWebSocketHandler implements HandshakeInterceptor {

    /** Tomcat: give up on a blocked send after this long (the client is then closed and reconnects). */
    static final long SEND_TIMEOUT_MS = 5000;

    private final LiveHub hub;
    private final SessionService sessions;

    public LiveSocket(LiveHub hub, SessionService sessions) {
        this.hub = hub;
        this.sessions = sessions;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet && !sessions.originAllowed(servlet.getServletRequest())) {
            response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
        // nothing
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session instanceof StandardWebSocketSession standard) {
            Session nativeSession = standard.getNativeSession(Session.class);
            if (nativeSession != null) {
                nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", SEND_TIMEOUT_MS);
            }
        }
        URI uri = session.getUri();
        var params = uri == null ? null : UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String runId = params == null ? null : params.getFirst("run_id");
        String afterText = params == null ? null : params.getFirst("after");
        if (runId != null && (runId.isBlank() || !Ids.isUuid(runId))) {
            runId = null;
        }
        Long after = null;
        if (afterText != null && !afterText.isBlank()) {
            try {
                after = Long.parseLong(afterText.trim());
            } catch (NumberFormatException e) {
                session.close(CloseStatus.BAD_DATA.withReason("after must be a cursor number"));
                return;
            }
        }
        hub.connect(session, runId, after);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Read-only stream: commands go to POST /api/commands.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.disconnect(session);
    }
}
