package com.demobank.dto;

import com.demobank.domain.Transfer;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Result of a simulated transfer.
 *
 * <p>Returns the state of <strong>both</strong> accounts after the operation, so
 * the UI can refresh the dashboard without a second round trip and the user can
 * immediately check that the money left one account and arrived in the
 * other.</p>
 *
 * @param reference        public UUID of the operation, to be quoted in any
 *                         support request instead of an internal id
 * @param executedAt       moment the transfer was booked
 * @param amount           transferred amount, as a decimal string
 * @param currency         currency of the operation
 * @param description      free-text label, echoed back as stored
 * @param status           always {@code COMPLETED} today: a refused transfer
 *                         throws and leaves no row behind
 * @param sourceAccount    source account after the debit
 * @param destinationAccount destination account after the credit
 */
public record TransferResponse(
        String reference,
        Instant executedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String description,
        String status,
        AccountResponse sourceAccount,
        AccountResponse destinationAccount) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getReference(),
                transfer.getExecutedAt(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getDescription(),
                transfer.getStatus().name(),
                AccountResponse.from(transfer.getSourceAccount()),
                AccountResponse.from(transfer.getDestinationAccount()));
    }
}
