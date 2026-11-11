package com.foodflow.userservice.user.application;

import com.foodflow.platform.error.ResourceNotFoundException;
import com.foodflow.userservice.user.domain.EmailAddress;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.SelfStatusChangeException;
import com.foodflow.userservice.user.domain.UserRepository;
import com.foodflow.userservice.user.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Administrative user management (requires role ADMIN, enforced in the web layer). */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository users;
    private final SessionRevoker sessions;
    private final Clock clock;

    public AdminUserService(UserRepository users, SessionRevoker sessions, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<User> search(UserStatus status, String emailPrefix, int page, int size) {
        String prefix = emailPrefix == null || emailPrefix.isBlank() ? null : EmailAddress.normalize(emailPrefix);
        return users.search(status, prefix, PageRequest.of(page, size));
    }

    @Transactional
    public User changeStatus(UUID actingAdminId, UUID userId, UserStatus newStatus) {
        if (actingAdminId.equals(userId)) {
            throw new SelfStatusChangeException();
        }
        if (newStatus == UserStatus.SUSPENDED) {
            // bulk revocation runs before the user is loaded, so no managed entity is detached by it
            sessions.revokeAllSessions(userId);
        }
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
        user.changeStatus(newStatus, Instant.now(clock));
        log.info("Admin {} changed status of user {} to {}", actingAdminId, userId, newStatus);
        return users.save(user);
    }
}
