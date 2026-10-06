package com.demobank.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials submitted to {@code POST /api/auth/login}.
 *
 * <p>The size limits are not cosmetic. BCrypt only considers the first 72 bytes
 * of a password, so accepting longer input would mean two different passwords
 * authenticating the same account. Enforcing the limit here also stops an
 * attacker from making the server hash megabyte-sized payloads: BCrypt at cost
 * 12 takes roughly 250 ms, and the cheapest denial of service against a login
 * endpoint is to send work that is expensive to reject.</p>
 *
 * <p>Jackson is configured to reject unknown properties, so a body carrying
 * extra fields such as {@code "role": "ROLE_ADMIN"} is refused outright rather
 * than partially bound.</p>
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password) {
}
