package com.demobank.dto;

import com.demobank.domain.Movement;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of an account statement.
 *
 * <p>{@code amount} is always a positive magnitude and {@code type} carries the
 * direction, mirroring the storage model so that the client never has to guess
 * the sign convention.</p>
 *
 * @param id                movement identifier
 * @param executedAt        moment the movement was booked (UTC, ISO-8601)
 * @param description       human label shown in the statement
 * @param type              {@code DEBIT} (money out) or {@code CREDIT} (money in)
 * @param amount            positive magnitude, serialised as a string
 * @param balanceAfter      account balance right after this line, as a string
 * @param transferReference public UUID of the originating transfer, or
 *                          {@code null} for movements not produced by one
 */
public record MovementResponse(
        Long id,
        Instant executedAt,
        String description,
        String type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceAfter,
        String transferReference) {

    public static MovementResponse from(Movement movement) {
        return new MovementResponse(
                movement.getId(),
                movement.getExecutedAt(),
                movement.getDescription(),
                movement.getType().name(),
                movement.getAmount(),
                movement.getBalanceAfter(),
                movement.getTransfer() != null ? movement.getTransfer().getReference() : null);
    }
}
