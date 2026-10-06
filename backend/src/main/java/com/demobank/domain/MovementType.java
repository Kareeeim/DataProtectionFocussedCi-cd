package com.demobank.domain;

/**
 * Direction of a {@link Movement} relative to the account that holds it.
 *
 * <p>The stored {@code amount} is always a positive magnitude; the direction is
 * carried by this type alone. Storing signed amounts as well would create two
 * sources of truth that can disagree.</p>
 */
public enum MovementType {

    /** Money leaving the account (balance decreases). */
    DEBIT,

    /** Money entering the account (balance increases). */
    CREDIT
}
