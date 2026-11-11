package com.foodflow.userservice.support;

import com.foodflow.platform.id.UuidV7;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;

import java.time.Instant;
import java.util.Set;

public final class TestUsers {

    public static final String PASSWORD = "correct horse battery staple";

    private TestUsers() {
    }

    /** A user whose password hash matches {@link #PASSWORD} under {@link FakePasswordEncoder}. */
    public static User customer(String email, Instant now) {
        return withRoles(email, now, Role.CUSTOMER);
    }

    public static User withRoles(String email, Instant now, Role... roles) {
        return User.register(UuidV7.generate(), email, "Test User", "hashed:" + PASSWORD, Set.of(roles), now);
    }
}
