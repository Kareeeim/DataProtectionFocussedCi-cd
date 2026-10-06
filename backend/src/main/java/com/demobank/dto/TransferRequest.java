package com.demobank.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/transfers}.
 *
 * <p>Every field is validated before the service layer is reached, so a
 * malformed request costs one cheap check instead of a database round trip:</p>
 *
 * <ul>
 *   <li>{@code @Positive} on the identifiers rejects {@code 0} and negative
 *       values, which can never be valid primary keys.</li>
 *   <li>{@code @DecimalMin("0.01")} enforces the "only positive amounts" rule
 *       from the specification. Zero is refused as well: a transfer of nothing
 *       is not a transfer.</li>
 *   <li>{@code @Digits(integer = 17, fraction = 2)} rejects amounts with more
 *       than two decimal places (there is no half cent) and amounts too large
 *       for the {@code DECIMAL(19,2)} column. Without it, such a value would
 *       only be caught by the database, producing a confusing 500 instead of a
 *       clear 400.</li>
 *   <li>{@code @Size(max = 140)} matches the column width, so an over-long
 *       description is a validation error rather than a truncation.</li>
 * </ul>
 *
 * <p>The user's identity is <strong>not</strong> a field of this record. The
 * server takes it from the verified token; accepting it from the body would let
 * a customer move somebody else's money by editing the JSON.</p>
 */
public record TransferRequest(

        @NotNull(message = "Source account is required")
        @Positive(message = "Source account id must be positive")
        Long sourceAccountId,

        @NotNull(message = "Destination account is required")
        @Positive(message = "Destination account id must be positive")
        Long destinationAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @DecimalMax(value = "99999999999999999.99", message = "Amount exceeds the maximum allowed value")
        @Digits(integer = 17, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Size(max = 140, message = "Description must not exceed 140 characters")
        String description) {
}
