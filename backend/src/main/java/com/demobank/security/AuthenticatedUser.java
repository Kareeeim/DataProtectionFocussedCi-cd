package com.demobank.security;

import com.demobank.domain.Role;
import java.security.Principal;

/**
 * The identity carried by a verified access token, and the only thing the
 * application trusts about "who is calling".
 *
 * <p>It is a plain immutable record, deliberately <strong>not</strong> a JPA
 * entity: nothing in a request can mutate it, and it holds no password hash.
 * Controllers receive it through
 * {@code @AuthenticationPrincipal AuthenticatedUser me}.</p>
 *
 * <p>The {@link #id()} is the value every ownership check is based on. It comes
 * from the signed token, never from a query parameter or request body - which is
 * precisely why a client cannot ask for somebody else's data by editing the
 * payload.</p>
 *
 * @param id       primary key of the user in the database
 * @param email    login address (also the value returned by {@link #getName()})
 * @param fullName display name, used by the UI header
 * @param role     authority granted to this identity
 */
public record AuthenticatedUser(Long id, String email, String fullName, Role role) implements Principal {

    @Override
    public String getName() {
        return email;
    }

    /** Spring Security authority string, e.g. {@code ROLE_CUSTOMER}. */
    public String authority() {
        return role.name();
    }
}
