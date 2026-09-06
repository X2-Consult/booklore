package org.booklore.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
@Profile({"dev"})
public class LoggingFilter extends OncePerRequestFilter {

    /**
     * Headers whose values are credentials. These are never written to the log - a dev-profile
     * instance still handles real user tokens, and anyone who can read the journal could replay
     * them. Compared lower-case.
     */
    private static final Set<String> REDACTED_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-auth-key",
            "x-auth-user",
            "x-api-key",
            "x-catalog-api-key"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getRequestURI().startsWith("/ws")) {
            filterChain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();

        log.info("Incoming request: {} {} from IP {}",
                request.getMethod(),
                request.getRequestURI(),
                request.getRemoteAddr());

        if (log.isDebugEnabled()) {
            var headerNames = request.getHeaderNames();
            if (headerNames != null) {
                while (headerNames.hasMoreElements()) {
                    String headerName = headerNames.nextElement();
                    log.debug("Header: {}={}", headerName, headerValue(headerName, request));
                }
            }
        }

        filterChain.doFilter(request, response);

        long duration = System.currentTimeMillis() - start;
        log.info("Completed {} {} with status {} in {} ms",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                duration);
    }

    private String headerValue(String headerName, HttpServletRequest request) {
        return REDACTED_HEADERS.contains(headerName.toLowerCase())
                ? "<redacted>"
                : request.getHeader(headerName);
    }
}
