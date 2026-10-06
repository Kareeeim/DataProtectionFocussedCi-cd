package com.demobank.config;

import com.demobank.security.JwtAuthenticationFilter;
import com.demobank.security.RestAccessDeniedHandler;
import com.demobank.security.RestAuthenticationEntryPoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The single place where HTTP security is decided.
 *
 * <p>The chain is built around four principles:</p>
 *
 * <ol>
 *   <li><strong>Deny by default.</strong> {@code anyRequest().denyAll()} closes
 *       the chain. A new controller added next year is unreachable until
 *       somebody deliberately opens a rule for it - the opposite of the usual
 *       "everything is public unless I remember to protect it".</li>
 *
 *   <li><strong>Stateless.</strong> No HTTP session is created or read, so
 *       there is no session identifier to steal, fixate or replay. Identity
 *       travels in the signed token only, which is also why CSRF protection is
 *       disabled: CSRF exists because browsers attach cookies automatically to
 *       cross-site requests, and this API never authenticates from a cookie.
 *       An attacker's page cannot make the browser add an
 *       {@code Authorization} header.</li>
 *
 *   <li><strong>Authentication is proved on every request.</strong> The JWT
 *       filter validates the signature and the expiry of each token; nothing is
 *       cached between requests.</li>
 *
 *   <li><strong>Failures are JSON and say little.</strong> 401 and 403 are
 *       rendered by dedicated handlers that never leak a stack trace or an
 *       internal detail.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize as a second line of defence on controllers
public class SecurityConfig {

    /** BCrypt work factor. 12 is ~250 ms per verification on current hardware. */
    private static final int BCRYPT_STRENGTH = 12;

