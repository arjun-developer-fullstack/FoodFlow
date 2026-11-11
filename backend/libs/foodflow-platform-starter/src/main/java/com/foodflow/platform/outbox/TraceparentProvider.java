package com.foodflow.platform.outbox;

/**
 * Supplies the W3C {@code traceparent} of the current span, or {@code null} when tracing is
 * unavailable. Stored with each outbox row so consumers continue the originating trace.
 */
@FunctionalInterface
public interface TraceparentProvider {

    TraceparentProvider NONE = () -> null;

    String currentTraceparent();
}
