package com.foodflow.gateway.web;

import com.foodflow.gateway.security.ProblemJsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * Turns failures to reach a downstream service into RFC 9457 problem details, in the same shape
 * as every other FoodFlow error (stable {@code code}, {@code correlationId}):
 * <ul>
 *   <li><b>503 {@code SERVICE_UNAVAILABLE}</b> (with {@code Retry-After}) when the service cannot be
 *       reached: connection refused, connect timeout, unknown host, or no available instance.</li>
 *   <li><b>504 {@code GATEWAY_TIMEOUT}</b> when the service accepted the request but did not answer
 *       within the configured response timeout. No {@code Retry-After}: the request may have been
 *       processed, so clients must not blindly retry non-idempotent calls.</li>
 * </ul>
 * Responses never reveal internal host names, ports or exception messages; those are logged.
 *
 * <p>Every other error is passed on unchanged to Spring Boot's default handler. This is
 * deliberately a plain {@link WebExceptionHandler}, not an {@code ErrorWebExceptionHandler} bean,
 * which would replace Boot's default handler for all errors. It runs just before it.
 */
@Component
public class DownstreamErrorHandler implements WebExceptionHandler, Ordered {

    /** Boot's DefaultErrorWebExceptionHandler runs at -1. */
    static final int ORDER = -2;
    static final String RETRY_AFTER_SECONDS = "5";

    private static final Logger log = LoggerFactory.getLogger(DownstreamErrorHandler.class);

    private final ProblemJsonWriter problems;

    public DownstreamErrorHandler(ProblemJsonWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error); // part of the downstream response was already sent
        }
        if (isUnreachable(error) || hasStatus(error, HttpStatus.SERVICE_UNAVAILABLE)) {
            log.warn("Downstream service unavailable for {} {}: {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), describe(error));
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "The service is temporarily unavailable. Please try again shortly.");
        }
        if (hasStatus(error, HttpStatus.GATEWAY_TIMEOUT)) {
            log.warn("Downstream service timed out for {} {}: {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), describe(error));
            return problems.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT",
                    "The service did not respond in time.");
        }
        return Mono.error(error);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** Connection-level failures anywhere in the cause chain (Netty's connect exceptions extend ConnectException). */
    private static boolean isUnreachable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStatus(Throwable error, HttpStatus status) {
        return error instanceof ResponseStatusException rse && rse.getStatusCode().value() == status.value();
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + root.getMessage();
    }
}
