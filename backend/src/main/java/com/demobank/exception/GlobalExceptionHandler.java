package com.demobank.exception;

import com.demobank.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception that escapes a controller into the single
 * {@link ApiError} shape.
 *
 * <p>Two rules govern this class:</p>
 *
 * <ol>
 *   <li><strong>Nothing internal crosses the boundary.</strong> Stack traces,
 *       SQL fragments, class names and exception messages from third-party
 *       libraries stay in the server log. A client receives a stable error
 *       code and a sentence written for a human. The catch-all handler is the
 *       important one here: it exists so that an unexpected failure cannot
 *       leak a framework message by default.</li>
 *
 *   <li><strong>Expected failures are not errors.</strong> A rejected payload
 *       or a refused transfer is normal traffic and is logged at {@code WARN}
 *       (or not at all); only the catch-all logs at {@code ERROR} with the
 *       stack trace, because that one genuinely needs investigating.</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Business failures raised deliberately by the service layer. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex,
                                                      HttpServletRequest request) {
        log.warn("Request to {} {} refused: {} ({})",
                request.getMethod(), request.getRequestURI(), ex.getCode(), ex.getMessage());

        ApiError body = ApiError.of(ex.getStatus(), ex.getCode(), ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Bean Validation failures on a request body: one entry per offending
     * field, so the UI can highlight exactly which input to fix.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                        HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldViolation)
                .toList();

        ApiError body = ApiError.ofValidation(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The request contains invalid fields.", request.getRequestURI(), violations);
        return ResponseEntity.badRequest().body(body);
    }

    /** Bean Validation failures on query parameters or path variables. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(ConstraintViolationException ex,
                                                             HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(this::toFieldViolation)
                .toList();

        ApiError body = ApiError.ofValidation(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The request contains invalid parameters.", request.getRequestURI(), violations);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Unparseable body, wrong JSON type, or an unknown property (Jackson is
     * configured with {@code fail-on-unknown-properties}). The parser's own
     * message can quote the payload, so it is replaced with a neutral one.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        log.warn("Unreadable request body on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());

        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body is not valid JSON or contains unexpected fields.",
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** A path variable or query parameter that cannot be converted, e.g. {@code /accounts/abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                      HttpServletRequest request) {
        ApiError body = ApiError.ofValidation(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "A request parameter has an invalid format.", request.getRequestURI(),
                List.of(new ApiError.FieldViolation(ex.getName(), "Invalid format")));
        return ResponseEntity.badRequest().body(body);
    }

    /** A required query parameter is missing. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                          HttpServletRequest request) {
        ApiError body = ApiError.ofValidation(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "A required request parameter is missing.", request.getRequestURI(),
                List.of(new ApiError.FieldViolation(ex.getParameterName(), "Required")));
        return ResponseEntity.badRequest().body(body);
    }

    /** Wrong HTTP verb for an existing path. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                            HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "This HTTP method is not supported for this resource.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    /**
     * Raised by {@code @PreAuthorize} when an authenticated caller lacks the
     * required authority. Handled here as well as in the security filter chain
     * because method-level checks throw inside the controller invocation, which
     * is after the chain's {@code AccessDeniedHandler} has already passed.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                      HttpServletRequest request) {
        log.warn("Access denied on {} {}", request.getMethod(), request.getRequestURI());

        ApiError body = ApiError.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You are not allowed to perform this operation.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** Unknown path. Answered with the same shape as everything else. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                    HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "The requested resource does not exist.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Catch-all.
     *
     * <p>This is the handler that decides what an unforeseen failure looks
     * like from outside. The client gets {@code 500} and one generic sentence;
     * the stack trace goes to the log, where it belongs. Returning
     * {@code ex.getMessage()} here would be a permanent invitation to leak
     * whatever a future library decides to put in its exception messages -
     * connection strings, file paths, SQL.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        ApiError body = ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ApiError.FieldViolation toFieldViolation(FieldError error) {
        return new ApiError.FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private ApiError.FieldViolation toFieldViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        // Keep only the parameter name, dropping the method prefix the
        // validator adds ("login.arg0" -> "arg0" is still useless, so the last
        // segment is the best available label).
        int lastDot = path.lastIndexOf('.');
        String field = (lastDot >= 0) ? path.substring(lastDot + 1) : path;
        return new ApiError.FieldViolation(field, violation.getMessage());
    }
}
