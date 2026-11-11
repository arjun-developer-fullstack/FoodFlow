package com.foodflow.userservice.user.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * User aggregate root: identity, credentials state and profile.
 * All state changes go through intention-revealing methods that enforce invariants.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Getter(AccessLevel.NONE)
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @Getter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Set<Role> roles = new HashSet<>();

    private User(UUID id, String email, String fullName, String passwordHash, Set<Role> roles, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = EmailAddress.normalize(email);
        this.fullName = requireName(fullName);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("A user needs at least one role");
        }
        this.roles = EnumSet.copyOf(roles);
        this.status = UserStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static User register(UUID id, String email, String fullName, String passwordHash, Set<Role> roles,
                                Instant now) {
        return new User(id, email, fullName, passwordHash, roles, now);
    }

    // ------------------------------------------------------------------ authentication

    public String passwordHash() {
        return passwordHash;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** True when the user may obtain or refresh tokens right now. */
    public boolean canAuthenticateAt(Instant now) {
        return isActive() && !isLockedAt(now);
    }

    /**
     * Records a failed password attempt; locks the account once the policy threshold is reached.
     *
     * @return true if this attempt caused the account to become locked
     */
    public boolean recordFailedLogin(Instant now, LockoutPolicy policy) {
        if (lockedUntil != null && !now.isBefore(lockedUntil)) {
            // previous lock expired: start counting afresh
            failedLoginAttempts = 0;
            lockedUntil = null;
        }
        failedLoginAttempts++;
        updatedAt = now;
        if (failedLoginAttempts >= policy.maxFailedAttempts()) {
            lockedUntil = now.plus(policy.lockDuration());
            failedLoginAttempts = 0;
            return true;
        }
        return false;
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    /** Replaces the hash, e.g. when the password encoder's parameters were strengthened. */
    public void rehashPassword(String newPasswordHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash");
        this.updatedAt = now;
    }

    // ------------------------------------------------------------------ profile

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public void changeFullName(String newFullName, Instant now) {
        this.fullName = requireName(newFullName);
        this.updatedAt = now;
    }

    /** @param newPhone E.164 number, or {@code null} to remove it */
    public void changePhone(String newPhone, Instant now) {
        this.phone = newPhone;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------------ administration

    public void changeStatus(UserStatus newStatus, Instant now) {
        this.status = Objects.requireNonNull(newStatus, "newStatus");
        if (newStatus == UserStatus.ACTIVE) {
            // re-activation also clears a pending lockout
            this.failedLoginAttempts = 0;
            this.lockedUntil = null;
        }
        this.updatedAt = now;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("fullName must not be blank");
        }
        return name.trim();
    }
}
