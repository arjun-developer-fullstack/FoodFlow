package com.foodflow.userservice.user.application;

import com.foodflow.userservice.support.InMemoryUserRepository;
import com.foodflow.userservice.support.MutableClock;
import com.foodflow.userservice.support.TestUsers;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.PhoneAlreadyInUseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserProfileServiceTest {

    private InMemoryUserRepository users;
    private UserProfileService service;
    private User jane;

    @BeforeEach
    void setUp() {
        MutableClock clock = MutableClock.startingAt("2026-11-07T10:00:00Z");
        users = new InMemoryUserRepository();
        service = new UserProfileService(users, clock);
        jane = users.save(TestUsers.customer("jane@example.com", Instant.now(clock)));
    }

    @Test
    void updatesOnlyProvidedFields() {
        service.updateProfile(jane.getId(), new UpdateProfileCommand(null, "+447700900123"));
        service.updateProfile(jane.getId(), new UpdateProfileCommand("Jane Q. Doe", null));

        assertThat(jane.getFullName()).isEqualTo("Jane Q. Doe");
        assertThat(jane.getPhone()).isEqualTo("+447700900123");
    }

    @Test
    void emptyPhoneRemovesIt() {
        service.updateProfile(jane.getId(), new UpdateProfileCommand(null, "+447700900123"));

        service.updateProfile(jane.getId(), new UpdateProfileCommand(null, ""));

        assertThat(jane.getPhone()).isNull();
    }

    @Test
    void phoneMustBeUniqueAcrossUsers() {
        User bob = users.save(TestUsers.customer("bob@example.com", Instant.now()));
        service.updateProfile(bob.getId(), new UpdateProfileCommand(null, "+447700900123"));

        assertThatThrownBy(() -> service.updateProfile(jane.getId(), new UpdateProfileCommand(null, "+447700900123")))
                .isInstanceOf(PhoneAlreadyInUseException.class);
    }
}
