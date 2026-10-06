package com.demobank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A bank account belonging to exactly one {@link User}.
 *
 * <p>Money is represented with {@link BigDecimal}. {@code double} and
 * {@code float} are binary floating point types and cannot represent values
 * such as {@code 0.10} exactly; using them for money produces rounding errors
 * that accumulate over many operations. The matching column is
 * {@code DECIMAL(19,2)}.</p>
 *
 * <p>The {@link Version} field adds optimistic locking on top of the pessimistic
 * lock taken during a transfer (see {@code AccountRepository#findByIdForUpdate}):
 * two concurrent updates of the same row cannot silently overwrite each
 * other.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IBAN-like identifier of the demo account. Unique in the database. */
    @Column(name = "account_number", nullable = false, unique = true, length = 34)
    private String accountNumber;

    /**
     * Owner of the account. Loaded lazily: the row is only fetched when the
     * code actually needs the owner, which keeps the common statement query
     * from joining users on every call.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_accounts_owner"))
    private User owner;

    /** ISO-4217 alphabetic currency code, e.g. {@code EUR}. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Current balance, always with scale 2 and never negative. */
    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Account() {
    }

    public Account(String accountNumber, User owner, String currency, BigDecimal balance) {
        this.accountNumber = accountNumber;
        this.owner = owner;
        this.currency = currency;
        this.balance = balance;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- balance mutation --------------------------------------------------

    /**
     * Subtracts {@code amount} from the balance.
     *
     * <p>The check performed here duplicates the database CHECK constraint on
     * purpose: it produces a clear domain error before the SQL statement is
     * even issued, while the constraint still protects the data if this code is
     * ever bypassed. Defence in depth, not redundancy.</p>
     *
     * @param amount a strictly positive amount with scale 2
     * @throws IllegalArgumentException if the amount is not positive or the
     *                                  available balance is insufficient
     */
    public void debit(BigDecimal amount) {
        requirePositive(amount);
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient balance on account " + accountNumber);
        }
        this.balance = this.balance.subtract(amount);
    }

    /**
     * Adds {@code amount} to the balance.
     *
     * @param amount a strictly positive amount with scale 2
     * @throws IllegalArgumentException if the amount is not positive
     */
    public void credit(BigDecimal amount) {
        requirePositive(amount);
        this.balance = this.balance.add(amount);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be strictly positive");
        }
    }

    /** True when this account belongs to the given user id. */
    public boolean isOwnedBy(Long userId) {
        return userId != null && owner != null && userId.equals(owner.getId());
    }

    // --- accessors ---------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public User getOwner() {
        return owner;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Account account) || id == null) {
            return false;
        }
        return id.equals(account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Account{id=" + id + ", accountNumber='" + accountNumber
                + "', currency='" + currency + "', balance=" + balance + '}';
    }
}
