package com.foodflow.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtValidationTest {

    private static final String ISSUER = "https://auth.foodflow.test";
    private static final String AUDIENCE = "foodflow-api";

    private final OAuth2TokenValidator<Jwt> validator = JwtValidation.forPlatform(ISSUER, AUDIENCE);

    @Test
    void acceptsTokenWithMatchingIssuerAndAudience() {
        assertThat(validator.validate(jwt(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300))).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsTokenForAnotherAudience() {
        assertThat(validator.validate(jwt(ISSUER, List.of("other-api"), Instant.now().plusSeconds(300))).hasErrors())
                .isTrue();
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        assertThat(validator.validate(jwt("https://evil.test", List.of(AUDIENCE), Instant.now().plusSeconds(300)))
                .hasErrors()).isTrue();
    }

    @Test
    void rejectsExpiredTokenBeyondClockSkew() {
        assertThat(validator.validate(jwt(ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120))).hasErrors())
                .isTrue();
    }

    private static Jwt jwt(String issuer, List<String> audience, Instant expiresAt) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1")
                .issuer(issuer).audience(audience)
                .issuedAt(expiresAt.minusSeconds(900)).expiresAt(expiresAt)
                .build();
    }
}
