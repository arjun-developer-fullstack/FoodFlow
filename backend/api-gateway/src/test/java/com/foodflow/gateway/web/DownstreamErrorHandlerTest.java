package com.foodflow.gateway.web;

import com.foodflow.gateway.security.ProblemJsonWriter;
import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamErrorHandlerTest {

    private final DownstreamErrorHandler handler = new DownstreamErrorHandler(new ProblemJsonWriter(JsonMapper.builder().build()));

    @Test
    void runsBeforeSpringBootsDefaultErrorHandler() {
        assertThat(handler.getOrder()).isLessThan(-1);
    }

    @Test
    void connectionFailuresAnywhereInTheCauseChainBecome503Problems() {
        for (Throwable error : new Throwable[]{
                new ConnectException("Connection refused: localhost/127.0.0.1:8081"),
                new IllegalStateException("wrapped", new ConnectException("Connection refused")),
                new ConnectTimeoutException("connection timed out after 2000 ms: user-service:8081"),
                new UnknownHostException("user-service.foodflow.svc.cluster.local"),
                NotFoundException.create(false, "No instances available for user-service")}) {
            MockServerWebExchange exchange = exchange();

            StepVerifier.create(handler.handle(exchange, error)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
            String body = exchange.getResponse().getBodyAsString().block();
            assertThat(body).contains("\"code\":\"SERVICE_UNAVAILABLE\"", "\"status\":503",
                            "\"correlationId\":\"corr-123\"", "\"instance\":\"/api/v1/auth/login\"")
                    // never leak internal topology or exception messages
                    .doesNotContain("127.0.0.1", "8081", "user-service", "Connection refused");
        }
    }

    @Test
    void responseTimeoutsBecome504ProblemsWithoutRetryAfter() {
        MockServerWebExchange exchange = exchange();
        ResponseStatusException timeout = new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                "Response took longer than timeout: PT10S", new TimeoutException());

        StepVerifier.create(handler.handle(exchange, timeout)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"GATEWAY_TIMEOUT\"", "\"status\":504")
                .doesNotContain("PT10S");
    }

    @Test
    void everyOtherErrorIsLeftToTheDefaultHandlerUnchanged() {
        for (Throwable error : new Throwable[]{
                new IllegalStateException("bug"),
                new ResponseStatusException(HttpStatus.NOT_FOUND),
                new ResponseStatusException(HttpStatus.BAD_GATEWAY)}) {
            MockServerWebExchange exchange = exchange();

            StepVerifier.create(handler.handle(exchange, error)).verifyErrorMatches(e -> e == error);

            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void anAlreadyCommittedResponseIsNotTouched() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();
        ConnectException error = new ConnectException("Connection reset");

        StepVerifier.create(handler.handle(exchange, error)).verifyErrorMatches(e -> e == error);
    }

    private static MockServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login"));
        exchange.getAttributes().put(CorrelationIdWebFilter.ATTRIBUTE, "corr-123");
        return exchange;
    }
}
