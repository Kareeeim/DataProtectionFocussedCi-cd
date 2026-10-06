package com.demobank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * An application user, i.e. a customer of the demo bank.
 *
 * <p>Security-relevant decisions in this class:</p>
 * <ul>
 *   <li>The password is held as a <strong>BCrypt hash</strong> only. The field
 *       is named {@code passwordHash} rather than {@code password} so that no
 *       future reader can mistake it for a plaintext secret.</li>
 *   <li>The class has no {@code toString()} that includes the hash: the hash
 *       would otherwise end up in logs the moment somebody logs a user
 *       object.</li>
 *   <li>There is no setter for {@code role} or {@code enabled} reachable from
 *       the web layer, so a client cannot promote itself.</li>
 * </ul>
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login identifier. Stored lowercase; the column is uniquely indexed. */
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt hash (60 characters). Never a plaintext password. */
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    /** Allows an account to be disabled without deleting its history. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected User() {
    }

    public User(String email, String passwordHash, String fullName, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = true;
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

    // --- accessors ---------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Changes the stored hash. Intentionally the only password-related mutator:
     * the hash is always produced by the encoder in the service layer.
     */
    public void changePasswordHash(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // --- identity ----------------------------------------------------------
    //
    // Entities are compared by primary key. Two transient (not yet persisted)
    // users are never equal, which keeps Set/Map semantics predictable.

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user) || id == null) {
            return false;
        }
        return id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /** Deliberately omits the password hash and every other sensitive field. */
    @Override
    public String toString() {
        return "User{id=" + id + ", email='" + email + "', role=" + role + '}';
    }
}
