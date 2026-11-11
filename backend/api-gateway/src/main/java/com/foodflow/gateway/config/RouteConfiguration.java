package com.foodflow.gateway.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Public routes. Only explicitly listed paths are routed; everything else, including every
 * {@code /internal/**} path, has no route and is additionally denied by the security chain.
 * Every route enforces the request-size limit. Phase 2+ services add their routes here.
 */
@Configuration(proxyBeanMethods = false)
public class RouteConfiguration {

    @Bean
    RouteLocator foodflowRoutes(RouteLocatorBuilder builder, GatewayProperties properties,
                                @Qualifier("authRateLimiter") RedisRateLimiter authRateLimiter,
                                @Qualifier("sessionRateLimiter") RedisRateLimiter sessionRateLimiter,
                                @Qualifier("apiRateLimiter") RedisRateLimiter apiRateLimiter,
                                @Qualifier("clientIpKeyResolver") KeyResolver clientIpKeyResolver,
                                @Qualifier("principalOrIpKeyResolver") KeyResolver principalOrIpKeyResolver) {
        String userService = properties.routes().userService().toString();
        RouteFilters filters = new RouteFilters(properties);

        return builder.routes()
                .route("user-service-sign-in", route -> route
                        .path("/api/v1/auth/login", "/api/v1/auth/register")
                        .filters(f -> filters.apply(f, authRateLimiter, clientIpKeyResolver))
                        .uri(userService))
                .route("user-service-session", route -> route
                        .path("/api/v1/auth/refresh", "/api/v1/auth/logout")
                        .filters(f -> filters.apply(f, sessionRateLimiter, clientIpKeyResolver))
                        .uri(userService))
                .route("user-service-users", route -> route
                        .path("/api/v1/users/**", "/api/v1/admin/users/**")
                        .filters(f -> filters.apply(f, apiRateLimiter, principalOrIpKeyResolver))
                        .uri(userService))
                .route("user-service-jwks", route -> route
                        .path("/.well-known/jwks.json")
                        .filters(f -> filters.apply(f, apiRateLimiter, clientIpKeyResolver))
                        .uri(userService))
                .build();
    }

    private record RouteFilters(GatewayProperties properties) {

        GatewayFilterSpec apply(GatewayFilterSpec filters, RedisRateLimiter limiter, KeyResolver keyResolver) {
            GatewayFilterSpec spec = filters.setRequestSize(properties.maxRequestSize());
            if (!properties.rateLimit().enabled()) {
                return spec;
            }
            return spec.requestRateLimiter(config -> config
                    .setRateLimiter(limiter)
                    .setKeyResolver(keyResolver)
                    .setDenyEmptyKey(true));
        }
    }
}
