package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.service.ApiTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiTokenAuthFilterTest {

    private ApiTokenService apiTokenService;
    private BookLoreUserTransformer bookLoreUserTransformer;
    private ApiTokenAuthFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        apiTokenService = mock(ApiTokenService.class);
        bookLoreUserTransformer = mock(BookLoreUserTransformer.class);
        filter = new ApiTokenAuthFilter(apiTokenService, bookLoreUserTransformer);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void passesThroughWhenNoAuthorizationHeader() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(apiTokenService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesThroughUntouchedForNormalJwt_leavingItToJwtAuthenticationFilter() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer eyJhbGciOiJIUzI1NiJ9.some.jwt");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(apiTokenService);
    }

    @Test
    void rejectsUnknownOrRevokedApiToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + ApiTokenService.TOKEN_PREFIX + "bad");
        when(apiTokenService.authenticate(any())).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, chain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void allowsGetRequestForValidToken() throws Exception {
        String rawToken = ApiTokenService.TOKEN_PREFIX + "valid";
        BookLoreUserEntity entity = BookLoreUserEntity.builder().id(1L).build();
        BookLoreUser dto = new BookLoreUser();
        dto.setId(1L);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(request.getMethod()).thenReturn("GET");
        when(apiTokenService.authenticate(rawToken)).thenReturn(Optional.of(entity));
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(dto);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(dto);
    }

    @Test
    void allowsAllowlistedProgressMutation() throws Exception {
        String rawToken = ApiTokenService.TOKEN_PREFIX + "valid";
        BookLoreUserEntity entity = BookLoreUserEntity.builder().id(1L).build();
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/books/progress");
        when(apiTokenService.authenticate(rawToken)).thenReturn(Optional.of(entity));
        when(bookLoreUserTransformer.toDTO(entity)).thenReturn(new BookLoreUser());

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsNonAllowlistedMutation_evenForValidToken() throws Exception {
        String rawToken = ApiTokenService.TOKEN_PREFIX + "valid";
        BookLoreUserEntity entity = BookLoreUserEntity.builder().id(1L).build();
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestURI()).thenReturn("/api/v1/books");
        when(apiTokenService.authenticate(rawToken)).thenReturn(Optional.of(entity));

        filter.doFilterInternal(request, response, chain);

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), any());
        verify(chain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsChangePassword_evenThoughItsOpenToAnyAuthenticatedSession() throws Exception {
        String rawToken = ApiTokenService.TOKEN_PREFIX + "valid";
        BookLoreUserEntity entity = BookLoreUserEntity.builder().id(1L).build();
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/api/v1/users/change-password");
        when(apiTokenService.authenticate(rawToken)).thenReturn(Optional.of(entity));

        filter.doFilterInternal(request, response, chain);

        verify(response).sendError(eq(HttpServletResponse.SC_FORBIDDEN), any());
        verify(chain, never()).doFilter(any(), any());
    }
}
