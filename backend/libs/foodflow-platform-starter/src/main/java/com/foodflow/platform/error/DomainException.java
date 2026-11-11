package com.foodflow.platform.error;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Base class for all expected business failures.
 *
 * <p>Subclasses describe <em>what</em> went wrong with a stable, machine-readable {@link #errorCode()}
 * (e.g. {@code EMAIL_ALREADY_REGISTERED}) and an {@link ErrorCategory}. Messages must be safe to
 * show to API clients: never include secrets, credentials or other users' data.
 *
 * <p>Failures that clear up on their own (lockouts, rate limits, saturation) may state when a retry can
 * succeed; {@link GlobalExceptionHandler} returns it as a {@code Retry-After} header (RFC 9110 §10.2.3).
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCategory category;
    private final String errorCode;
    private final Duration retryAfter;

    protected DomainException(ErrorCategory category, String errorCode, String message) {
        this(category, errorCode, message, null);
    }

    protected DomainException(ErrorCategory category, String errorCode, String message, Duration retryAfter) {
        super(message);
        this.category = Objects.requireNonNull(category, "category");
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? null : retryAfter;
    }

    public ErrorCategory category() {
        return category;
    }

    public String errorCode() {
        return errorCode;
    }

    /** How long the client should wait before retrying, if known. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
