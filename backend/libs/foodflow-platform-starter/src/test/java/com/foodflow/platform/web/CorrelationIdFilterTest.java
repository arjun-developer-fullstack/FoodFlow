package com.foodflow.platform.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void propagatesAWellFormedIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "abc-123-def-456");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(CorrelationId.MDC_KEY));
            }
        });

        assertThat(seenInMdc).hasValue("abc-123-def-456");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc-123-def-456");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).as("MDC is cleared after the request").isNull();
    }

    @Test
    void replacesMissingOrUnsafeIdsWithAGeneratedOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "evil\nInjected log line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationId.HEADER))
                .isNotBlank()
                .doesNotContain("evil")
                .matches("[0-9a-f-]{36}");
    }

    @Test
    void sanitizeRejectsTooShortAndTooLongIds() {
        assertThat(CorrelationId.sanitizeOrGenerate("short")).isNotEqualTo("short");
        String tooLong = "a".repeat(65);
        assertThat(CorrelationId.sanitizeOrGenerate(tooLong)).isNotEqualTo(tooLong);
        assertThat(CorrelationId.sanitizeOrGenerate(null)).isNotBlank();
    }
}
