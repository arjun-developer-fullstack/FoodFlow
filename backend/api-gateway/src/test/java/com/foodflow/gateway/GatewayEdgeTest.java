package com.foodflow.gateway;

import com.foodflow.gateway.support.StubUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real gateway (routing, security, CORS, correlation id) against a JDK-HTTP stub of
 * user-service. Rate limiting is disabled here because it needs Redis; see {@code RateLimitIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayEdgeTest {

    static final StubUserService userService = new StubUserService();
    static final String ALLOWED_ORIGIN = "https://app.foodflow.test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("USER_SERVICE_URL", userService::baseUrl);
        registry.add("CORS_ALLOWED_ORIGINS", () -> ALLOWED_ORIGIN);
        registry.add("JWT_ISSUER", () -> StubUserService.ISSUER);
        registry.add("REDIS_HOST", () -> "localhost");
        registry.add("foodflow.gateway.rate-limit.enabled", () -> "false");
    }

    @AfterAll
    static void stopStub() {
        userService.close();
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(20)).build();
    }

    @Nested
    class Routing {

        @Test
        void publicAuthEndpointsAreProxiedWithoutAToken() {
            client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists("X-Correlation-Id")
                    .expectBody().jsonPath("$.proxied").isEqualTo("/api/v1/auth/login");

            assertThat(userService.header("/api/v1/auth/login", "X-Correlation-Id")).isNotBlank();
            assertThat(userService.header("/api/v1/auth/login", "X-Forwarded-For")).isNotBlank();
        }

        @Test
        void jwksIsReachableThroughTheGateway() {
            client.get().uri("/.well-known/jwks.json").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.keys[0].kid").isEqualTo("stub-key");
        }

        @Test
        void authenticatedRequestsAreForwardedWithTheBearerToken() {
            String token = userService.token("CUSTOMER");

            client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();

            assertThat(userService.header("/api/v1/users/me", "Authorization")).isEqualTo("Bearer " + token);
        }

        @Test
        void sessionEndpointsAreRoutedAndUnlistedAuthPathsAreNot() {
            client.post().uri("/api/v1/auth/refresh").exchange().expectStatus().isOk();
            client.post().uri("/api/v1/auth/logout").exchange().expectStatus().isOk();
            client.post().uri("/api/v1/auth/something-else").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void oversizedBodiesAreRejectedAtTheEdgeWithoutReachingTheService() {
            String tooLarge = "{\"email\":\"" + "a".repeat(300 * 1024) + "\"}";

            client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(tooLarge.length()))
                    .bodyValue(tooLarge)
                    .exchange()
                    .expectStatus().isEqualTo(413);
        }

        @Test
        void wellFormedClientCorrelationIdsArePropagatedAndUnsafeOnesReplaced() {
            client.post().uri("/api/v1/auth/register").header("X-Correlation-Id", "client-request-0001")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectHeader().valueEquals("X-Correlation-Id", "client-request-0001");
            assertThat(userService.header("/api/v1/auth/register", "X-Correlation-Id")).isEqualTo("client-request-0001");

            client.post().uri("/api/v1/auth/register").header("X-Correlation-Id", "bad id with spaces")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectHeader().value("X-Correlation-Id", id -> assertThat(id).doesNotContain(" "));
        }
    }

    @Nested
    class EdgeAuthentication {

        @Test
        void missingTokenIs401ProblemWithBearerChallengeAndIsNotForwarded() {
            client.get().uri("/api/v1/users/me/addresses").exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                    .jsonPath("$.correlationId").exists();
            assertThat(userService.received("/api/v1/users/me/addresses")).isFalse();
        }

        @Test
        void expiredForgedAndWrongAudienceTokensAreRejected() {
            for (String token : new String[]{userService.expiredToken(), userService.tokenSignedByUnknownKey(),
                    userService.tokenForAudience("another-api"), "not.a.jwt"}) {
                client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .exchange()
                        .expectStatus().isUnauthorized();
            }
        }

        @Test
        void adminRoutesRequireTheAdminRole() {
            client.get().uri("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + userService.token("CUSTOMER"))
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");

            client.get().uri("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + userService.token("ADMIN"))
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        void internalEndpointsAreNeverExposed() {
            String token = userService.token("ADMIN");

            client.get().uri("/internal/carts/123").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isForbidden();
            client.post().uri("/api/v1/internal/menu/price-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isForbidden();
            client.get().uri("/internal/carts/123").exchange().expectStatus().isUnauthorized();
        }

        @Test
        void unknownPathsAreDenied() {
            client.get().uri("/does-not-exist").exchange().expectStatus().isUnauthorized();
        }
    }

    @Nested
    class Cors {

        @Test
        void preflightFromAnAllowedOriginSucceedsWithCredentials() {
            client.options().uri("/api/v1/auth/login")
                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }

        @Test
        void preflightFromAnUnknownOriginIsRejected() {
            client.options().uri("/api/v1/auth/login")
                    .header(HttpHeaders.ORIGIN, "https://evil.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }
    }
}
