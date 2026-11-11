package com.foodflow.platform.outbox;

import org.apache.avro.specific.SpecificRecord;

import java.util.Objects;
import java.util.UUID;

/**
 * An event to be published to Kafka once the surrounding database transaction commits.
 *
 * @param eventId       unique id, identical to the payload's {@code metadata.eventId}
 * @param aggregateType e.g. {@code User}
 * @param aggregateId   Kafka record key (ordering is guaranteed per aggregate)
 * @param eventType     e.g. {@code UserRegistered}
 * @param topic         destination topic
 * @param payload       Avro event generated from {@code foodflow-event-contracts}
 */
public record OutboxMessage(UUID eventId, String aggregateType, String aggregateId, String eventType,
                            String topic, SpecificRecord payload) {

    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        requireText(topic, "topic");
        Objects.requireNonNull(payload, "payload");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
