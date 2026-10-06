package com.demobank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Audit record of one simulated transfer between two accounts of the same
 * customer.
 *
 * <p>Why keep this table at all, when both legs are already visible in
 * {@code movements}? Because it is the immutable record of the <em>operation</em>
 * as opposed to the <em>consequences</em>: it stores who asked for it
 * ({@link #initiatedBy}), when, and which two accounts were involved. An audit
 * trail that can be reconstructed only by correlating statement lines is not an
 * audit trail.</p>
 *
 * <p>The client never sees the internal {@link #id}: the API exposes
 * {@link #reference}, a random UUID. Sequential identifiers leak business
 * volume and invite enumeration.</p>
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public, non-guessable identifier handed back to the client. */
    @Column(name = "reference", nullable = false, unique = true, length = 36)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_transfers_source"))
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_transfers_destination"))
    private Account destinationAccount;

    /** The authenticated user who requested the transfer. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false,
            foreignKey = @ForeignKey(name = "fk_transfers_user"))
    private User initiatedBy;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Currency of the transfer; both accounts must share it. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 140)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    /** Required by JPA. */
    protected Transfer() {
    }

    /**
     * Creates a completed transfer.
     *
     * <p>The public {@link #reference} is a random UUID rather than a sequence,
     * and {@code executedAt} is supplied by the caller so that the transfer and
     * both of its movements share one timestamp.</p>
     */
    public Transfer(Account sourceAccount, Account destinationAccount, User initiatedBy,
                    BigDecimal amount, String description, Instant executedAt) {
        this.reference = UUID.randomUUID().toString();
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.initiatedBy = initiatedBy;
        this.amount = amount;
        this.currency = sourceAccount.getCurrency();
        this.description = description;
        this.status = TransferStatus.COMPLETED;
        this.executedAt = executedAt;
    }

    @PrePersist
    void onCreate() {
        if (this.executedAt == null) {
            this.executedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Account getSourceAccount() {
        return sourceAccount;
    }

    public Account getDestinationAccount() {
        return destinationAccount;
    }

    public User getInitiatedBy() {
        return initiatedBy;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transfer transfer) || id == null) {
            return false;
        }
        return id.equals(transfer.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Transfer{reference='" + reference + "', amount=" + amount
                + ", currency='" + currency + "', status=" + status + '}';
    }
}
