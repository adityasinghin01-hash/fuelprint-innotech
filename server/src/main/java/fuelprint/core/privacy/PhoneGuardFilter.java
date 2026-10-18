package fuelprint.core.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Runs {@link PhoneGuard} over the body of every {@code /api} response, whichever package wrote it. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PhoneGuardFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            byte[] body = wrapper.getContentAsByteArray();
            String text = new String(body, StandardCharsets.UTF_8);
            String masked = PhoneGuard.mask(text, "the response of " + request.getMethod() + " " + request.getRequestURI());
            if (masked != text) {
                wrapper.resetBuffer();
                byte[] bytes = masked.getBytes(StandardCharsets.UTF_8);
                wrapper.getOutputStream().write(bytes);
            }
            wrapper.copyBodyToResponse();
        }
    }
}
