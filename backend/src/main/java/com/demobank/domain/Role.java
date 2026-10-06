package com.demobank.domain;

/**
 * Authorisation roles known to the application.
 *
 * <p>The names are stored in the database as-is and are used verbatim as Spring
 * Security authorities, so they carry the {@code ROLE_} prefix required by
 * {@code hasRole(...)} expressions. Storing the prefixed form avoids a whole
 * class of "forgot to add the prefix" authorisation bugs.</p>
 */
public enum Role {

    /** Ordinary customer: may only ever see and move their own money. */
    ROLE_CUSTOMER,

    /** Reserved for support staff; no demo user is given this role. */
    ROLE_ADMIN
}
