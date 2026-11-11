package com.foodflow.gateway.ratelimit;

import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the real client address for rate limiting without trusting spoofable headers.
 *
 * <p>{@code X-Forwarded-For} is honoured <b>only</b> when the TCP peer is a trusted proxy (same regex
 * as {@code spring.cloud.gateway.server.webflux.trusted-proxies}). The client is then the entry
 * {@code trustedHops} positions from the right, i.e. the address the outermost trusted proxy saw.
 * A client that reaches the gateway directly cannot influence its key by sending the header.
 */
public final class TrustedProxyClientAddressResolver implements RemoteAddressResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final Pattern trustedProxies;
    private final int trustedHops;

    /**
     * @param trustedProxiesRegex peers allowed to supply X-Forwarded-For; blank = trust nobody
     * @param trustedHops         number of trusted proxies in front of the gateway that append to the header
     */
    public TrustedProxyClientAddressResolver(String trustedProxiesRegex, int trustedHops) {
        if (trustedHops < 1) {
            throw new IllegalArgumentException("trustedHops must be >= 1");
        }
        this.trustedProxies = trustedProxiesRegex == null || trustedProxiesRegex.isBlank()
                ? null : Pattern.compile(trustedProxiesRegex);
        this.trustedHops = trustedHops;
    }

    @Override
    public InetSocketAddress resolve(ServerWebExchange exchange) {
        InetSocketAddress peer = exchange.getRequest().getRemoteAddress();
        if (peer == null || trustedProxies == null || !trustedProxies.matcher(peer.getHostString()).matches()) {
            return peer;
        }
        List<String> forwarded = forwardedFor(exchange);
        if (forwarded.isEmpty()) {
            return peer;
        }
        String client = forwarded.get(Math.max(0, forwarded.size() - trustedHops));
        return IP_LITERAL.matcher(client).matches() ? InetSocketAddress.createUnresolved(client, peer.getPort()) : peer;
    }

    private static List<String> forwardedFor(ServerWebExchange exchange) {
        List<String> values = new ArrayList<>();
        for (String header : exchange.getRequest().getHeaders().getOrEmpty(X_FORWARDED_FOR)) {
            for (String part : header.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return values;
    }
}
