package com.demobank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the DemoBank REST API.
 *
 * <p>DemoBank is a <strong>didactic</strong> application: it simulates a retail
 * banking back office so that authentication, authorisation, input validation
 * and transactional integrity can be studied on a realistic example. It performs
 * no real banking operation and must never hold real personal data or real
 * credentials.</p>
 *
 * <p>{@code @ConfigurationPropertiesScan} picks up the typed configuration
 * classes (JWT, CORS, login throttling) so that security settings are bound and
 * validated at startup rather than read ad hoc at runtime.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DemoBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoBankApplication.class, args);
    }
}
