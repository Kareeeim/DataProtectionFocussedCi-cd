package com.demobank.dto;

import com.demobank.domain.User;

/**
 * Successful login payload.
 *
 * <p>Contains the access token and the minimum the UI needs to greet the user.
 * It deliberately does <strong>not</strong> echo the password hash, the
 * database id alone, or anything else that is not already in the token.</p>
 *
 * @param accessToken signed JWT the client must send as
 *                    {@code Authorization: Bearer <token>}
 * @param tokenType   always {@code Bearer}; sent so clients need not hardcode it
 * @param expiresIn   token lifetime in seconds, letting the client refresh or
 *                    log out before the token dies
 * @param user        identity summary for display
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummary user) {

    /** Public view of the authenticated user. Never carries the password hash. */
    public record UserSummary(Long id, String email, String fullName, String role) {

        public static UserSummary from(User user) {
            return new UserSummary(
                    user.getId(),
                    user.getEmail(),
                    user.getFullName(),
                    user.getRole().name());
        }
    }

    public static LoginResponse of(String accessToken, long expiresIn, User user) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, UserSummary.from(user));
    }
}
