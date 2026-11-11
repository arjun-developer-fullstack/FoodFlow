package com.foodflow.userservice.auth.infrastructure.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Signing-key configuration ({@code foodflow.auth.keys.*}). Keys are never part of the codebase.
 *
 * @param privateKeyLocation         PEM (PKCS#8, RSA >= 2048 bit) of the active signing key, e.g.
 *                                   {@code file:/var/run/secrets/foodflow/jwt/private.pem}
 *                                   mounted from AWS Secrets Manager via External Secrets
 * @param previousPublicKeyLocations PEM (X.509 SubjectPublicKeyInfo) public keys of retired signing
 *                                   keys; still published in the JWKS until their tokens expire
 * @param allowEphemeral             local development only: generate a throw-away key at startup
 *                                   when no private key is configured
 */
@ConfigurationProperties(prefix = "foodflow.auth.keys")
public record JwtKeyProperties(
        String privateKeyLocation,
        @DefaultValue List<String> previousPublicKeyLocations,
        @DefaultValue("false") boolean allowEphemeral) {
}
