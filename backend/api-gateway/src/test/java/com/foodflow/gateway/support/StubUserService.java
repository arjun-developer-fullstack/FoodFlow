package com.foodflow.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal stand-in for user-service: serves a JWKS for a test signing key and echoes every other
 * request, recording the headers the gateway forwarded. Uses only the JDK HTTP server (no Docker).
 */
public final class StubUserService implements AutoCloseable {

    public static final String ISSUER = "https://gateway.foodflow.test";
    public static final String AUDIENCE = "foodflow-api";

    private final HttpServer server;
    private final RSAKey signingKey;
    private final Map<String, Map<String, List<String>>> lastHeadersByPath = new ConcurrentHashMap<>();

    public StubUserService() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("stub-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, 200, jwks));
        server.createContext("/", exchange -> {
            lastHeadersByPath.put(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()));
            respond(exchange, 200, "{\"proxied\":\"" + exchange.getRequestURI().getPath() + "\"}");
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String header(String path, String name) {
        Map<String, List<String>> headers = lastHeadersByPath.get(path);
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().getFirst())
                .findFirst().orElse(null);
    }

    public boolean received(String path) {
        return lastHeadersByPath.containsKey(path);
    }

    public String token(String... roles) {
        return token(signingKey, AUDIENCE, ISSUER, Instant.now().plus(Duration.ofMinutes(15)), roles);
    }

    public String tokenForAudience(String audience) {
        return token(signingKey, audience, ISSUER, Instant.now().plus(Duration.ofMinutes(15)), "CUSTOMER");
    }

    public String expiredToken() {
        return token(signingKey, AUDIENCE, ISSUER, Instant.now().minus(Duration.ofMinutes(5)), "CUSTOMER");
    }

    public String tokenSignedByUnknownKey() {
        try {
            RSAKey other = new RSAKeyGenerator(2048).keyID("stub-key").generate();
            return token(other, AUDIENCE, ISSUER, Instant.now().plus(Duration.ofMinutes(15)), "CUSTOMER");
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(RSAKey key, String audience, String issuer, Instant expiresAt, String... roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(UUID.randomUUID().toString())
                    .audience(audience)
                    .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(15))))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("roles", List.of(roles))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
