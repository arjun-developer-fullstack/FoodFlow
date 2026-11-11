package com.foodflow.platform.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PostgreSQL implementation of {@link OutboxStore} (table DDL: see {@link JdbcOutboxWriter}). */
public class JdbcOutboxStore implements OutboxStore {

    /** Arbitrary constant identifying the publisher advisory lock ("FoodFlow" in ASCII). */
    static final long PUBLISHER_LOCK_KEY = 0x466F6F64466C6F77L;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;

    public JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquirePublisherLock() {
        // Transaction-scoped: released automatically at commit/rollback, safe with connection pools.
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", PUBLISHER_LOCK_KEY)
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<PendingEvent> loadBatch(int limit) {
        return jdbc.sql("""
                        SELECT id, aggregate_id, event_type, topic, payload_type, payload_schema, payload,
                               correlation_id, traceparent
                          FROM outbox_events
                         WHERE published_at IS NULL AND parked_at IS NULL
                         ORDER BY seq
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("topic"),
                        rs.getString("payload_type"),
                        rs.getString("payload_schema"),
                        rs.getBytes("payload"),
                        rs.getString("correlation_id"),
                        rs.getString("traceparent")))
                .list();
    }

    @Override
    public void markPublished(List<UUID> ids, Instant publishedAt) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox_events SET published_at = :now WHERE id IN (:ids)")
                .param("now", Timestamp.from(publishedAt))
                .param("ids", ids)
                .update();
    }

    @Override
    public void recordFailure(UUID id, String error) {
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", truncate(error))
                .param("id", id)
                .update();
    }

    @Override
    public void park(UUID id, String error, Instant parkedAt) {
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, last_error = :error, parked_at = :now WHERE id = :id")
                .param("error", truncate(error))
                .param("now", Timestamp.from(parkedAt))
                .param("id", id)
                .update();
    }

    @Override
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND parked_at IS NULL")
                .query(Long.class).single();
    }

    @Override
    public long countParked() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE parked_at IS NOT NULL")
                .query(Long.class).single();
    }

    @Override
    public int purgePublishedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    private static String truncate(String error) {
        return error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }
}
