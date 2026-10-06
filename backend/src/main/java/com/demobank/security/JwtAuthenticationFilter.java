package com.demobank.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code Authorization: Bearer <token>} header and, when the token
 * verifies, populates the Spring Security context for the current request.
 *
 * <p>Three deliberate choices:</p>
 * <ol>
 *   <li><strong>Fail open to "anonymous", never to "authenticated".</strong> If
 *       anything at all is wrong with the token, the context is cleared and the
 *       request continues unauthenticated. It is then the authorisation rules
 *       (deny by default) that reject it with 401 - not this filter, which
 *       never writes a response and therefore cannot be tricked into letting a
 *       request through.</li>
 *   <li><strong>The token grants only the role it carries, checked against a
 *       fixed enum.</strong> An unknown role is rejected in
 *       {@link JwtService#parseAndVerify(String)}; there is no default.</li>
 *   <li><strong>Nothing about the token is logged at normal level.</strong> A
 *       JWT is a bearer credential: whoever reads it can use it.</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        // Only act when nobody is authenticated yet: never overwrite an
        // existing authentication with one derived from a header.
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthenticatedUser user = jwtService.parseAndVerify(token);

                var authentication = new UsernamePasswordAuthenticationToken(
                        user,
                        // No credentials are retained: the token has already
                        // been verified, and keeping it would leave the
                        // credential lying around in memory for the whole
                        // request.
                        null,
                        List.of(new SimpleGrantedAuthority(user.authority())));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
                jwtService.logRejection(ex.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the bearer token, enforcing the exact scheme.
     *
     * @return the compact token, or {@code null} when the header is absent or
     *         does not use the {@code Bearer} scheme
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
