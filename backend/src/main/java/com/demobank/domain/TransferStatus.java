package com.demobank.domain;

/**
 * Lifecycle state of a {@link Transfer}.
 *
 * <p>Only completed transfers are persisted by the current implementation: a
 * refused transfer (invalid amount, missing account, insufficient funds) rolls
 * the whole transaction back and leaves no trace in this table, which is the
 * behaviour the task requires. {@link #REJECTED} exists so that a future
 * "pending / failed transfers" feature can record refusals deliberately rather
 * than as a side effect.</p>
 */
public enum TransferStatus {

    /** Both legs were written and both balances updated. */
    COMPLETED,

    /** Reserved for an explicit refusal record. Not written today. */
    REJECTED
}
