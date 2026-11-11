package com.foodflow.platform.outbox;

import java.util.UUID;

/**
 * A committed, not yet published outbox row.
 *
 * @param payloadSchema Avro schema (JSON) the payload was written with, so it can still be decoded
 *                      after the generated class evolved (rolling deployments)
 */
public record PendingEvent(UUID id, String aggregateId, String eventType, String topic, String payloadType,
                           String payloadSchema, byte[] payload, String correlationId, String traceparent) {
}
