package fuelprint.core.session;

import java.io.IOException;

import fuelprint.api.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a POST, PUT, PATCH or DELETE to {@code /api/} from another web origin (cross-site request forgery),
 * using {@link SessionService#originAllowed}. Answers 403 with reason {@code ORIGIN_NOT_ALLOWED}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OriginFilter extends OncePerRequestFilter {

    private final SessionService sessions;

    public OriginFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean changing = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        return !changing || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!sessions.originAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"reason_code\":\"ORIGIN_NOT_ALLOWED\",\"reason_text\":"
                    + "\"This request came from another web page. Open the app from this server.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
