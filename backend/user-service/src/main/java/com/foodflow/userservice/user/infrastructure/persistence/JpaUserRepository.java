package com.foodflow.userservice.user.infrastructure.persistence;

import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserRepository;
import com.foodflow.userservice.user.domain.UserStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data adapter for {@link UserRepository}. */
public interface JpaUserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User>, UserRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Override
    Optional<User> findByEmail(String email);

    @Override
    boolean existsByEmail(String email);

    @Override
    boolean existsByPhoneAndIdNot(String phone, UUID id);

    @Override
    default Page<User> search(UserStatus status, String emailPrefix, Pageable pageable) {
        Specification<User> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (emailPrefix != null) {
                predicates.add(cb.like(root.get("email"), escapeLike(emailPrefix) + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return findAll(filter, sorted);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
