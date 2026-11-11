package com.foodflow.userservice.user.application;

import com.foodflow.platform.error.ResourceNotFoundException;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.PhoneAlreadyInUseException;
import com.foodflow.userservice.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use cases for the authenticated user's own profile. */
@Service
public class UserProfileService {

    private final UserRepository users;
    private final Clock clock;

    public UserProfileService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public User getProfile(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
    }

    @Transactional
    public User updateProfile(UUID userId, UpdateProfileCommand command) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
        Instant now = Instant.now(clock);

        if (command.fullName() != null) {
            user.changeFullName(command.fullName(), now);
        }
        if (command.phone() != null) {
            String phone = command.phone().isBlank() ? null : command.phone().trim();
            if (phone != null && users.existsByPhoneAndIdNot(phone, userId)) {
                throw new PhoneAlreadyInUseException();
            }
            user.changePhone(phone, now);
        }
        return users.save(user);
    }
}
