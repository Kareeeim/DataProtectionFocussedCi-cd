package com.demobank.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for every business failure that must reach the client with a
 * specific HTTP status and a stable error code.
 *
 * <p>Carrying the status and the code on the exception - rather than deciding
 * them in the handler with a chain of {@code instanceof} checks - keeps the
 * mapping in one obvious place and makes the intent of each failure explicit at
 * the point where it is raised.</p>
 *
 * <p>The {@code message} of these exceptions is treated as
 * <strong>client-safe</strong>: it is returned verbatim, so it must never
 * contain a stack trace, a SQL statement or an internal identifier. Anything
 * that is not safe to show belongs in a log line, not in the message.</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
