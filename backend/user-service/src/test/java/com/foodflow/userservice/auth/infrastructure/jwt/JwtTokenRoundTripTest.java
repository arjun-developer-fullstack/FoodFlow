package com.foodflow.userservice.auth.infrastructure.jwt;

import com.foodflow.platform.security.FoodflowJwtProperties;
import com.foodflow.userservice.auth.application.AccessTokenIssuer.IssuedAccessToken;
import com.foodflow.userservice.auth.application.AuthProperties;
import com.foodflow.userservice.support.TestUsers;
import com.foodflow.userservice.user.domain.Role;
import com.foodflow.userservice.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real RS256 signing and verification with the production wiring of {@link JwtConfiguration}. */
class JwtTokenRoundTripTest {

    private static final String ISSUER = "https://auth.foodflow.test";
    private static final String AUDIENCE = "foodflow-api";

    @TempDir
    Path keys;

    private final JwtConfiguration configuration = new JwtConfiguration();
    private final FoodflowJwtProperties jwtProperties = new FoodflowJwtProperties(ISSUER, AUDIENCE, null, null);
    private final AuthProperties authProperties = new AuthProperties(Duration.ofMinutes(15), Duration.ofDays(14),
            Duration.ofSeconds(10), new AuthProperties.Lockout(5, Duration.ofMinutes(15)),
            new AuthProperties.PasswordHashing(0, Duration.ofSeconds(2)));
    private User user;

    @BeforeEach
    void setUp() {
        user = TestUsers.withRoles("jane@example.com", Instant.now(), Role.CUSTOMER, Role.COURIER);
    }

    @Test
    void issuedTokenVerifiesAndCarriesTheExpectedClaims() {
        JwtKeyMaterial material = ephemeral();
        IssuedAccessToken token = issuer(material).issue(user);

        Jwt jwt = decoder(material).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("COURIER", "CUSTOMER");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256")
                .containsEntry("kid", material.signingKey().getKeyID());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "name");
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() {
        IssuedAccessToken foreign = issuer(ephemeral()).issue(user);

        assertThatThrownBy(() -> decoder(ephemeral()).decode(foreign.value())).isInstanceOf(JwtException.class);
    }

    @Test
    void tokensForAnotherAudienceOrIssuerAreRejected() {
        JwtKeyMaterial material = ephemeral();
        JwtAccessTokenIssuer otherAudience = new JwtAccessTokenIssuer(configuration.jwtEncoder(material),
                material.signingKey().getKeyID(), new FoodflowJwtProperties(ISSUER, "other-api", null, null),
                authProperties, Clock.systemUTC());
        JwtAccessTokenIssuer otherIssuer = new JwtAccessTokenIssuer(configuration.jwtEncoder(material),
                material.signingKey().getKeyID(), new FoodflowJwtProperties("https://evil.test", AUDIENCE, null, null),
                authProperties, Clock.systemUTC());

        assertThatThrownBy(() -> decoder(material).decode(otherAudience.issue(user).value()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder(material).decode(otherIssuer.issue(user).value()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokensAreRejected() {
        JwtKeyMaterial material = ephemeral();
        JwtAccessTokenIssuer pastIssuer = new JwtAccessTokenIssuer(configuration.jwtEncoder(material),
                material.signingKey().getKeyID(), jwtProperties, authProperties,
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), java.time.ZoneOffset.UTC));

        assertThatThrownBy(() -> decoder(material).decode(pastIssuer.issue(user).value()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void loadsPemKeysAndKeepsRetiredPublicKeysForVerificationDuringRotation() throws Exception {
        KeyPair oldPair = rsa(2048);
        KeyPair newPair = rsa(2048);
        Path oldPrivate = writePem("old-private.pem", "PRIVATE KEY", oldPair.getPrivate().getEncoded());
        Path oldPublic = writePem("old-public.pem", "PUBLIC KEY", oldPair.getPublic().getEncoded());
        Path newPrivate = writePem("new-private.pem", "PRIVATE KEY", newPair.getPrivate().getEncoded());

        JwtKeyMaterial before = load(new JwtKeyProperties(oldPrivate.toUri().toString(), List.of(), false));
        IssuedAccessToken issuedBeforeRotation = issuer(before).issue(user);

        JwtKeyMaterial after = load(new JwtKeyProperties(newPrivate.toUri().toString(),
                List.of(oldPublic.toUri().toString()), false));

        assertThat(after.publicJwkSet().getKeys()).hasSize(2);
        assertThat(decoder(after).decode(issuedBeforeRotation.value()).getSubject())
                .isEqualTo(user.getId().toString());
        assertThat(decoder(after).decode(issuer(after).issue(user).value()).getHeaders().get("kid"))
                .isEqualTo(after.signingKey().getKeyID());
    }

    @Test
    void publishedJwksContainsNoPrivateKeyMaterial() {
        Map<String, Object> jwks = configuration.publicKeySetProvider(ephemeral()).publicJwkSet();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> published = (List<Map<String, Object>>) jwks.get("keys");
        assertThat(published).hasSize(1);
        assertThat(published.getFirst()).containsKeys("kty", "kid", "n", "e", "alg", "use")
                .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    }

    @Test
    void refusesToStartWithoutAKeyUnlessEphemeralKeysAreExplicitlyAllowed() {
        assertThatThrownBy(() -> load(new JwtKeyProperties(null, List.of(), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_LOCATION");
    }

    @Test
    void rejectsWeakKeys() throws Exception {
        Path weak = writePem("weak.pem", "PRIVATE KEY", rsa(1024).getPrivate().getEncoded());

        assertThatThrownBy(() -> load(new JwtKeyProperties(weak.toUri().toString(), List.of(), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void reportsMissingKeyFiles() {
        assertThatThrownBy(() -> load(new JwtKeyProperties(keys.resolve("missing.pem").toUri().toString(),
                List.of(), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    // ------------------------------------------------------------------ helpers

    private JwtKeyMaterial ephemeral() {
        return load(new JwtKeyProperties(null, List.of(), true));
    }

    private static JwtKeyMaterial load(JwtKeyProperties properties) {
        return JwtKeyMaterial.load(properties, new DefaultResourceLoader());
    }

    private JwtAccessTokenIssuer issuer(JwtKeyMaterial material) {
        return (JwtAccessTokenIssuer) configuration.accessTokenIssuer(configuration.jwtEncoder(material), material,
                jwtProperties, authProperties, Clock.systemUTC());
    }

    private JwtDecoder decoder(JwtKeyMaterial material) {
        return configuration.jwtDecoder(material, jwtProperties);
    }

    private static KeyPair rsa(int bits) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private Path writePem(String fileName, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return Files.writeString(keys.resolve(fileName),
                "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }
}
