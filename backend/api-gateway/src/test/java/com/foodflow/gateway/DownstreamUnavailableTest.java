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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real gateway with an unavailable downstream service: clients must get RFC 9457 problem
 * details (503 unreachable, 504 timeout), never a generic error body or internal details.
 */
class DownstreamUnavailableTest {

    static final String ALLOWED_ORIGIN = "https://app.foodflow.test";

    /** user-service is down: nothing listens on its port. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class ServiceUnreachable {

        static final int DEAD_PORT = freePort();
        static final StubUserService tokens = new StubUserService(); // only used to mint tokens

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registry.add("USER_SERVICE_URL", () -> "http://127.0.0.1:" + DEAD_PORT);
            registry.add("CORS_ALLOWED_ORIGINS", () -> ALLOWED_ORIGIN);
            registry.add("JWT_ISSUER", () -> StubUserService.ISSUER);
            registry.add("REDIS_HOST", () -> "localhost");
            registry.add("foodflow.gateway.rate-limit.enabled", () -> "false");
        }

        @AfterAll
        static void stopStub() {
            tokens.close();
        }

        @LocalServerPort
        int port;

        WebTestClient client;

        @BeforeEach
        void client() {
            client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(20)).build();
        }

        @Test
        void proxiedRequestIs503ProblemWithRetryAfterAndNoInternalDetails() {
            String body = new String(client.post().uri("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                    .expectHeader().exists("X-Correlation-Id")
                    .expectBody()
                    .jsonPath("$.type").isEqualTo("https://problems.foodflow.com/service-unavailable")
                    .jsonPath("$.status").isEqualTo(503)
                    .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                    .jsonPath("$.instance").isEqualTo("/api/v1/auth/login")
                    .jsonPath("$.correlationId").exists()
                    .returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);

            assertThat(body).doesNotContain("127.0.0.1", String.valueOf(DEAD_PORT), "refused", "Exception");
        }

        @Test
        void correlationIdInTheProblemMatchesTheResponseHeader() {
            client.post().uri("/api/v1/auth/register").header("X-Correlation-Id", "client-request-0503")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().valueEquals("X-Correlation-Id", "client-request-0503")
                    .expectBody().jsonPath("$.correlationId").isEqualTo("client-request-0503");
        }

        @Test
        void browsersCanReadTheProblemFromAnAllowedOrigin() {
            client.post().uri("/api/v1/auth/login").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);
        }

        @Test
        void jwksRouteIs503() {
            client.get().uri("/.well-known/jwks.json").exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
        }

        @Test
        void authenticatedRequestWhileSigningKeysCannotBeFetchedIs503NotA401() {
            // The JWKS lives on the unavailable user-service. A 401 here would make the web app
            // sign the user out because of an outage; 503 tells it to retry instead.
            client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token("CUSTOMER"))
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
        }

        @Test
        void edgeSecurityStillRunsFirst() {
            // No token: rejected at the edge, the dead service is never contacted
            client.get().uri("/api/v1/users/me").exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        }
    }

    /** user-service accepts the connection but does not answer within the response timeout. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "spring.cloud.gateway.server.webflux.httpclient.response-timeout=300ms")
    class ServiceTooSlow {

        static final HttpServer slowService = slowServer(Duration.ofSeconds(3));

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registry.add("USER_SERVICE_URL", () -> "http://127.0.0.1:" + slowService.getAddress().getPort());
            registry.add("CORS_ALLOWED_ORIGINS", () -> ALLOWED_ORIGIN);
            registry.add("JWT_ISSUER", () -> StubUserService.ISSUER);
            registry.add("REDIS_HOST", () -> "localhost");
            registry.add("foodflow.gateway.rate-limit.enabled", () -> "false");
        }

        @AfterAll
        static void stopStub() {
            slowService.stop(0);
        }

        @LocalServerPort
        int port;

        @Test
        void slowResponseIs504ProblemWithoutRetryAfter() {
            WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(20)).build()
                    .post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange()
                    .expectStatus().isEqualTo(504)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT")
                    .jsonPath("$.status").isEqualTo(504)
                    .jsonPath("$.correlationId").exists();
        }
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static HttpServer slowServer(Duration delay) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                try (exchange) {
                    Thread.sleep(delay);
                    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // the gateway gave up and closed the connection
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
