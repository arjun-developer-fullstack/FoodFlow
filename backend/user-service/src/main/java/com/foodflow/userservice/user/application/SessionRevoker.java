package com.foodflow.userservice.user.application;

import java.util.UUID;

/**
 * Outbound port: terminates all sessions (refresh tokens) of a user. Implemented by the auth
 * feature, which keeps the user feature independent of authentication internals.
 */
public interface SessionRevoker {

    void revokeAllSessions(UUID userId);
}
