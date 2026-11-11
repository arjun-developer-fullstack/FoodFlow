package com.foodflow.gateway.ratelimit;

import com.foodflow.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitConfigurationTest {

    /** Same default as application.yaml: RFC 1918 + loopback. */
    private static final String TRUSTED = "(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})|(127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})";

    private final RateLimitConfiguration configuration = new RateLimitConfiguration();

    @Test
    void behindTheTrustedLoadBalancerTheLastForwardedHopIsTheClient() {
        RemoteAddressResolver addresses = new TrustedProxyClientAddressResolver(TRUSTED, 1);
        MockServerWebExchange exchange = exchange("10.0.0.5", "1.2.3.4, 198.51.100.23");

        assertThat(configuration.clientIpKeyResolver(addresses).resolve(exchange).block())
                .as("the spoofed left-most entry is ignored")
                .isEqualTo("ip:198.51.100.23");
    }

    @Test
    void aClientConnectingDirectlyCannotSpoofItsAddress() {
        RemoteAddressResolver addresses = new TrustedProxyClientAddressResolver(TRUSTED, 1);
        MockServerWebExchange exchange = exchange("203.0.113.50", "198.51.100.99");

        assertThat(configuration.clientIpKeyResolver(addresses).resolve(exchange).block()).isEqualTo("ip:203.0.113.50");
    }

    @Test
    void supportsTwoTrustedHopsSuchAsCdnPlusLoadBalancer() {
        RemoteAddressResolver addresses = new TrustedProxyClientAddressResolver(TRUSTED, 2);
        MockServerWebExchange exchange = exchange("10.0.0.5", "spoofed, 198.51.100.23, 130.176.0.1");

        assertThat(configuration.clientIpKeyResolver(addresses).resolve(exchange).block()).isEqualTo("ip:198.51.100.23");
    }

    @Test
    void ignoresNonIpValuesAndTrustsNobodyWhenNoProxiesAreConfigured() {
        assertThat(configuration.clientIpKeyResolver(new TrustedProxyClientAddressResolver(TRUSTED, 1))
                .resolve(exchange("10.0.0.5", "evil.example.com")).block()).isEqualTo("ip:10.0.0.5");
        assertThat(configuration.clientIpKeyResolver(new TrustedProxyClientAddressResolver("", 1))
                .resolve(exchange("10.0.0.5", "198.51.100.23")).block()).isEqualTo("ip:10.0.0.5");
    }

    @Test
    void limitsAuthenticatedCallersPerUserAndAnonymousCallersPerIp() {
        KeyResolver resolver = configuration.principalOrIpKeyResolver(new TrustedProxyClientAddressResolver(TRUSTED, 1));
        Principal user = () -> "0192f0c4-7d4e-7a2b-9c1d-0123456789ab";
        MockServerWebExchange authenticated = MockServerWebExchange.builder(
                MockServerHttpRequest.get("/api/v1/users/me").remoteAddress(new InetSocketAddress("203.0.113.9", 443)))
                .principal(user).build();

        assertThat(resolver.resolve(exchange("203.0.113.9", null)).block()).isEqualTo("ip:203.0.113.9");
        assertThat(resolver.resolve(authenticated).block()).isEqualTo("user:0192f0c4-7d4e-7a2b-9c1d-0123456789ab");
    }

    @Test
    void corsOriginsMustBeExplicit() {
        assertThatThrownBy(() -> new GatewayProperties.Cors(List.of("https://*.foodflow.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockServerWebExchange exchange(String peer, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress(peer, 443));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(request);
    }
}
