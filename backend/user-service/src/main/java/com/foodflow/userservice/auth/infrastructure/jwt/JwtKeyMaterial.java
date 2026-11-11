package com.foodflow.userservice.auth.infrastructure.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * RSA key material of the token issuer.
 *
 * <ul>
 *   <li>{@link #signingKey()}: the active private key; signs new access tokens.</li>
 *   <li>{@link #publicJwkSet()}: public halves of the active and all previous keys. Published at
 *       {@code /.well-known/jwks.json} and used for local verification. Key ids are RFC 7638
 *       thumbprints, so rotation needs no extra configuration: deploy a new private key and move
 *       the old public key to {@code previous-public-key-locations}.</li>
 * </ul>
 */
public final class JwtKeyMaterial {

    static final int MIN_KEY_SIZE_BITS = 2048;
    private static final Logger log = LoggerFactory.getLogger(JwtKeyMaterial.class);

    private final RSAKey signingKey;
    private final JWKSet publicJwkSet;

    JwtKeyMaterial(RSAKey signingKey, List<RSAKey> previousPublicKeys) {
        this.signingKey = signingKey;
        List<JWK> publicKeys = new ArrayList<>();
        publicKeys.add(signingKey.toPublicJWK());
        previousPublicKeys.stream()
                .filter(key -> !key.getKeyID().equals(signingKey.getKeyID()))
                .forEach(publicKeys::add);
        this.publicJwkSet = new JWKSet(publicKeys);
    }

    public static JwtKeyMaterial load(JwtKeyProperties properties, ResourceLoader resources) {
        RSAKey signingKey;
        if (hasText(properties.privateKeyLocation())) {
            RSAPrivateKey privateKey = read(resources, properties.privateKeyLocation(),
                    in -> RsaKeyConverters.pkcs8().convert(in));
            signingKey = toSigningKey(derivePublicKey(privateKey), privateKey);
            log.info("Loaded JWT signing key kid={}", signingKey.getKeyID());
        } else if (properties.allowEphemeral()) {
            signingKey = generateEphemeralKey();
            log.warn("Using an EPHEMERAL JWT signing key (kid={}). Tokens become invalid on restart and are not "
                    + "shared between replicas. Never enable foodflow.auth.keys.allow-ephemeral outside local "
                    + "development.", signingKey.getKeyID());
        } else {
            throw new IllegalStateException("No JWT signing key configured. Set foodflow.auth.keys.private-key-location "
                    + "(env JWT_PRIVATE_KEY_LOCATION) to a PKCS#8 PEM file, or enable allow-ephemeral for local use.");
        }

        List<RSAKey> previous = new ArrayList<>();
        for (String location : properties.previousPublicKeyLocations()) {
            if (hasText(location)) {
                RSAPublicKey publicKey = read(resources, location.trim(), in -> RsaKeyConverters.x509().convert(in));
                previous.add(toPublicKey(publicKey));
            }
        }
        return new JwtKeyMaterial(signingKey, previous);
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public JWKSet publicJwkSet() {
        return publicJwkSet;
    }

    // ------------------------------------------------------------------ helpers

    private static RSAKey toSigningKey(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        requireStrongKey(publicKey);
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot compute JWK thumbprint", e);
        }
    }

    private static RSAKey toPublicKey(RSAPublicKey publicKey) {
        requireStrongKey(publicKey);
        try {
            return new RSAKey.Builder(publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot compute JWK thumbprint", e);
        }
    }

    private static void requireStrongKey(RSAPublicKey key) {
        if (key.getModulus().bitLength() < MIN_KEY_SIZE_BITS) {
            throw new IllegalStateException("JWT RSA keys must be at least " + MIN_KEY_SIZE_BITS + " bits");
        }
    }

    private static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException("JWT private key must be an RSA CRT key (standard PKCS#8 RSA key)");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive RSA public key", e);
        }
    }

    private static RSAKey generateEphemeralKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_SIZE_BITS);
            KeyPair pair = generator.generateKeyPair();
            return toSigningKey((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate ephemeral RSA key", e);
        }
    }

    private static <T> T read(ResourceLoader resources, String location, KeyReader<T> reader) {
        Resource resource = resources.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("JWT key not found at " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            T key = reader.read(in);
            if (key == null) {
                throw new IllegalStateException("Cannot parse JWT key at " + location);
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JWT key at " + location, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid PEM key at " + location, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface KeyReader<T> {
        T read(InputStream in) throws IOException;
    }
}
