package com.foodflow.userservice.user.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Address}. */
public interface AddressRepository {

    Address save(Address address);

    void delete(Address address);

    List<Address> findAllByUserId(UUID userId);

    Optional<Address> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    /**
     * Clears the default flag on every address of the user except {@code keepId}
     * ({@code null} clears all). Executes immediately, before pending entity changes are flushed.
     */
    void clearDefaultExcept(UUID userId, UUID keepId, Instant now);
}