    /**
     * Password encoder used for every hash and every verification.
     *
     * <p>BCrypt is chosen deliberately over a plain SHA-* digest: it embeds a
     * random per-password salt (so identical passwords produce different hashes
     * and rainbow tables are useless) and it is intentionally slow, which makes
     * offline brute force of a stolen dump expensive. The cost factor is raised
     * over time as hardware gets faster; stored hashes stay valid because the
     * cost is encoded in the hash itself.</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    /**
     * Prevents the JWT filter from also being registered as a plain servlet
     * filter by Spring Boot's automatic {@code Filter}-bean registration.
     *
     * <p>Without this, the filter would run twice per request - once inside the
     * security chain where it belongs, and once outside it, where it would set
     * an authentication that the security chain has already finished
     * evaluating. The subtle consequence would be endpoints responding as
     * authenticated while their authorisation rules were never applied.</p>
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            SecurityProperties properties) throws Exception {

        http
            // --- sessions and CSRF -----------------------------------------
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Safe only because authentication never comes from a cookie. If a
            // cookie-based scheme is ever introduced, this must be re-enabled.
            .csrf(csrf -> csrf.disable())

            // --- CORS -------------------------------------------------------
            .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))

            // --- security headers -------------------------------------------
            .headers(headers -> headers
                    // Nothing may embed the API in a frame (clickjacking).
                    .frameOptions(frame -> frame.deny())
                    // Never send the URL of an API call as a referrer.
                    .referrerPolicy(referrer ->
                            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    // A JSON endpoint needs no scripts, styles, images or
                    // frames. The policy value comes from configuration so the
                    // `demo` profile can relax it just enough for Swagger UI.
                    .contentSecurityPolicy(csp -> csp.policyDirectives(properties.getCspPolicy()))
                    // Deny access to device APIs the application never uses.
                    .permissionsPolicyHeader(permissions -> permissions.policy(
                            "geolocation=(), camera=(), microphone=(), payment=(), usb=()"))
                    // Only meaningful over HTTPS; browsers ignore it on plain
                    // HTTP, so it is safe to send in the local demo too.
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31_536_000))
                    .addHeaderWriter(new StaticHeadersWriter(
                            "Cross-Origin-Opener-Policy", "same-origin"))
                    .addHeaderWriter(new StaticHeadersWriter(
                            "Cross-Origin-Resource-Policy", "same-origin")))
            // Cache-Control is deliberately NOT configured here. Spring
            // Security's default writer already sends
            // "no-cache, no-store, max-age=0, must-revalidate" on every
            // response, which is exactly the guarantee this API needs: an
            // account statement must not survive in a browser cache, a proxy
            // cache or the disk of a shared machine, so that pressing "back"
            // after signing out cannot resurrect one. Adding a second,
            // path-scoped writer on top would emit the header twice - two
            // values that clients and proxies are free to reconcile
            // differently - for no additional protection. `AuthApiTest`
            // asserts the header is present, so the guarantee is covered even
            // if a future change disables the default.

            // --- authorisation rules ----------------------------------------
            .authorizeHttpRequests(auth -> auth
                    // The only public endpoint: it exists to hand out a token.
                    .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                    // Liveness/readiness probe, used by the Docker healthcheck.
                    // It exposes no detail (`show-details: never`).
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                    // API documentation: served only under the `demo` profile,
                    // where springdoc is enabled. Under `prod` these paths do
                    // not exist.
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()

                    // Everything else under /api requires a valid token. The
                    // ownership checks that follow are enforced in the service
                    // layer, because "authenticated" is not the same as
                    // "allowed to see this particular row".
                    .requestMatchers("/api/**").authenticated()

                    // Anything not matched above is refused outright.
                    .anyRequest().denyAll())

            // --- failure rendering ------------------------------------------
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))

            // --- token verification -----------------------------------------
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS policy: an explicit allow-list of origins, an explicit list of
     * methods and headers, and credentials disabled.
     *
     * <p>{@code allowedOrigins("*")} is never used: combined with a browser it
     * would let any website call this API and read the response on behalf of a
     * logged-in user. Credentials are disabled because the bearer token is
     * attached by the client application, not by the browser automatically.</p>
     *
     * <p><strong>A request addressed to its own origin is always allowed.</strong>
     * That is not a relaxation of the policy, it is the removal of a false
     * positive, and it is load-bearing for the Docker deployment. Browsers
     * attach an {@code Origin} header to every POST, same-origin or not. There,
     * nginx serves the SPA and proxies the API on a single port, so every login
     * and every transfer arrives carrying {@code Origin: http://localhost:8080}.
     * Judged against an allow-list written for the Angular dev server, that
     * request is refused with {@code 403 Invalid CORS request} - and because
     * curl sends no {@code Origin} at all, an API test suite passes while the
     * application is completely unusable from a browser. That is exactly the
     * failure this rule removes.</p>
     *
     * <p>It cannot be abused. A page on another site cannot forge the
     * {@code Origin} of its own requests - the browser sets it - and a client
     * that is not a browser is not subject to CORS in the first place. Genuine
     * cross-origin calls still have to appear in the configured allow-list.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        List<String> configuredOrigins = properties.getCors().getAllowedOrigins();

        UrlBasedCorsConfigurationSource pathMatching = new UrlBasedCorsConfigurationSource();
        pathMatching.registerCorsConfiguration("/api/**", baseConfiguration(configuredOrigins));

        return request -> {
            CorsConfiguration base = pathMatching.getCorsConfiguration(request);
            if (base == null) {
                return null;
            }
            String ownOrigin = ownOrigin(request);
            if (ownOrigin == null || base.getAllowedOrigins().contains(ownOrigin)) {
                return base;
            }
            // A copy: the registered instance is shared by every request, and
            // mutating it would let one caller's origin leak into the policy
            // applied to the next.
            CorsConfiguration effective = new CorsConfiguration(base);
            effective.addAllowedOrigin(ownOrigin);
            return effective;
        };
    }

    private static CorsConfiguration baseConfiguration(List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Accept-Language"));
        configuration.setExposedHeaders(List.of("Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        return configuration;
    }

    /**
     * Reconstructs the origin the client actually addressed, so it can be
     * compared with the {@code Origin} header it sent.
     *
     * <p>The forwarded headers are read directly rather than through the
     * framework's forwarded-header support, because the port must survive: a
     * browser talking to {@code http://localhost:8080} sends
     * {@code Origin: http://localhost:8080}, and an origin reconstructed without
     * the port would never match it. Trusting these headers is safe here for the
     * same reason {@code SERVER_FORWARD_HEADERS_STRATEGY} is set: the API has no
     * published port and is reachable only through the proxy that writes
     * them.</p>
     */
    private static String ownOrigin(HttpServletRequest request) {
        String host = firstNonBlank(
                request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null) {
            return null;
        }
        String scheme = firstNonBlank(
                request.getHeader("X-Forwarded-Proto"), request.getScheme());
        if (scheme == null) {
            return null;
        }
        // `Host` carries the port whenever it is not the scheme's default, and
        // an Origin header is written the same way, so the two are comparable
        // as they stand.
        return scheme + "://" + host;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
