package com.demobank.web;

import com.demobank.dto.ApiError;
import com.demobank.dto.LoginRequest;
import com.demobank.dto.LoginResponse;
import com.demobank.security.AuthenticatedUser;
import com.demobank.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>{@code POST /api/auth/login} is the only public endpoint of the API: it has
 * to be, since it is what hands out the token that every other endpoint
 * requires. It is therefore also the only endpoint exposed to unauthenticated
 * traffic, and the one that carries the brute-force protection.</p>
 *
 * <p>There is no {@code /logout} endpoint on purpose. With a stateless token the
 * server holds nothing to invalidate: the client discards the token and it
 * becomes useless. A server-side logout would need a revocation list, which is
 * a real feature - and a real cost - for a problem this demo does not have.</p>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Obtain and inspect the access token")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Authenticates a customer and returns an access token.
     *
     * <p>{@code @Valid} triggers Bean Validation on the body before the service
     * is called, so a request that is not even well formed never reaches the
     * password comparison.</p>
     *
     * <p>The caller's IP is taken from the connection, not from a header: with
     * {@code forward-headers-strategy=framework} the container resolves it from
     * {@code X-Forwarded-For} only because the API is reachable exclusively
     * through the nginx proxy on the private Docker network. If the API were
     * ever exposed directly, a client could forge that header and evade the
     * per-IP throttle.</p>
     */
    @PostMapping("/login")
    @Operation(summary = "Authenticate with demo credentials",
            description = "Returns a signed JWT valid for the configured lifetime. "
                    + "Repeated failures from the same account or address are throttled with HTTP 429.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "400", description = "Malformed or invalid payload",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Too many failed attempts",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        LoginResponse response = authService.login(request, httpRequest.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the identity behind the presented token.
     *
     * <p>Lets a single-page application rehydrate its session after a page
     * reload, and gives the test suite a cheap way to assert which principal a
     * token resolves to.</p>
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Describe the authenticated user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current identity"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<LoginResponse.UserSummary> me(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(new LoginResponse.UserSummary(
                caller.id(), caller.email(), caller.fullName(), caller.role().name()));
    }
}
