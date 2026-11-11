package com.foodflow.gateway.ratelimit;

import com.foodflow.gateway.support.StubUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis-backed rate limiting of the sign-in endpoint. <b>Requires Docker</b> (skipped without it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RateLimitIT.RedisContainer.class)
@Testcontainers(disabledWithoutDocker = true)
class RateLimitIT {

    static final StubUserService userService = new StubUserService();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("USER_SERVICE_URL", userService::baseUrl);
        registry.add("CORS_ALLOWED_ORIGINS", () -> "https://app.foodflow.test");
        registry.add("JWT_ISSUER", () -> StubUserService.ISSUER);
        registry.add("REDIS_HOST", () -> "replaced-by-service-connection");
        registry.add("foodflow.gateway.rate-limit.auth.replenish-rate", () -> "1");
        registry.add("foodflow.gateway.rate-limit.auth.burst-capacity", () -> "3");
    }

    @AfterAll
    static void stopStub() {
        userService.close();
    }

    @LocalServerPort
    int port;

    @Test
    void signInIsLimitedPerClientIpAndRejectsTheExcessWith429() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            statuses.add(client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Forwarded-For", "198.51.100.77").bodyValue("{}")
                    .exchange().returnResult(Void.class).getStatus().value());
        }

        assertThat(statuses.subList(0, 3)).containsOnly(HttpStatus.OK.value());
        assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void theRejectionIsAProblemDetailWithRetryAfterAndKeepsTheRateLimitHeaders() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        for (int i = 0; i < 3; i++) { // burst capacity 3
            client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Forwarded-For", "198.51.100.88").bodyValue("{}")
                    .exchange().expectStatus().isOk();
        }

        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.51.100.88").bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1") // 1 token at 1 token/s
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectHeader().exists("X-RateLimit-Burst-Capacity")
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .jsonPath("$.type").isEqualTo("https://problems.foodflow.com/rate-limited")
                .jsonPath("$.instance").isEqualTo("/api/v1/auth/login")
                .jsonPath("$.correlationId").exists();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisContainer {

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:8.6-alpine").withExposedPorts(6379);
        }
    }
}
