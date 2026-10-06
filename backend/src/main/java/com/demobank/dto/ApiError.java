package com.demobank.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * The single error shape returned by every endpoint.
 *
 * <p>Having one shape matters for two reasons. Clients can handle failures
 * generically instead of guessing per endpoint, and the API can guarantee what
 * is <em>not</em> in the body: no stack trace, no SQL fragment, no class name,
 * no internal identifier. Those details go to the server log, where an operator
 * can read them and an attacker cannot.</p>
 *
 * <p>{@code code} is a stable, machine-readable token (for example
 * {@code INSUFFICIENT_FUNDS}); {@code message} is a human sentence safe to show
 * to an end user. Clients should branch on {@code code}, never on the
 * message text.</p>
 *
 * @param timestamp   moment the error was produced (UTC)
 * @param status      HTTP status code, repeated in the body for convenience
 * @param error       standard reason phrase of that status
 * @param code        stable application error code
 * @param message     safe, user-presentable explanation
 * @param path        request path that produced the error
 * @param fieldErrors per-field validation failures; omitted when empty
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    /**
     * One invalid field of a rejected request.
     *
     * @param field  name of the offending field as the client sent it
     * @param message explanation of the rule that was broken
     */
    public record FieldViolation(String field, String message) {
    }

    /** Builds an error with no field-level detail. */
    public static ApiError of(HttpStatus status, String code, String message, String path) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                code, message, path, List.of());
    }

    /** Builds an error carrying per-field validation detail. */
    public static ApiError ofValidation(HttpStatus status, String code, String message,
                                        String path, List<FieldViolation> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                code, message, path, fieldErrors);
    }
}
