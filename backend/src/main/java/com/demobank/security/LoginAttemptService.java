package com.demobank.security;

import com.demobank.config.SecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sliding-window counter that slows down password-guessing against the login
 * endpoint.
 *
 * <p>Two keys are tracked for every attempt, and both must be clear for the
 * attempt to be processed:</p>
 * <ul>
 *   <li>{@code user:<email>} - stops an attacker from trying thousands of
 *       passwords against one account, from any number of machines.</li>
 *   <li>{@code ip:<address>} - stops a single machine from spraying one
 *       password across thousands of accounts.</li>
 * </ul>
 *
 * <p>Only <em>failed</em> attempts are counted and a success clears the
 * counter for that pair, so a legitimate user who mistypes twice and then
 * succeeds is not penalised.</p>
 *
 * <p><strong>Deliberate limitation.</strong> The state lives in this JVM's
 * memory. It is the right trade-off for a single-instance didactic deployment:
 * no external dependency, no shared state to protect. A real multi-replica
 * deployment must move this counter to a shared store (Redis, or a gateway-level
 * limiter) - otherwise each replica counts separately and the effective limit is
 * multiplied by the number of replicas. The map is bounded so that a flood of
 * distinct keys cannot exhaust memory.</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Hard cap on tracked keys; beyond this, expired entries are evicted. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int maxAttempts;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginAttemptService(SecurityProperties properties) {
        this.maxAttempts = properties.getLogin().getMaxAttempts();
        this.window = properties.getLogin().getWindow();
    }

    /** Key for a specific account, independent of where the attempt came from. */
    public static String userKey(String email) {
        return "user:" + email;
    }

    /** Key for a specific source address, independent of the account targeted. */
    public static String ipKey(String remoteAddress) {
        return "ip:" + remoteAddress;
    }

    /**
     * @return {@code true} when the key has reached the failure limit inside the
     *         current window and must be refused
     */
    public boolean isBlocked(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            purgeExpired(attempts);
            return attempts.size() >= maxAttempts;
        }
    }

    /** Records one failed attempt for the key. */
    public void recordFailure(String key) {
        if (failures.size() >= MAX_TRACKED_KEYS) {
            evictExpired();
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            purgeExpired(attempts);
            attempts.addLast(Instant.now());
        }
        log.warn("Failed login attempt recorded for '{}' ({} in the last {})",
                key, attempts.size(), window);
    }

    /** Clears the counter after a successful authentication. */
    public void reset(String key) {
        failures.remove(key);
    }

    /** Removes timestamps that have fallen out of the sliding window. */
    private void purgeExpired(Deque<Instant> attempts) {
        Instant cutoff = Instant.now().minus(window);
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.removeFirst();
        }
    }

    /**
     * Drops every key whose window has elapsed. Called only when the map grows
     * past its cap, so the cost is amortised and never paid per request.
     */
    private void evictExpired() {
        Instant cutoff = Instant.now().minus(window);
        Iterator<Map.Entry<String, Deque<Instant>>> it = failures.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Instant> attempts = it.next().getValue();
            synchronized (attempts) {
                purgeExpired(attempts);
                if (attempts.isEmpty()) {
                    it.remove();
                }
            }
        }
        if (failures.size() >= MAX_TRACKED_KEYS) {
            // Still saturated after purging: the window is under a sustained
            // flood. Drop everything rather than grow without bound; the worst
            // case is a brief relaxation of the limit, never an OOM.
            log.warn("Login throttle table saturated ({} keys); resetting it.", failures.size());
            failures.clear();
        }
    }

    /** Exposed for diagnostics and tests. */
    int trackedKeys() {
        return failures.size();
    }

    /** Exposed for diagnostics and tests. */
    int maxAttempts() {
        return maxAttempts;
    }
}
