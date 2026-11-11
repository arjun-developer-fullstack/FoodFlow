package com.foodflow.userservice.auth.infrastructure.persistence;

import com.foodflow.userservice.auth.domain.RefreshToken;
import com.foodflow.userservice.auth.domain.RefreshTokenRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data adapter for {@link RefreshTokenRepository} (PostgreSQL). */
public interface JpaRefreshTokenRepository extends JpaRepository<RefreshToken, UUID>, RefreshTokenRepository {

    /** Advisory-lock namespace for refresh-token families (two-key form; separate from one-key locks). */
    int FAMILY_LOCK_NAMESPACE = 7_001;

    @Override
    @Query("select t.familyId from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Override
    default void lockFamily(UUID familyId) {
        acquireFamilyLock(FAMILY_LOCK_NAMESPACE, familyId.toString());
    }

    /** Transaction-scoped; released automatically at commit or rollback. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, hashtext(:familyId))", nativeQuery = true)
    Integer acquireFamilyLock(@Param("namespace") int namespace, @Param("familyId") String familyId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
