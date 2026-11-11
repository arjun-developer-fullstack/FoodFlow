package com.foodflow.userservice.support;

import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserRepository;
import com.foodflow.userservice.user.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class InMemoryUserRepository implements UserRepository {

    private final Map<UUID, User> users = new LinkedHashMap<>();

    @Override
    public User save(User user) {
        users.put(user.getId(), user);
        return user;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    @Override
    public Optional<User> findByEmail(String normalizedEmail) {
        return users.values().stream().filter(u -> u.getEmail().equals(normalizedEmail)).findFirst();
    }

    @Override
    public boolean existsByEmail(String normalizedEmail) {
        return findByEmail(normalizedEmail).isPresent();
    }

    @Override
    public boolean existsByPhoneAndIdNot(String phone, UUID excludedUserId) {
        return users.values().stream()
                .anyMatch(u -> Objects.equals(u.getPhone(), phone) && !u.getId().equals(excludedUserId));
    }

    @Override
    public Page<User> search(UserStatus status, String emailPrefix, Pageable pageable) {
        List<User> matches = users.values().stream()
                .filter(u -> status == null || u.getStatus() == status)
                .filter(u -> emailPrefix == null || u.getEmail().startsWith(emailPrefix))
                .sorted(Comparator.comparing(User::getCreatedAt).reversed())
                .toList();
        int from = (int) Math.min(pageable.getOffset(), matches.size());
        int to = Math.min(from + pageable.getPageSize(), matches.size());
        return new PageImpl<>(matches.subList(from, to), pageable, matches.size());
    }
}
