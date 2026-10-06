package com.demobank.web;

import com.demobank.dto.ApiError;
import com.demobank.dto.TransferRequest;
import com.demobank.dto.TransferResponse;
import com.demobank.security.AuthenticatedUser;
import com.demobank.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulated transfers.
 */
@RestController
@RequestMapping("/api/transfers")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Transfers", description = "Simulated transfers between accounts of the same customer")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Executes a transfer.
     *
     * <p>Returns {@code 201 Created}: the operation does not merely modify state
     * that already existed, it brings a new resource - the transfer, identified
     * by its {@code reference} - into existence. There is no {@code Location}
     * header because no endpoint retrieves a transfer by URL; the reference is
     * enough to correlate it with the statement lines it produced.</p>
     *
     * <p>The answer is deliberately uninformative about <em>why</em> an account
     * was refused: a caller who is not the owner receives {@code 404}, the same
     * as for an id that does not exist.</p>
     */
    @PostMapping
    @Operation(summary = "Create a simulated transfer",
            description = "Moves money between two distinct accounts owned by the authenticated "
                    + "customer. Both the balances and the two statement lines are written in a "
                    + "single database transaction: if any step fails, nothing is stored.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer completed"),
            @ApiResponse(responseCode = "400", description = "Validation failed (amount, same account, "
                    + "date/parameter format)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "An account does not exist or is not yours",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Insufficient funds",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TransferResponse> createTransfer(
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        TransferResponse response = transferService.execute(request, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
