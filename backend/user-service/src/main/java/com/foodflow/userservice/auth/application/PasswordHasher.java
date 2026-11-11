package com.foodflow.userservice.auth.application;

import com.foodflow.userservice.auth.domain.AuthExceptions.AuthenticationBusyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bulkhead around the (deliberately expensive) password encoder.
 *
 * <p>Each Argon2 computation costs ~19 MiB of memory and tens of milliseconds of CPU. With virtual
 * threads there is no natural cap on concurrent requests, so a burst of sign-ins could exhaust the
 * heap or starve every other endpoint. At most {@code maxConcurrent} hashes run at once; callers wait
 * up to {@code acquireTimeout} and are otherwise rejected with 503 (retryable).
 *
 * <p>Hashing must never run inside a database transaction: it would pin a pooled connection
 * for the whole computation.
 */
public class PasswordHasher {

    private final PasswordEncoder encoder;
    private final Semaphore permits;
    private final long acquireTimeoutNanos;

    public PasswordHasher(PasswordEncoder encoder, int maxConcurrent, Duration acquireTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        this.encoder = encoder;
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeoutNanos = acquireTimeout.toNanos();
    }

    public String hash(CharSequence rawPassword) {
        return withPermit(() -> encoder.encode(rawPassword));
    }

    public boolean matches(CharSequence rawPassword, String passwordHash) {
        return withPermit(() -> encoder.matches(rawPassword, passwordHash));
    }

    /** True when the hash uses outdated parameters and should be replaced after a successful sign-in. */
    public boolean needsUpgrade(String passwordHash) {
        return encoder.upgradeEncoding(passwordHash);
    }

    private <T> T withPermit(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthenticationBusyException();
        }
        if (!acquired) {
            throw new AuthenticationBusyException();
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
