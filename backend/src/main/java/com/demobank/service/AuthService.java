package com.demobank.service;

import com.demobank.domain.User;
import com.demobank.dto.LoginRequest;
import com.demobank.dto.LoginResponse;
import com.demobank.exception.InvalidCredentialsException;
import com.demobank.exception.TooManyRequestsException;
import com.demobank.repository.UserRepository;
import com.demobank.security.JwtService;
import com.demobank.security.LoginAttemptService;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Authenticates customers and issues access tokens.
 *
 * <p>The whole class is built around one idea: an attacker must learn nothing
 * from the login endpoint except "it worked" or "it did not". Everything below
 * serves that goal.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;

    /**
     * A throwaway hash used only when the submitted email does not exist.
     *
     * <p>Comparing against a real hash costs the same ~250 ms as comparing
     * against a real user's hash, so an attacker cannot tell registered
     * addresses from unknown ones by timing the response. Without this, "unknown
     * email" would return in microseconds while "wrong password" would take a
     * quarter of a second - a trivially measurable difference that enumerates
     * the customer base.</p>
     *
     * <p>It is <em>derived at startup</em> rather than written out as a literal,
     * and that is the whole point. A hand-written BCrypt string is easy to get
     * wrong - one character too many and the encoder rejects it as malformed,
     * returns {@code false} immediately, and the equalisation silently stops
     * working while every comment still claims it does. Encoding a random value
     * here cannot drift: the encoder produces a well-formed hash at the
     * configured cost factor by construction, and the random input is discarded
     * so no password can ever match it.</p>
     */
    private final String timingEqualisationHash;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LoginAttemptService loginAttempts) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttempts = loginAttempts;

        // One BCrypt operation, once, at startup - not per request. Failures
        // cannot reach a caller: if the encoder were misconfigured the context
        // would fail to start, which is where that belongs.
        this.timingEqualisationHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Verifies credentials and returns a signed access token.
     *
     * @param request       validated credentials
     * @param remoteAddress source address of the caller, used as the second
     *                      throttling key
     * @throws InvalidCredentialsException if the pair does not authenticate
     * @throws TooManyRequestsException    if either throttle key is exhausted
     */
    public LoginResponse login(LoginRequest request, String remoteAddress) {

        // Normalise before anything else: the column is case-insensitive and
        // unique, so lookups and throttle keys must both use the same form,
        // otherwise "Alice@demo.local" and "alice@demo.local" would be counted
        // as two different accounts by the rate limiter.
        String email = normaliseEmail(request.email());

        String userKey = LoginAttemptService.userKey(email);
        String ipKey = LoginAttemptService.ipKey(remoteAddress);

        if (loginAttempts.isBlocked(userKey) || loginAttempts.isBlocked(ipKey)) {
            // The message gives no hint about which key tripped, nor whether the
            // account exists.
            log.warn("Login throttled for email='{}' from {}", email, remoteAddress);
            throw new TooManyRequestsException(
                    "Too many failed login attempts. Please try again later.");
        }

        Optional<User> maybeUser = userRepository.findByEmail(email);

        // Always perform exactly one BCrypt comparison, whether or not the user
        // exists, so the response time does not reveal which case we are in.
        String hashToCheck = maybeUser.map(User::getPasswordHash)
                .orElse(timingEqualisationHash);
        boolean passwordMatches = verifyPassword(request.password(), hashToCheck);

        if (maybeUser.isEmpty() || !passwordMatches) {
            loginAttempts.recordFailure(userKey);
            loginAttempts.recordFailure(ipKey);
            // Log the attempt without the submitted password - obviously - and
            // without confirming whether the account exists.
            log.warn("Failed authentication attempt for email='{}' from {}", email, remoteAddress);
            throw new InvalidCredentialsException();
        }

        User user = maybeUser.get();

        if (!user.isEnabled()) {
            // A disabled account is reported exactly like a wrong password: the
            // client learns nothing, the operator still sees it in the log.
            loginAttempts.recordFailure(userKey);
            log.warn("Authentication refused for disabled account '{}'", email);
            throw new InvalidCredentialsException();
        }

        // Success clears the counters so a user who mistyped twice is not left
        // locked out for the rest of the window.
        loginAttempts.reset(userKey);
        loginAttempts.reset(ipKey);

        String token = jwtService.issueToken(user);
        log.info("User '{}' (id={}) authenticated successfully", user.getEmail(), user.getId());

        return LoginResponse.of(token, jwtService.getTtlSeconds(), user);
    }

    /**
     * Compares a raw password with a stored hash, treating any encoder failure
     * as a failed authentication rather than letting it surface as a 500.
     *
     * <p>The length is already bounded by Bean Validation; this guard covers the
     * residual case of a multi-byte password that exceeds BCrypt's 72-byte
     * input limit, where the encoder is allowed to reject the input.</p>
     */
    private boolean verifyPassword(String rawPassword, String hash) {
        try {
            return passwordEncoder.matches(rawPassword, hash);
        } catch (IllegalArgumentException ex) {
            log.debug("Password verification rejected the input: {}", ex.getMessage());
            return false;
        }
    }

    /** Trims and lowercases, matching the case-insensitive unique index. */
    private String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
