package com.demobank.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a request is syntactically valid but violates a business rule
 * that Bean Validation cannot express - for example a transfer whose source and
 * destination accounts are the same.
 *
 * <p>It results in {@code 400 Bad Request}, the same status used for field
 * validation failures, so a client can treat "the request must be corrected"
 * as one case.</p>
 */
public class InvalidOperationException extends ApiException {

    public InvalidOperationException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
