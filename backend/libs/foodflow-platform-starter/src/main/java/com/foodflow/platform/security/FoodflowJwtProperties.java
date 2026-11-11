package com.foodflow.platform.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Resource-server settings shared by all services ({@code foodflow.security.jwt.*}).
 *
 * @param issuer     expected {@code iss} claim (must match the user-service issuer exactly)
 * @param audience   expected value in the {@code aud} claim
 * @param jwkSetUri  JWKS endpoint used to verify signatures; optional for the issuer itself,
 *                   which verifies with its local keys
 * @param rolesClaim claim holding the role names (mapped to {@code ROLE_*} authorities)
 */
@Validated
@ConfigurationProperties(prefix = "foodflow.security.jwt")
public record FoodflowJwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        String jwkSetUri,
        String rolesClaim) {

    public static final String DEFAULT_ROLES_CLAIM = "roles";

    public FoodflowJwtProperties {
        if (rolesClaim == null || rolesClaim.isBlank()) {
            rolesClaim = DEFAULT_ROLES_CLAIM;
        }
    }
}
