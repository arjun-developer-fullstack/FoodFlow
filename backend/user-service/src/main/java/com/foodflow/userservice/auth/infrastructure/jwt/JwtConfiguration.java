package com.foodflow.userservice.auth.infrastructure.jwt;

import com.foodflow.platform.security.FoodflowJwtProperties;
import com.foodflow.platform.security.JwtValidation;
import com.foodflow.userservice.auth.application.AccessTokenIssuer;
import com.foodflow.userservice.auth.application.AuthProperties;
import com.foodflow.userservice.auth.application.PublicKeySetProvider;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Clock;

/**
 * Issuer-side JWT wiring. The user service verifies tokens with its <em>local</em> public keys
 * (no HTTP round-trip to its own JWKS endpoint); this decoder replaces the platform starter's
 * JWKS-based default. The same issuer/audience validation rules apply as in every other service.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtKeyProperties.class, AuthProperties.class})
public class JwtConfiguration {

    @Bean
    JwtKeyMaterial jwtKeyMaterial(JwtKeyProperties properties, ResourceLoader resourceLoader) {
        return JwtKeyMaterial.load(properties, resourceLoader);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeyMaterial keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyMaterial keys, FoodflowJwtProperties jwtProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(keys.publicJwkSet()))
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidation.forPlatform(jwtProperties.issuer(), jwtProperties.audience()));
        return decoder;
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(JwtEncoder encoder, JwtKeyMaterial keys, FoodflowJwtProperties jwtProperties,
                                        AuthProperties authProperties, Clock clock) {
        return new JwtAccessTokenIssuer(encoder, keys.signingKey().getKeyID(), jwtProperties, authProperties, clock);
    }

    /** Public keys only: {@code toJSONObject(true)} strips every private parameter. */
    @Bean
    PublicKeySetProvider publicKeySetProvider(JwtKeyMaterial keys) {
        return () -> keys.publicJwkSet().toJSONObject(true);
    }
}
