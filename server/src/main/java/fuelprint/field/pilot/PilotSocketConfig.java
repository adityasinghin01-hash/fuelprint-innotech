package fuelprint.field.pilot;

import fuelprint.api.SessionService;
import jakarta.servlet.ServletContext;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.context.ServletContextAware;

/**
 * Registers {@code WS /ws/pilot} with the servlet container's Jakarta WebSocket {@link ServerContainer}
 * (Tomcat's, which Spring Boot sets up), and its handshake filter. Spring's own WebSocket module is not on the
 * class path ({@code server/pom.xml} has no {@code spring-boot-starter-websocket}), so the plain Jakarta API is used.
 * In a test without a real server there is no container and nothing is registered.
 */
@Configuration
@Profile("!stub")
public class PilotSocketConfig implements ServletContextAware, SmartInitializingSingleton {

    public static final String PATH = "/ws/pilot";
    private static final Logger log = LoggerFactory.getLogger(PilotSocketConfig.class);

    private final PilotInputService input;
    private ServletContext servletContext;

    public PilotSocketConfig(PilotInputService input) {
        this.input = input;
    }

    @Bean
    public FilterRegistrationBean<PilotHandshakeFilter> pilotHandshakeFilter(ObjectProvider<SessionService> sessions) {
        FilterRegistrationBean<PilotHandshakeFilter> registration =
                new FilterRegistrationBean<>(new PilotHandshakeFilter(sessions));
        registration.addUrlPatterns(PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("fieldPilotHandshake");
        return registration;
    }

    @Override
    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Object attribute = servletContext == null ? null : servletContext.getAttribute(ServerContainer.class.getName());
        if (!(attribute instanceof ServerContainer container)) {
            log.info("No WebSocket server container (no real web server); {} is not registered", PATH);
            return;
        }
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(PilotSocket.class, PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public <T> T getEndpointInstance(Class<T> endpointClass) {
                        return endpointClass.cast(new PilotSocket(input));
                    }
                })
                .build();
        try {
            container.addEndpoint(config);
        } catch (DeploymentException e) {
            throw new IllegalStateException("Cannot register " + PATH, e);
        }
    }
}
