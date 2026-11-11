package com.foodflow.userservice.user.domain;

import java.time.Duration;

/**
 * Brute-force protection: after {@code maxFailedAttempts} consecutive failures the account is
 * locked for {@code lockDuration}. Complements per-IP rate limiting at the API gateway.
 */
public record LockoutPolicy(int maxFailedAttempts, Duration lockDuration) {

    public LockoutPolicy {
        if (maxFailedAttempts < 1) {
            throw new IllegalArgumentException("maxFailedAttempts must be >= 1");
        }
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("lockDuration must be positive");
        }
    }
}
