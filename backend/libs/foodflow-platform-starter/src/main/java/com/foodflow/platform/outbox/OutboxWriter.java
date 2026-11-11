package com.foodflow.platform.outbox;

/**
 * Appends events to the service's {@code outbox_events} table <b>inside the caller's transaction</b>.
 * The event is published only if that transaction commits, so state changes and events can never
 * diverge (ADR-0004).
 */
public interface OutboxWriter {

    /**
     * @throws IllegalStateException if called without an active transaction
     */
    void append(OutboxMessage message);
}
