package com.demobank.dto;

import com.demobank.domain.Account;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/**
 * Public representation of an account.
 *
 * <p><strong>Money is serialised as a JSON string, not as a JSON number.</strong>
 * JavaScript numbers are IEEE-754 doubles: they cannot represent every decimal
 * exactly, and integers beyond 2^53 silently lose precision. An amount that
 * arrives as a number is therefore already at risk the moment it reaches the
 * browser, before any application code runs. Sending {@code "2540.75"} keeps
 * the exact value intact and forces the client to treat money as a decimal
 * rather than as a float.</p>
 *
 * <p>The id is exposed because the client needs it to build URLs, but every
 * endpoint that accepts one verifies ownership against the token before using
 * it.</p>
 */
public record AccountResponse(
        Long id,
        String accountNumber,
        String ownerName,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance) {

    /**
     * Maps an entity to its public shape.
     *
     * <p>The owner must have been fetched already (the repository queries use an
     * entity graph): {@code open-in-view} is disabled, so touching a lazy
     * association here would throw.</p>
     */
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getOwner().getFullName(),
                account.getCurrency(),
                account.getBalance());
    }
}
