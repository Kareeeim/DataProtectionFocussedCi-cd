package com.demobank.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when the source account cannot cover the requested amount.
 *
 * <p>Mapped to {@code 422 Unprocessable Content} rather than {@code 400}: the
 * request is perfectly well formed and passed every validation rule, it simply
 * cannot be executed against the current state of the data. Keeping the two
 * apart lets the client distinguish "you sent something wrong, fix the form"
 * from "the operation was refused, show the reason".</p>
 *
 * <p>Raising this exception aborts the surrounding transaction, so neither
 * balance is touched and no movement is written.</p>
 */
public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", message);
    }
}
