package com.foodflow.userservice.auth.infrastructure.jwt;

import com.foodflow.platform.security.FoodflowJwtProperties;
import com.foodflow.userservice.auth.application.AccessTokenIssuer;
import com.foodflow.userservice.auth.application.AuthProperties;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Mints RS256 access tokens.
 *
 * <p>Claims: {@code iss}, {@code sub} (user id), {@code aud}, {@code iat}, {@code nbf}, {@code exp},
 * {@code jti} and {@code roles}. No PII (e-mail, name) is placed in tokens. Future claims such as
 * {@code restaurantIds} (Phase 2) are added here.
 */
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final String keyId;
    private final FoodflowJwtProperties jwtProperties;
    private final AuthProperties authProperties;
    private final Clock clock;

    public JwtAccessTokenIssuer(JwtEncoder encoder, String keyId, FoodflowJwtProperties jwtProperties,
                                AuthProperties authProperties, Clock clock) {
        this.encoder = encoder;
        this.keyId = keyId;
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    @Override
    public IssuedAccessToken issue(User user) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(authProperties.accessTokenTtl());
        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getId().toString())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(ROLES_CLAIM, roles)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, expiresAt);
    }
}
