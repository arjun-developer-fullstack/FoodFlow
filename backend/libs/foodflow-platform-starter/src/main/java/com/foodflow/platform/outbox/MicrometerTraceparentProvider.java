package com.foodflow.platform.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/** {@link TraceparentProvider} backed by Micrometer Tracing (OpenTelemetry bridge in FoodFlow). */
public class MicrometerTraceparentProvider implements TraceparentProvider {

    private final Tracer tracer;

    public MicrometerTraceparentProvider(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
    }
}
