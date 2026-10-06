package com.demobank.security;

import com.demobank.config.SecurityProperties;
import com.demobank.domain.Role;
import com.demobank.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the JSON Web Tokens used as access tokens.
 *
 * <p>Why a signed token instead of a server-side session: the API stays
 * stateless (any replica can serve any request, nothing to replicate) and no
 * session cookie exists, which removes CSRF as an attack class entirely. The
 * price is that a token cannot be un-issued before it expires, which is why the
 * lifetime is deliberately short ({@code app.security.jwt.ttl}, 30 minutes by
 * default).</p>
 *
 * <p>Properties of the tokens produced here:</p>
 * <ul>
 *   <li><strong>Signed</strong> with HMAC-SHA256 using a key of at least 256
 *       bits, so the payload cannot be modified by the client. The payload is
 *       <em>not</em> encrypted - anybody can read it - so it carries no secret,
 *       just an identifier, an email, a name and a role.</li>
 *   <li><strong>Time-bounded</strong>: {@code iat} and {@code exp} are always
 *       set and verified, with a small clock-skew tolerance.</li>
 *   <li><strong>Issuer-bound</strong>: tokens minted for another service with
 *       the same key are rejected through the {@code iss} check.</li>
 *   <li><strong>Unique</strong>: every token carries a random {@code jti}, which
 *       makes tokens distinguishable in an audit log and leaves the door open
 *       for a future revocation list.</li>
 * </ul>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_ROLE = "role";

    private final SecurityProperties properties;
    private final SecretKey signingKey;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        // The key length was validated at startup (@Size(min = 32)); building it
        // here means a malformed key fails the context, not the first login.
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mints an access token for a successfully authenticated user.
     *
     * @param user the authenticated user (must be persisted, so it has an id)
     * @return the compact, signed JWT
     */
    public String issueToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getJwt().getTtl());

        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                // `sub` is the user id: the single value all ownership checks
                // are derived from.
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_NAME, user.getFullName())
                .claim(CLAIM_ROLE, user.getRole().name())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies the signature and the time bounds of a token and turns it into
     * the principal used by the rest of the application.
     *
     * <p>Any anomaly - bad signature, expired, wrong issuer, missing or
     * unparseable claims, unknown role - raises {@link JwtException} (or
     * {@link IllegalArgumentException} for a structurally broken subject) and
     * the caller treats the request as unauthenticated. There is no "trust the
     * token anyway" path.</p>
     *
     * @param token compact JWT taken from the {@code Authorization} header
     * @throws JwtException if the token is not valid
     */
    public AuthenticatedUser parseAndVerify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getJwt().getIssuer())
                .clockSkewSeconds(properties.getJwt().getClockSkew().toSeconds())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException ex) {
            // A subject that is not a number cannot have been produced by
            // issueToken(), so the token is not trustworthy.
            throw new JwtException("Token subject is not a valid user id");
        }

        String roleName = claims.get(CLAIM_ROLE, String.class);
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException | NullPointerException ex) {
            // Unknown or missing role: refuse, never fall back to a default.
            throw new JwtException("Token carries an unknown role");
        }

        return new AuthenticatedUser(
                userId,
                claims.get(CLAIM_EMAIL, String.class),
                claims.get(CLAIM_NAME, String.class),
                role);
    }

    /** Token lifetime in seconds, reported to the client at login. */
    public long getTtlSeconds() {
        return properties.getJwt().getTtl().toSeconds();
    }

    /**
     * Logs a token rejection without ever writing the token itself to the log.
     * A JWT in a log file is a credential in a log file.
     */
    public void logRejection(String reason) {
        log.debug("Rejected bearer token: {}", reason);
    }
}
