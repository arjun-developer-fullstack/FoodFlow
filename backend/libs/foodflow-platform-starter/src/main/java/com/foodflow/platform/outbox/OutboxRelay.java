package com.foodflow.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes committed outbox rows to Kafka (at-least-once, in commit-sequence order).
 *
 * <ul>
 *   <li><b>Single active publisher:</b> each poll runs in one transaction holding a PostgreSQL
 *       advisory lock, so with N replicas exactly one publishes at a time.</li>
 *   <li><b>Ordered and bounded:</b> rows are sent in {@code seq} order and sending stops at the first
 *       failure, so no later row reaches Kafka ahead of an earlier failed one. Acknowledgements are
 *       awaited for the whole batch within {@code send-timeout}; rows are marked published only up
 *       to the first unacknowledged one. Failed polls back off exponentially (max 30 s).</li>
 *   <li><b>Poison rows are parked, not retried forever:</b> a row whose payload cannot be decoded
 *       (corruption, unknown type) can never succeed, so it is parked ({@code parked_at}) and the
 *       queue continues. Serialization or broker failures are retried, because they are usually
 *       transient (registry or broker outage).</li>
 *   <li><b>At-least-once:</b> a crash between send and commit re-publishes rows; consumers must be
 *       idempotent on {@value #HEADER_EVENT_ID}.</li>
 * </ul>
 * Alert on {@code foodflow.outbox.pending} (growing), {@code foodflow.outbox.parked} (&gt; 0) and
 * {@code foodflow.outbox.publish.failures}.
 */
public class OutboxRelay implements SmartLifecycle {

    public static final String HEADER_EVENT_ID = "ff-event-id";
    public static final String HEADER_EVENT_TYPE = "ff-event-type";
    public static final String HEADER_CORRELATION_ID = "ff-correlation-id";
    public static final String HEADER_TRACEPARENT = "traceparent";

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final TransactionOperations transactions;
    private final KafkaTemplate<Object, Object> kafka;
    private final AvroPayloadCodec codec;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter failureCounter;
    private final Counter parkedCounter;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong parked = new AtomicLong();

    private volatile ScheduledExecutorService executor;
    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private int consecutiveFailures;

    public OutboxRelay(OutboxStore store, TransactionOperations transactions, KafkaTemplate<Object, Object> kafka,
                       AvroPayloadCodec codec, OutboxProperties properties, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.transactions = transactions;
        this.kafka = kafka;
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
        this.publishedCounter = Counter.builder("foodflow.outbox.published")
                .description("Outbox events published to Kafka").register(meters);
        this.failureCounter = Counter.builder("foodflow.outbox.publish.failures")
                .description("Failed outbox publish attempts").register(meters);
        this.parkedCounter = Counter.builder("foodflow.outbox.parked.total")
                .description("Outbox events parked as undeliverable").register(meters);
        Gauge.builder("foodflow.outbox.pending", pending, AtomicLong::get)
                .description("Outbox events waiting to be published").register(meters);
        Gauge.builder("foodflow.outbox.parked", parked, AtomicLong::get)
                .description("Parked (undeliverable) outbox events awaiting operator action").register(meters);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("outbox-relay").daemon(true).factory());
        long poll = properties.pollInterval().toMillis();
        long cleanup = properties.cleanupInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::pollSafely, poll, poll, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::cleanupSafely, cleanup, cleanup, TimeUnit.MILLISECONDS);
        this.executor = scheduler;
        log.info("Outbox relay started (poll every {}, batch size {})", properties.pollInterval(),
                properties.batchSize());
    }

    @Override
    public void stop() {
        ScheduledExecutorService scheduler = this.executor;
        this.executor = null;
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(properties.sendTimeout().toSeconds() + 5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** Start late and stop early relative to other lifecycle beans. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    // ------------------------------------------------------------------ publishing

    private void pollSafely() {
        if (Instant.now(clock).isBefore(nextAttemptAt)) {
            return;
        }
        try {
            if (publishPendingBatch().failed()) {
                scheduleBackoff();
            } else {
                consecutiveFailures = 0;
                nextAttemptAt = Instant.EPOCH;
            }
        } catch (Throwable e) { // never let the scheduled task die silently
            log.error("Outbox poll failed", e);
            scheduleBackoff();
        }
    }

    /** Publishes one batch; public for tests and operational tooling. */
    public BatchResult publishPendingBatch() {
        BatchResult result = transactions.execute(status -> {
            if (!store.tryAcquirePublisherLock()) {
                return BatchResult.NONE; // another replica is publishing
            }
            List<PendingEvent> batch = store.loadBatch(properties.batchSize());
            BatchResult outcome = batch.isEmpty() ? BatchResult.NONE : publish(batch);
            pending.set(store.countPending());
            parked.set(store.countParked());
            return outcome;
        });
        return result == null ? BatchResult.NONE : result;
    }

    private BatchResult publish(List<PendingEvent> batch) {
        List<PendingEvent> sentEvents = new ArrayList<>(batch.size());
        List<CompletableFuture<?>> acknowledgements = new ArrayList<>(batch.size());
        int parkedNow = 0;
        boolean failed = false;

        for (PendingEvent event : batch) {
            SpecificRecord payload;
            try {
                payload = codec.decode(event.payloadType(), event.payloadSchema(), event.payload());
            } catch (RuntimeException e) {
                park(event, e);
                parkedNow++;
                continue;
            }
            try {
                acknowledgements.add(kafka.send(toRecord(event, payload)));
                sentEvents.add(event);
            } catch (RuntimeException e) {
                // e.g. schema registry unreachable or schema not registered: retry later, keep order
                recordFailure(event, e);
                failed = true;
                break;
            }
        }

        long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        List<UUID> published = new ArrayList<>(sentEvents.size());
        for (int i = 0; i < sentEvents.size(); i++) {
            try {
                acknowledgements.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                published.add(sentEvents.get(i).id());
            } catch (ExecutionException | TimeoutException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                recordFailure(sentEvents.get(i), cause);
                failed = true;
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed = true;
                break;
            }
        }
        store.markPublished(published, Instant.now(clock));
        publishedCounter.increment(published.size());
        return new BatchResult(published.size(), parkedNow, failed);
    }

    private ProducerRecord<Object, Object> toRecord(PendingEvent event, SpecificRecord payload) {
        RecordHeaders headers = new RecordHeaders();
        addHeader(headers, HEADER_EVENT_ID, event.id().toString());
        addHeader(headers, HEADER_EVENT_TYPE, event.eventType());
        addHeader(headers, HEADER_CORRELATION_ID, event.correlationId());
        addHeader(headers, HEADER_TRACEPARENT, event.traceparent());
        return new ProducerRecord<>(event.topic(), null, event.aggregateId(), payload, headers);
    }

    private static void addHeader(RecordHeaders headers, String name, String value) {
        if (value != null) {
            headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void recordFailure(PendingEvent event, Throwable cause) {
        failureCounter.increment();
        String error = describe(cause);
        log.warn("Publishing outbox event {} ({}) to {} failed: {}", event.id(), event.eventType(), event.topic(), error);
        store.recordFailure(event.id(), error);
    }

    private void park(PendingEvent event, Throwable cause) {
        parkedCounter.increment();
        String error = describe(cause);
        log.error("Parking undeliverable outbox event {} ({}): {}", event.id(), event.eventType(), error);
        store.park(event.id(), error, Instant.now(clock));
    }

    private static String describe(Throwable cause) {
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    private void scheduleBackoff() {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 16);
        long backoff = Math.min(properties.pollInterval().toMillis() << consecutiveFailures, MAX_BACKOFF.toMillis());
        nextAttemptAt = Instant.now(clock).plusMillis(backoff);
    }

    // ------------------------------------------------------------------ housekeeping

    private void cleanupSafely() {
        try {
            int deleted = purgePublished();
            if (deleted > 0) {
                log.debug("Purged {} published outbox events", deleted);
            }
        } catch (Throwable e) {
            log.warn("Outbox cleanup failed", e);
        }
    }

    public int purgePublished() {
        Instant cutoff = Instant.now(clock).minus(properties.retention());
        Integer deleted = transactions.execute(status -> store.purgePublishedBefore(cutoff));
        return deleted == null ? 0 : deleted;
    }

    /** Outcome of one poll. */
    public record BatchResult(int published, int parked, boolean failed) {
        static final BatchResult NONE = new BatchResult(0, 0, false);
    }
}
