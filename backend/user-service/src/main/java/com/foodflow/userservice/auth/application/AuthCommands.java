package com.foodflow.userservice.auth.application;

import com.foodflow.userservice.user.domain.Role;

import java.time.Instant;
import java.util.UUID;

/** Inputs and outputs of {@link AuthService}. */
public final class AuthCommands {

    private AuthCommands() {
    }

    /** Self-service account types. ADMIN can never be self-assigned. */
    public enum AccountType {
        CUSTOMER(Role.CUSTOMER),
        RESTAURANT_OWNER(Role.RESTAURANT_OWNER),
        COURIER(Role.COURIER);

        private final Role role;

        AccountType(Role role) {
            this.role = role;
        }

        public Role role() {
            return role;
        }
    }

    public record RegisterCommand(String email, String password, String fullName, AccountType accountType) {
    }

    public record LoginCommand(String email, String password) {
    }

    /** Client metadata stored with a session for auditing ("active sessions" views, incident response). */
    public record ClientContext(String userAgent, String ipAddress) {

        private static final int MAX_USER_AGENT = 255;
        private static final int MAX_IP = 45;

        public ClientContext {
            userAgent = truncate(userAgent, MAX_USER_AGENT);
            ipAddress = truncate(ipAddress, MAX_IP);
        }

        private static String truncate(String value, int max) {
            if (value == null) {
                return null;
            }
            return value.length() <= max ? value : value.substring(0, max);
        }
    }

    /**
     * A successfully established session. The refresh token is returned to the web layer only to
     * be placed in an HttpOnly cookie; it is never logged or persisted in clear text.
     */
    public record AuthSession(UUID userId, String accessToken, Instant accessTokenExpiresAt,
                              String refreshToken, Instant refreshTokenExpiresAt) {

        @Override
        public String toString() {
            return "AuthSession[userId=" + userId + ", accessTokenExpiresAt=" + accessTokenExpiresAt
                    + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
        }
    }
}
