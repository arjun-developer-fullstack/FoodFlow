package com.foodflow.userservice.user.domain;

import com.foodflow.platform.id.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final LockoutPolicy POLICY = new LockoutPolicy(3, Duration.ofMinutes(10));

    @Test
    void registrationNormalizesEmailAndStartsActive() {
        User user = User.register(UuidV7.generate(), " Jane@Example.COM ", " Jane ", "hash", Set.of(Role.CUSTOMER), NOW);

        assertThat(user.getEmail()).isEqualTo("jane@example.com");
        assertThat(user.getFullName()).isEqualTo("Jane");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.canAuthenticateAt(NOW)).isTrue();
    }

    @Test
    void requiresAtLeastOneRoleAndAName() {
        assertThatThrownBy(() -> User.register(UuidV7.generate(), "a@b.co", "A", "hash", Set.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.register(UuidV7.generate(), "a@b.co", "  ", "hash", Set.of(Role.CUSTOMER), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locksAfterThresholdAndUnlocksWhenTheLockExpires() {
        User user = newUser();

        assertThat(user.recordFailedLogin(NOW, POLICY)).isFalse();
        assertThat(user.recordFailedLogin(NOW, POLICY)).isFalse();
        assertThat(user.recordFailedLogin(NOW, POLICY)).isTrue();

        assertThat(user.isLockedAt(NOW.plus(Duration.ofMinutes(9)))).isTrue();
        assertThat(user.canAuthenticateAt(NOW.plus(Duration.ofMinutes(9)))).isFalse();
        assertThat(user.isLockedAt(NOW.plus(Duration.ofMinutes(10)))).isFalse();
    }

    @Test
    void failuresAfterAnExpiredLockStartCountingAgain() {
        User user = newUser();
        for (int i = 0; i < 3; i++) {
            user.recordFailedLogin(NOW, POLICY);
        }
        Instant later = NOW.plus(Duration.ofHours(1));

        assertThat(user.recordFailedLogin(later, POLICY)).isFalse();
        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.isLockedAt(later)).isFalse();
    }

    @Test
    void successfulLoginResetsTheFailureCounter() {
        User user = newUser();
        user.recordFailedLogin(NOW, POLICY);

        user.recordSuccessfulLogin(NOW);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void suspensionBlocksAuthenticationAndReactivationClearsLockout() {
        User user = newUser();
        for (int i = 0; i < 3; i++) {
            user.recordFailedLogin(NOW, POLICY);
        }

        user.changeStatus(UserStatus.SUSPENDED, NOW);
        assertThat(user.canAuthenticateAt(NOW.plus(Duration.ofDays(1)))).isFalse();

        user.changeStatus(UserStatus.ACTIVE, NOW);
        assertThat(user.canAuthenticateAt(NOW)).isTrue();
    }

    @Test
    void rolesAreExposedReadOnly() {
        User user = newUser();

        assertThatThrownBy(() -> user.getRoles().add(Role.ADMIN)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static User newUser() {
        return User.register(UuidV7.generate(), "jane@example.com", "Jane", "hash", Set.of(Role.CUSTOMER), NOW);
    }
}
