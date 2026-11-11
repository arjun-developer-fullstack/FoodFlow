package com.foodflow.gateway.ratelimit;

import com.foodflow.gateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

/**
 * Distributed token-bucket rate limiting backed by Redis (shared by all gateway replicas).
 * Exceeding a limit returns 429 with {@code X-RateLimit-*} headers.
 *
 * <p>Buckets: {@code auth} (sign-in/sign-up, strict, per IP), {@code session} (refresh/sign-out,
 * lenient, per IP, so users behind a shared address are not logged out) and {@code api} (per user).
 * Note: Spring Cloud Gateway's Redis limiter fails open if Redis is unreachable; alert on it.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfiguration {

    static final String UNKNOWN_CLIENT = "unknown";

    @Bean
    RemoteAddressResolver clientAddressResolver(
            @Value("${spring.cloud.gateway.server.webflux.trusted-proxies:}") String trustedProxies,
            GatewayProperties properties) {
        return new TrustedProxyClientAddressResolver(trustedProxies, properties.clientIp().trustedHops());
    }

    @Bean
    KeyResolver clientIpKeyResolver(RemoteAddressResolver addresses) {
        return exchange -> Mono.just("ip:" + clientIp(addresses.resolve(exchange)));
    }

    /** Authenticated callers are limited per user id ({@code sub}), anonymous ones per IP. */
    @Bean
    @Primary
    KeyResolver principalOrIpKeyResolver(RemoteAddressResolver addresses) {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(addresses.resolve(exchange))));
    }

    @Bean
    @Primary
    RedisRateLimiter apiRateLimiter(GatewayProperties properties) {
        return limiter(properties.rateLimit().api());
    }

    @Bean
    RedisRateLimiter authRateLimiter(GatewayProperties properties) {
        return limiter(properties.rateLimit().auth());
    }

    @Bean
    RedisRateLimiter sessionRateLimiter(GatewayProperties properties) {
        return limiter(properties.rateLimit().session());
    }

    private static RedisRateLimiter limiter(GatewayProperties.Bucket bucket) {
        return new RedisRateLimiter(bucket.replenishRate(), bucket.burstCapacity(), 1);
    }

    static String clientIp(InetSocketAddress address) {
        if (address == null) {
            return UNKNOWN_CLIENT;
        }
        String host = address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
        return host == null || host.isBlank() ? UNKNOWN_CLIENT : host;
    }
}
