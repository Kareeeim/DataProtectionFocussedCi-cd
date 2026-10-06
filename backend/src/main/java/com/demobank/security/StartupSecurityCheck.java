package com.demobank.security;

import com.demobank.config.SecurityProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Last line of defence against a deployment that "works" but is insecure.
 *
 * <p>The most dangerous misconfiguration in a token-based API is not a crash -
 * it is a service that starts happily while signing tokens with a secret that
 * is published in the repository. Everyone can then forge a token for any user
 * and the logs look perfectly normal.</p>
 *
 * <p>This check runs after the context is ready and inspects the effective
 * configuration:</p>
 * <ul>
 *   <li>under the {@code prod} profile, any known demo value makes the
 *       application <strong>refuse to start</strong>;</li>
 *   <li>under any other profile, it logs a prominent warning so the state is
 *       visible rather than implicit.</li>
 * </ul>
 *
 * <p>Only the <em>names</em> of the offending settings are ever logged, never
 * their values.</p>
 */
@Component
public class StartupSecurityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityCheck.class);

    /**
     * The JWT key shipped in {@code .env.example} / {@code docker-compose.yml}.
     * Public by definition - it is in the repository.
     */
    static final String DEMO_JWT_SECRET =
            "CHANGE_ME_demo_only_3f8a1c9e5b7d2460af13c8e6d94b7205";

    /** Demo database passwords shipped in the same files. */
    private static final List<String> DEMO_DB_PASSWORDS = List.of(
            "demo_root_pwd_change_me",
            "demo_app_pwd_change_me",
            "demo_migrator_pwd_change_me");

    private final SecurityProperties securityProperties;
    private final Environment environment;

    public StartupSecurityCheck(SecurityProperties securityProperties, Environment environment) {
        this.securityProperties = securityProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean productionProfile = environment.matchesProfiles("prod");
        List<String> problems = new ArrayList<>();

        if (DEMO_JWT_SECRET.equals(securityProperties.getJwt().getSecret())) {
            problems.add("app.security.jwt.secret is still the demo key published in the repository");
        }

        for (String property : List.of("spring.datasource.password", "spring.flyway.password")) {
            String value = environment.getProperty(property);
            if (value != null && DEMO_DB_PASSWORDS.contains(value)) {
                problems.add(property + " is still a demo password published in the repository");
            }
        }

        if (problems.isEmpty()) {
            log.info("Security configuration check passed.");
            return;
        }

        if (productionProfile) {
            // Fail closed: better a deployment that does not start than one
            // that silently accepts forged credentials.
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile because demo credentials are in use: "
                            + String.join("; ", problems)
                            + ". Set JWT_SECRET, DB_APP_PASSWORD and DB_MIGRATOR_PASSWORD to real values.");
        }

        log.warn("""

                =====================================================================
                 DEMO CREDENTIALS IN USE - this configuration is for local
                 demonstration only and must never be exposed to a network.
                {}
                 Generate a real JWT key with:  openssl rand -base64 48
                =====================================================================""",
                problems.stream().map(p -> "  - " + p).reduce("", (a, b) -> a + "\n" + b));
    }
}
