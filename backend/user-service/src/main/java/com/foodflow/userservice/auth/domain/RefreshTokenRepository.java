package com.foodflow.userservice.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link RefreshToken}. */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    /** Family of a token, read without loading (and caching) the entity. */
    Optional<UUID> findFamilyIdByTokenHash(String tokenHash);

    /**
     * Serializes all work on one token family until the transaction ends: rotation, reuse
     * revocation and sign-out. Without it, a revocation racing a rotation could miss the successor
     * token that the rotation is about to commit.
     */
    void lockFamily(UUID familyId);

    /** Loads by hash with a pessimistic write lock. Call after {@link #lockFamily(UUID)}. */
    Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);

    /** @return number of tokens revoked */
    int revokeFamily(UUID familyId, Instant now);

    /** @return number of tokens revoked */
    int revokeAllForUser(UUID userId, Instant now);
}
