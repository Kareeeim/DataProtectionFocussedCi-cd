package com.demobank.web;

import com.demobank.dto.AccountResponse;
import com.demobank.dto.ApiError;
import com.demobank.dto.MovementResponse;
import com.demobank.security.AuthenticatedUser;
import com.demobank.service.AccountService;
import com.demobank.service.MovementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints for accounts and their statements.
 *
 * <p>Notice what is <em>absent</em> from every signature: there is no user id
 * parameter. The identity comes from {@code @AuthenticationPrincipal}, which is
 * populated from the verified token by the JWT filter. A client cannot ask for
 * another customer's data even by crafting the URL, because there is no field
 * in the request that names a customer.</p>
 *
 * <p>{@code @PreAuthorize("isAuthenticated()")} repeats at method level what the
 * filter chain already enforces at URL level. That redundancy is intentional:
 * if somebody later edits a URL rule in {@code SecurityConfig}, the methods
 * underneath do not silently become public.</p>
 */
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Accounts", description = "Accounts and statements of the authenticated customer")
public class AccountController {

    private final AccountService accountService;
    private final MovementService movementService;

    public AccountController(AccountService accountService, MovementService movementService) {
        this.accountService = accountService;
        this.movementService = movementService;
    }

    /** Lists the accounts owned by the caller. */
    @GetMapping
    @Operation(summary = "List the accounts of the authenticated customer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accounts, possibly an empty list"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<AccountResponse>> listAccounts(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(accountService.listOwnedAccounts(caller.id()));
    }

    /**
     * Returns one account of the caller.
     *
     * <p>An account belonging to another customer produces {@code 404}, exactly
     * like an id that does not exist, so the response cannot be used to probe
     * which account ids are real.</p>
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get one of the authenticated customer's accounts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
            @ApiResponse(responseCode = "404", description = "No such account for this customer",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(accountService.getOwnedAccount(id, caller.id()));
    }

    /**
     * Statement of one account, newest first.
     *
     * @param from  optional inclusive start date, ISO-8601 ({@code 2026-01-31})
     * @param to    optional inclusive end date; both bounds are included because
     *              users think in calendar days
     * @param limit optional maximum number of rows (default 200, capped at 500)
     */
    @GetMapping("/{id}/movements")
    @Operation(summary = "List the movements of an account, newest first",
            description = "Optional ISO-8601 date filters. Both bounds are inclusive.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movements, possibly an empty list"),
            @ApiResponse(responseCode = "400", description = "Invalid date range or parameter format",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
            @ApiResponse(responseCode = "404", description = "No such account for this customer",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<MovementResponse>> listMovements(
            @PathVariable Long id,
            @Parameter(description = "Inclusive start date (yyyy-MM-dd)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive end date (yyyy-MM-dd)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Maximum rows to return (default 200, max 500)")
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(
                movementService.listMovements(id, caller.id(), from, to, limit));
    }
}
