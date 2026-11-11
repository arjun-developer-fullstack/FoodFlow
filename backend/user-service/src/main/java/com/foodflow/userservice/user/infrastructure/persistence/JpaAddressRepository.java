package com.foodflow.userservice.user.infrastructure.persistence;

import com.foodflow.userservice.user.domain.Address;
import com.foodflow.userservice.user.domain.AddressRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data adapter for {@link AddressRepository}. */
public interface JpaAddressRepository extends JpaRepository<Address, UUID>, AddressRepository {

    @Override
    List<Address> findAllByUserId(UUID userId);

    @Override
    Optional<Address> findByIdAndUserId(UUID id, UUID userId);

    @Override
    long countByUserId(UUID userId);

    @Override
    default void clearDefaultExcept(UUID userId, UUID keepId, Instant now) {
        if (keepId == null) {
            clearAllDefaults(userId, now);
        } else {
            clearOtherDefaults(userId, keepId, now);
        }
    }

    @Modifying(flushAutomatically = true)
    @Query("update Address a set a.defaultAddress = false, a.updatedAt = :now "
            + "where a.userId = :userId and a.defaultAddress = true")
    void clearAllDefaults(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update Address a set a.defaultAddress = false, a.updatedAt = :now "
            + "where a.userId = :userId and a.defaultAddress = true and a.id <> :keepId")
    void clearOtherDefaults(@Param("userId") UUID userId, @Param("keepId") UUID keepId, @Param("now") Instant now);
}
