package com.demobank.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when an email/password pair does not authenticate.
 *
 * <p>The message is intentionally identical for "unknown email", "wrong
 * password" and "disabled account". Distinguishing them would turn the login
 * endpoint into an oracle: an attacker could first harvest which addresses are
 * registered, then concentrate the guessing on real accounts. The server log
 * keeps the real reason; the client gets one neutral sentence.</p>
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
    }
}
