package org.booklore.config.security.filter;

import org.booklore.config.security.userdetails.UserAuthenticationDetails;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates requests bearing a self-service API token (see {@link ApiTokenService}),
 * recognized by its {@code blt_} prefix so a plain {@code Authorization: Bearer <jwt>}
 * request passes straight through untouched (see the matching skip in
 * {@link JwtAuthenticationFilter}).
 *
 * <p>Unlike a normal login JWT, a token authenticated here is capped to read-only plus
 * reading-progress updates <em>regardless of the linked account's own permissions</em> -
 * every GET is allowed (scoped exactly like a normal login: same library/book visibility,
 * same download permission requirement), and only a small explicit allowlist of
 * progress-related mutations is allowed. Everything else is rejected here, before it ever
 * reaches a controller - deliberately not relying on each endpoint's own
 * {@code @PreAuthorize}, since several intentionally have none (e.g. change-password),
 * which is fine for a real login session but not for a token meant to leave read-only.
 */
@Slf4j
@Component("apiTokenAuthFilter")
@RequiredArgsConstructor
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private record AllowedMutation(HttpMethod method, String path) {}

    private static final Set<AllowedMutation> ALLOWED_MUTATIONS = Set.of(
            new AllowedMutation(HttpMethod.POST, "/api/v1/books/progress"),
            new AllowedMutation(HttpMethod.POST, "/api/v1/books/status"),
            new AllowedMutation(HttpMethod.POST, "/api/v1/books/reset-progress"),
            new AllowedMutation(HttpMethod.POST, "/api/v1/reading-sessions")
    );

    private final ApiTokenService apiTokenService;
    private final BookLoreUserTransformer bookLoreUserTransformer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null || !token.startsWith(ApiTokenService.TOKEN_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<BookLoreUserEntity> user = apiTokenService.authenticate(token);
        if (user.isEmpty()) {
            log.debug("Invalid or revoked API token. Rejecting request.");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or revoked API token");
            return;
        }

        if (!isAllowed(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "API tokens are read-only plus reading-progress updates");
            return;
        }

        BookLoreUser dto = bookLoreUserTransformer.toDTO(user.get());
        var authentication = new UsernamePasswordAuthenticationToken(dto, null, null);
        authentication.setDetails(new UserAuthenticationDetails(request, dto.getId()));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    private boolean isAllowed(HttpServletRequest request) {
        if (HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        var candidate = new AllowedMutation(HttpMethod.valueOf(request.getMethod()), request.getRequestURI());
        return ALLOWED_MUTATIONS.contains(candidate);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        return (bearer != null && bearer.startsWith("Bearer ")) ? bearer.substring(7) : null;
    }
}
