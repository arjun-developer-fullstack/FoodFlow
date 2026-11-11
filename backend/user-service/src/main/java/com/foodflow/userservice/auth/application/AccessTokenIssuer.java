package com.foodflow.userservice.auth.application;

import com.foodflow.userservice.user.domain.User;

import java.time.Instant;

/** Outbound port: mints signed access tokens (JWT) for a user. */
public interface AccessTokenIssuer {

    IssuedAccessToken issue(User user);

    record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
