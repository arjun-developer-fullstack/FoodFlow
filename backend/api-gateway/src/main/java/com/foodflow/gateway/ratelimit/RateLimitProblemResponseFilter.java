package com.foodflow.gateway.ratelimit;

import com.foodflow.gateway.security.ProblemJsonWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Turns the rate limiter's bare 429 (empty body) into the same RFC 9457 problem the services return,
 * plus {@code Retry-After}.
 *
 * <p>Spring Cloud Gateway's {@code RequestRateLimiter} sets the status and completes the response without a
 * body. This filter wraps the response for every routed request and, only when a 429 is completed without a
 * body, writes {@code application/problem+json} with code {@code RATE_LIMITED}. The {@code X-RateLimit-*}
 * headers added by the limiter are kept.
 *
 * <p>{@code Retry-After} is the time to earn the requested tokens back at the bucket's replenish rate,
 * rounded up to whole seconds (at least 1), so a well-behaved client succeeds on its first retry.
 */
@Component
public class RateLimitProblemResponseFilter implements GlobalFilter, Ordered {

    static final String CODE = "RATE_LIMITED";
    static final String DETAIL = "Too many requests. Retry after the number of seconds in the Retry-After header.";

    private final ProblemJsonWriter problems;

    public RateLimitProblemResponseFilter(ProblemJsonWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode()) && !isCommitted()) {
                    getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(getHeaders())));
                    // Writes to the undecorated response, so this override is not re-entered.
                    return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, CODE, DETAIL);
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }

    /** Runs before the route's rate limiter so it can decorate the response the limiter completes. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static long retryAfterSeconds(HttpHeaders headers) {
        double replenishPerSecond = number(headers.getFirst(RedisRateLimiter.REPLENISH_RATE_HEADER), 0);
        double requestedTokens = number(headers.getFirst(RedisRateLimiter.REQUESTED_TOKENS_HEADER), 1);
        if (replenishPerSecond <= 0) {
            return 1;
        }
        return Math.max(1, (long) Math.ceil(requestedTokens / replenishPerSecond));
    }

    private static double number(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
