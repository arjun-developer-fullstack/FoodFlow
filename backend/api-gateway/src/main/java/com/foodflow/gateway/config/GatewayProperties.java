package com.foodflow.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

/**
 * Gateway settings ({@code foodflow.gateway.*}).
 *
 * @param routes         downstream service base URIs (Kubernetes service DNS names in the cluster)
 * @param cors           browser origins allowed to call the API with credentials
 * @param rateLimit      Redis token-bucket limits
 * @param clientIp       how the client address is derived behind proxies
 * @param maxRequestSize largest accepted request body; larger requests get 413 at the edge
 */
@Validated
@ConfigurationProperties(prefix = "foodflow.gateway")
public record GatewayProperties(@Valid @NotNull Routes routes, @Valid @NotNull Cors cors,
                                @Valid @NotNull @DefaultValue RateLimit rateLimit,
                                @Valid @NotNull @DefaultValue ClientIp clientIp,
                                @NotNull @DefaultValue("256KB") DataSize maxRequestSize) {

    public record Routes(@NotNull URI userService) {
    }

    /** Exact origins only (scheme://host[:port]); wildcards are rejected because credentials are allowed. */
    public record Cors(@NotEmpty List<String> allowedOrigins) {

        public Cors {
            for (String origin : allowedOrigins == null ? List.<String>of() : allowedOrigins) {
                if (origin.contains("*")) {
                    throw new IllegalArgumentException("Wildcard CORS origins are not allowed: " + origin);
                }
            }
        }
    }

    /**
     * @param enabled disable only for tests without Redis
     * @param auth    sign-in/sign-up, per client IP (brute-force protection)
     * @param session refresh/sign-out, per client IP; lenient because every page load refreshes
     * @param api     per authenticated user (falls back to client IP)
     */
    public record RateLimit(@DefaultValue("true") boolean enabled,
                            @Valid @DefaultValue({"1", "10"}) Bucket auth,
                            @Valid @DefaultValue({"10", "30"}) Bucket session,
                            @Valid @DefaultValue({"20", "40"}) Bucket api) {
    }

    /**
     * @param replenishRate  tokens added per second (sustained requests per second)
     * @param burstCapacity  bucket size (short bursts)
     */
    public record Bucket(@Min(1) int replenishRate, @Min(1) int burstCapacity) {
    }

    /** @param trustedHops trusted proxies in front of the gateway that append X-Forwarded-For (ALB = 1) */
    public record ClientIp(@Min(1) @Max(5) @DefaultValue("1") int trustedHops) {
    }
}
