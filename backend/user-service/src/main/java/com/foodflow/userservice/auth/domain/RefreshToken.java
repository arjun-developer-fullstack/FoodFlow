package com.foodflow.userservice.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A rotating refresh token (ADR-0007). Only the SHA-256 hash of the opaque token is stored.
 *
 * <p>All tokens issued from one login share a {@code familyId}. Each refresh consumes the
 * presented token ({@code revokedAt} + {@code replacedBy}) and issues a successor. Presenting a
 * consumed token again is treated as theft: the whole family is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Version
    private Long version;

    private RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt,
                         String userAgent, String ipAddress) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
    }

    public static RefreshToken issue(UUID id, UUID userId, UUID familyId, String tokenHash, Instant now,
                                     Instant expiresAt, String userAgent, String ipAddress) {
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        return new RefreshToken(id, userId, familyId, tokenHash, now, expiresAt, userAgent, ipAddress);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** A consumed token was rotated; presenting it again signals token theft. */
    public boolean wasRotated() {
        return replacedBy != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Revokes the token (sign-out, family revocation). Idempotent. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public void rotateTo(UUID successorId, Instant now) {
        if (isRevoked()) {
            throw new IllegalStateException("Cannot rotate a revoked refresh token");
        }
        this.replacedBy = Objects.requireNonNull(successorId, "successorId");
        this.revokedAt = now;
    }
}
