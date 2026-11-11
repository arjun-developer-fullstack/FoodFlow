package com.foodflow.userservice.auth.infrastructure;

import com.foodflow.userservice.auth.domain.RefreshTokenRepository;
import com.foodflow.userservice.user.application.SessionRevoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Adapter: revokes all refresh tokens of a user. Already-issued access tokens stay valid until
 * they expire (max. access-token TTL); see the access-token denylist item in the roadmap.
 */
@Component
class RefreshTokenSessionRevoker implements SessionRevoker {

    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    RefreshTokenSessionRevoker(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Override
    public void revokeAllSessions(UUID userId) {
        int revoked = refreshTokens.revokeAllForUser(userId, Instant.now(clock));
        securityLog.info("Revoked {} sessions of user {}", revoked, userId);
    }
}
