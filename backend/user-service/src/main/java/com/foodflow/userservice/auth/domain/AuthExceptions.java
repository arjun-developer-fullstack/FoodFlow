package com.foodflow.userservice.auth.domain;

import com.foodflow.platform.error.DomainException;
import com.foodflow.platform.error.ErrorCategory;

import java.time.Duration;

/**
 * Authentication failures. Messages are intentionally generic: they never reveal whether an
 * e-mail exists, nor why exactly a refresh token was rejected.
 */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    public static final class InvalidCredentialsException extends DomainException {
        public InvalidCredentialsException() {
            super(ErrorCategory.UNAUTHENTICATED, "INVALID_CREDENTIALS", "Invalid e-mail or password.");
        }
    }

    public static final class AccountLockedException extends DomainException {
        public AccountLockedException() {
            this(null);
        }

        /** @param remaining time until the lock expires; returned to the client as {@code Retry-After} */
        public AccountLockedException(Duration remaining) {
            super(ErrorCategory.LOCKED, "ACCOUNT_LOCKED",
                    "The account is temporarily locked after repeated failed sign-in attempts. Try again later.",
                    remaining);
        }
    }

    public static final class AccountSuspendedException extends DomainException {
        public AccountSuspendedException() {
            super(ErrorCategory.FORBIDDEN, "ACCOUNT_SUSPENDED", "The account is suspended. Contact support.");
        }
    }

    public static final class AuthenticationBusyException extends DomainException {
        public AuthenticationBusyException() {
            super(ErrorCategory.UNAVAILABLE, "AUTHENTICATION_BUSY",
                    "Sign-in is temporarily busy. Please try again in a moment.");
        }
    }

    public static final class InvalidRefreshTokenException extends DomainException {

        private final boolean supersededWithinGrace;

        public InvalidRefreshTokenException() {
            this(false);
        }

        private InvalidRefreshTokenException(boolean supersededWithinGrace) {
            super(ErrorCategory.UNAUTHENTICATED, "INVALID_REFRESH_TOKEN",
                    "The session has expired or was revoked. Sign in again.");
            this.supersededWithinGrace = supersededWithinGrace;
        }

        /**
         * The token was rotated moments ago (within the reuse grace window): a benign race such as a retried
         * request or a second tab. The session is intact and the client may already hold the successor
         * token, so the response must not clear the refresh cookie. Same code and message as any other
         * rejection, so the response reveals nothing about the token's state.
         */
        public static InvalidRefreshTokenException supersededWithinGrace() {
            return new InvalidRefreshTokenException(true);
        }

        /** Whether the refresh cookie must be left untouched (see {@link #supersededWithinGrace()}). */
        public boolean keepsCookie() {
            return supersededWithinGrace;
        }
    }
}
