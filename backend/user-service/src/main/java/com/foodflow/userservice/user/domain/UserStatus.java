package com.foodflow.userservice.user.domain;

/**
 * Administrative account status. Temporary lockout after failed logins is tracked separately
 * ({@code lockedUntil}) because it expires on its own.
 */
public enum UserStatus {
    /** May authenticate. */
    ACTIVE,
    /** Disabled by an administrator; all sessions are revoked. */
    SUSPENDED
}
