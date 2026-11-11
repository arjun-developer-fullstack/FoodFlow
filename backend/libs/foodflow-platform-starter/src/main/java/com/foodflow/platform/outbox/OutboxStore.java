package com.foodflow.platform.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Storage operations the {@link OutboxRelay} needs. All methods run inside the relay's transaction.
 * The PostgreSQL implementation is {@link JdbcOutboxStore}.
 */
public interface OutboxStore {

    /** Tries to become the single active publisher for the current transaction. */
    boolean tryAcquirePublisherLock();

    /** Oldest unpublished, unparked rows in commit-sequence order. */
    List<PendingEvent> loadBatch(int limit);

    void markPublished(List<UUID> ids, Instant publishedAt);

    void recordFailure(UUID id, String error);

    /** Moves an undeliverable row out of the queue (operator action required). */
    void park(UUID id, String error, Instant parkedAt);

    long countPending();

    long countParked();

    int purgePublishedBefore(Instant cutoff);
}
