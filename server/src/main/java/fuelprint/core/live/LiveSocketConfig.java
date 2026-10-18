package fuelprint.core.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers {@code /ws/live} (CORE-API section 6). Field registers {@code /ws/pilot} in its own package. */
@Configuration
@ConditionalOnWebApplication
@EnableWebSocket
public class LiveSocketConfig implements WebSocketConfigurer {

    private final LiveSocket socket;

    public LiveSocketConfig(LiveSocket socket) {
        this.socket = socket;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // The origin is checked by LiveSocket.beforeHandshake (same rule as POSTs), so Spring's own check is open.
        registry.addHandler(socket, "/ws/live").addInterceptors(socket).setAllowedOriginPatterns("*");
    }
}
