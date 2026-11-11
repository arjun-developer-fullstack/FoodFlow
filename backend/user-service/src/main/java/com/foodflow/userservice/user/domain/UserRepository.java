package com.foodflow.userservice.user.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for the {@link User} aggregate (implemented in the infrastructure layer). */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID id);

    /** Loads the user with a pessimistic write lock to serialize concurrent changes to its data. */
    Optional<User> findByIdForUpdate(UUID id);

    /** @param normalizedEmail see {@link EmailAddress#normalize(String)} */
    Optional<User> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    boolean existsByPhoneAndIdNot(String phone, UUID excludedUserId);

    /** Admin search; both filters are optional. Sorted by creation time, newest first. */
    Page<User> search(UserStatus status, String emailPrefix, Pageable pageable);
}
