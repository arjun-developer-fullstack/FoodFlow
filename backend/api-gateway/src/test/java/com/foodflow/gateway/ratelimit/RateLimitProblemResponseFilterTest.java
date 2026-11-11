package com.foodflow.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitProblemResponseFilterTest {

    @Test
    void retryAfterIsTheTimeToEarnTheRequestedTokensRoundedUp() {
        assertThat(RateLimitProblemResponseFilter.retryAfterSeconds(headers("1", "1"))).isEqualTo(1);   // auth bucket
        assertThat(RateLimitProblemResponseFilter.retryAfterSeconds(headers("20", "1"))).isEqualTo(1);  // api: 50 ms -> 1 s
        assertThat(RateLimitProblemResponseFilter.retryAfterSeconds(headers("2", "5"))).isEqualTo(3);   // 2.5 s -> 3 s
    }

    @Test
    void fallsBackToOneSecondWhenTheLimiterHeadersAreMissingOrInvalid() {
        assertThat(RateLimitProblemResponseFilter.retryAfterSeconds(new HttpHeaders())).isEqualTo(1);
        assertThat(RateLimitProblemResponseFilter.retryAfterSeconds(headers("not-a-number", "1"))).isEqualTo(1);
        assertThat(RateLimitProblemResponseFilter.retryAfterSeconds(headers("0", "1"))).isEqualTo(1);
    }

    private static HttpHeaders headers(String replenishRate, String requestedTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Replenish-Rate", replenishRate);
        headers.set("X-RateLimit-Requested-Tokens", requestedTokens);
        return headers;
    }
}
