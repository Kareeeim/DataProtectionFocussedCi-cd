package com.demobank.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated configuration for everything under {@code app.security}.
 *
 * <p>Binding the security settings to an object that is validated by
 * {@code @Validated} means a missing or too-weak setting is a
 * <strong>startup failure</strong>, not a silent runtime weakening. A service
 * that boots with an empty JWT secret and signs tokens with it would look
 * healthy while accepting forged tokens from anyone; this class makes that
 * impossible.</p>
 *
 * <p>The secret itself is never logged and has no default value.</p>
 */
@ConfigurationProperties(prefix = "app.security")
@Validated
public class SecurityProperties {

    @Valid
    @NotNull
    private Jwt jwt = new Jwt();

    @Valid
    @NotNull
    private Cors cors = new Cors();

    @Valid
    @NotNull
    private Login login = new Login();

    /**
     * Value of the {@code Content-Security-Policy} header returned by the API.
     * A pure JSON endpoint needs no scripts, styles, images or frames, so the
     * default is maximally restrictive.
     */
    @NotBlank
    private String cspPolicy = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    // --- JWT ---------------------------------------------------------------

    public static class Jwt {

        /**
         * HMAC-SHA256 signing key. Must be at least 32 characters (256 bits):
         * a shorter key is brute-forceable, which would let an attacker mint a
         * token for any user.
         */
        @NotBlank(message = "app.security.jwt.secret (env JWT_SECRET) must be set")
        @Size(min = 32, message = "app.security.jwt.secret (env JWT_SECRET) must be at least 32 characters")
        private String secret;

        @NotBlank
        private String issuer = "demobank-api";

        /** Access-token lifetime. Short by design: a stolen token expires. */
        @NotNull
        private Duration ttl = Duration.ofMinutes(30);

        /** Tolerance applied when validating {@code exp} / {@code iat}. */
        @NotNull
        private Duration clockSkew = Duration.ofSeconds(30);

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }
    }

    // --- CORS --------------------------------------------------------------

    public static class Cors {

        /**
         * Exact origins allowed to call the API from a browser. Never {@code *}:
         * a wildcard lets any website drive the API on behalf of a logged-in
         * user. The Docker deployment is same-origin through nginx and needs
         * no CORS entry at all; the default only covers the Angular dev server.
         */
        @NotNull
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:4200"));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    // --- Login throttling ---------------------------------------------------

    public static class Login {

        /** Failed attempts tolerated per account (and per IP) before lockout. */
        @Min(1)
        private int maxAttempts = 5;

        /** Length of the sliding window in which failures are counted. */
        @NotNull
        private Duration window = Duration.ofMinutes(15);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    // --- accessors ---------------------------------------------------------

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public Login getLogin() {
        return login;
    }

    public void setLogin(Login login) {
        this.login = login;
    }

    public String getCspPolicy() {
        return cspPolicy;
    }

    public void setCspPolicy(String cspPolicy) {
        this.cspPolicy = cspPolicy;
    }
}
