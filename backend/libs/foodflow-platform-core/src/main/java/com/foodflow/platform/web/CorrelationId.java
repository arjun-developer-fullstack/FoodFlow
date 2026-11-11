package com.foodflow.platform.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation id helpers. The id travels in the {@value #HEADER} header, is stored in the logging
 * MDC under {@value #MDC_KEY} and is echoed in responses and problem details.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Accept only short, log-safe ids so a client cannot inject content into logs. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Returns the incoming id when it is well-formed, otherwise a new random id. */
    public static String sanitizeOrGenerate(String candidate) {
        if (candidate != null && VALID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
