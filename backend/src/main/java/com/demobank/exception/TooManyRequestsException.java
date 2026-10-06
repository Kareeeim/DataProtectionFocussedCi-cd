package com.demobank.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a client has made too many failed login attempts in the
 * configured window.
 *
 * <p>{@code 429 Too Many Requests} tells an honest client to slow down and
 * costs a password-guessing script most of its throughput. The message is
 * deliberately vague about the state of the account: it must not reveal whether
 * the password was correct on any of the attempts, nor whether the address
 * exists at all.</p>
 */
public class TooManyRequestsException extends ApiException {

    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message);
    }
}
