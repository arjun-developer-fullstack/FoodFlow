package com.foodflow.userservice.user.application;

import com.foodflow.platform.error.ResourceNotFoundException;
import com.foodflow.userservice.support.InMemoryUserRepository;
import com.foodflow.userservice.support.MutableClock;
import com.foodflow.userservice.support.TestUsers;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;
import com.foodflow.userservice.user.domain.UserExceptions.SelfStatusChangeException;
import com.foodflow.userservice.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminUserServiceTest {

    private MutableClock clock;
    private InMemoryUserRepository users;
    private List<UUID> revokedSessions;
    private AdminUserService service;
    private User admin;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt("2026-11-07T10:00:00Z");
        users = new InMemoryUserRepository();
        revokedSessions = new ArrayList<>();
        service = new AdminUserService(users, revokedSessions::add, clock);
        admin = users.save(TestUsers.withRoles("admin@foodflow.test", Instant.now(clock), Role.ADMIN));
    }

    @Test
    void suspendingAUserRevokesAllOfTheirSessions() {
        User customer = users.save(TestUsers.customer("jane@example.com", Instant.now(clock)));

        User updated = service.changeStatus(admin.getId(), customer.getId(), UserStatus.SUSPENDED);

        assertThat(updated.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(revokedSessions).containsExactly(customer.getId());
    }

    @Test
    void reactivationDoesNotTouchSessions() {
        User customer = users.save(TestUsers.customer("jane@example.com", Instant.now(clock)));
        customer.changeStatus(UserStatus.SUSPENDED, Instant.now(clock));

        service.changeStatus(admin.getId(), customer.getId(), UserStatus.ACTIVE);

        assertThat(customer.isActive()).isTrue();
        assertThat(revokedSessions).isEmpty();
    }

    @Test
    void adminsCannotChangeTheirOwnStatus() {
        assertThatThrownBy(() -> service.changeStatus(admin.getId(), admin.getId(), UserStatus.SUSPENDED))
                .isInstanceOf(SelfStatusChangeException.class);
    }

    @Test
    void unknownUserIsNotFound() {
        assertThatThrownBy(() -> service.changeStatus(admin.getId(), UUID.randomUUID(), UserStatus.ACTIVE))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void searchFiltersByStatusAndNormalizedEmailPrefixNewestFirst() {
        users.save(TestUsers.customer("jane@example.com", Instant.now(clock)));
        clock.advance(Duration.ofMinutes(1));
        users.save(TestUsers.customer("janet@example.com", Instant.now(clock)));
        clock.advance(Duration.ofMinutes(1));
        users.save(TestUsers.customer("bob@example.com", Instant.now(clock)));

        assertThat(service.search(UserStatus.ACTIVE, " JAN", 0, 10).getContent())
                .extracting(User::getEmail)
                .containsExactly("janet@example.com", "jane@example.com");
        assertThat(service.search(null, null, 0, 2).getTotalElements()).isEqualTo(4);
    }
}
