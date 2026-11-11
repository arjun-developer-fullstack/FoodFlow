package com.foodflow.userservice.auth.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Token, lockout and password-hashing policy ({@code foodflow.auth.*}). Defaults follow ADR-0007.
 *
 * @param accessTokenTtl     JWT lifetime; keep short because access tokens cannot be recalled
 * @param refreshTokenTtl    lifetime of each rotating refresh token
 * @param refreshReuseGrace  window after a rotation in which re-presenting the consumed token is
 *                           treated as a benign race (two tabs, a retried request) rather than theft
 * @param lockout            failed-login lockout policy
 * @param passwordHashing    bulkhead for the password encoder
 */
@Validated
@ConfigurationProperties(prefix = "foodflow.auth")
public record AuthProperties(
        @NotNull @DefaultValue("15m") Duration accessTokenTtl,
        @NotNull @DefaultValue("14d") Duration refreshTokenTtl,
        @NotNull @DefaultValue("10s") Duration refreshReuseGrace,
        @Valid @NotNull @DefaultValue Lockout lockout,
        @Valid @NotNull @DefaultValue PasswordHashing passwordHashing) {

    public record Lockout(
            @Min(1) @Max(100) @DefaultValue("5") int maxFailedAttempts,
            @NotNull @DefaultValue("15m") Duration duration) {
    }

    /**
     * @param maxConcurrent  concurrent hash computations (each Argon2 run uses ~19 MiB);
     *                       0 = number of available processors
     * @param acquireTimeout how long a request waits for a slot before failing with 503
     */
    public record PasswordHashing(
            @Min(0) @Max(256) @DefaultValue("0") int maxConcurrent,
            @NotNull @DefaultValue("2s") Duration acquireTimeout) {

        public int effectiveMaxConcurrent() {
            return maxConcurrent > 0 ? maxConcurrent : Math.max(2, Runtime.getRuntime().availableProcessors());
        }
    }
}
