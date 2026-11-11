package com.foodflow.platform.outbox;

import com.foodflow.platform.web.CorrelationId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Writes outbox rows with {@link JdbcClient}. The JDBC connection is the one bound to the current
 * Spring-managed transaction (JPA or JDBC), so the row commits or rolls back with the business change.
 *
 * <p>Required table (each service owns it in its own Flyway migrations):
 * <pre>
 * CREATE TABLE outbox_events (
 *   seq            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 *   id             UUID         NOT NULL UNIQUE,
 *   aggregate_type VARCHAR(100) NOT NULL,
 *   aggregate_id   VARCHAR(100) NOT NULL,
 *   event_type     VARCHAR(100) NOT NULL,
 *   topic          VARCHAR(200) NOT NULL,
 *   payload_type   VARCHAR(255) NOT NULL,
 *   payload_schema TEXT         NOT NULL,   -- writer schema (Avro JSON), for decoding after evolution
 *   payload        BYTEA        NOT NULL,
 *   correlation_id VARCHAR(64),
 *   traceparent    VARCHAR(128),
 *   created_at     TIMESTAMPTZ  NOT NULL,
 *   published_at   TIMESTAMPTZ,
 *   attempts       INT          NOT NULL DEFAULT 0,
 *   last_error     VARCHAR(1000),
 *   parked_at      TIMESTAMPTZ);                -- undeliverable; needs operator action
 * CREATE INDEX ix_outbox_events_pending ON outbox_events (seq) WHERE published_at IS NULL AND parked_at IS NULL;
 * </pre>
 */
public class JdbcOutboxWriter implements OutboxWriter {

    private final JdbcClient jdbc;
    private final AvroPayloadCodec codec;
    private final Clock clock;
    private final TraceparentProvider traceparentProvider;

    public JdbcOutboxWriter(JdbcClient jdbc, AvroPayloadCodec codec, Clock clock,
                            TraceparentProvider traceparentProvider) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.clock = clock;
        this.traceparentProvider = traceparentProvider;
    }

    @Override
    public void append(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxWriter.append must run inside the transaction that changes the aggregate");
        }
        jdbc.sql("""
                        INSERT INTO outbox_events
                            (id, aggregate_type, aggregate_id, event_type, topic, payload_type, payload_schema,
                             payload, correlation_id, traceparent, created_at)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, :topic, :payloadType, :payloadSchema,
                                :payload, :correlationId, :traceparent, :createdAt)
                        """)
                .param("id", message.eventId())
                .param("aggregateType", message.aggregateType())
                .param("aggregateId", message.aggregateId())
                .param("eventType", message.eventType())
                .param("topic", message.topic())
                .param("payloadType", message.payload().getClass().getName())
                .param("payloadSchema", message.payload().getSchema().toString())
                .param("payload", codec.encode(message.payload()))
                .param("correlationId", CorrelationId.current())
                .param("traceparent", traceparentProvider.currentTraceparent())
                .param("createdAt", Timestamp.from(Instant.now(clock)))
                .update();
    }
}
