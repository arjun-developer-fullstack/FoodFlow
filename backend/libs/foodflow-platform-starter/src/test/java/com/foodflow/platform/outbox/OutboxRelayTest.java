package com.foodflow.platform.outbox;

import com.foodflow.events.user.UserRegistered;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Relay publishing semantics against an in-memory store and a mocked Kafka template. */
class OutboxRelayTest {

    private final AvroPayloadCodec codec = new AvroPayloadCodec();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private InMemoryStore store;
    private KafkaTemplate<Object, Object> kafka;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new InMemoryStore();
        kafka = mock(KafkaTemplate.class);
        OutboxProperties properties = new OutboxProperties(true, Duration.ofMillis(100), 100, Duration.ofSeconds(2),
                Duration.ofDays(7), Duration.ofHours(1));
        relay = new OutboxRelay(store, TransactionOperations.withoutTransaction(), kafka, codec, properties, clock,
                new SimpleMeterRegistry());
    }

    @Test
    void publishesInOrderWithKeyAndHeadersAndMarksRowsPublished() {
        PendingEvent first = store.add(validEvent("user-1"));
        PendingEvent second = store.add(validEvent("user-2"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        OutboxRelay.BatchResult result = relay.publishPendingBatch();

        assertThat(result.published()).isEqualTo(2);
        assertThat(result.failed()).isFalse();
        ArgumentCaptor<ProducerRecord<Object, Object>> sent = recordCaptor();
        verify(kafka, times(2)).send(sent.capture());
        ProducerRecord<Object, Object> record = sent.getAllValues().getFirst();
        assertThat(record.topic()).isEqualTo("foodflow.user.events.v1");
        assertThat(record.key()).isEqualTo("user-1");
        assertThat(record.value()).isInstanceOf(UserRegistered.class);
        assertThat(new String(record.headers().lastHeader(OutboxRelay.HEADER_EVENT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo(first.id().toString());
        assertThat(sent.getAllValues().get(1).key()).isEqualTo("user-2");
        assertThat(store.published).containsExactly(first.id(), second.id());
    }

    @Test
    void stopsSendingAtTheFirstSynchronousFailureSoLaterRowsAreNotDuplicatedOrReordered() {
        PendingEvent first = store.add(validEvent("user-1"));
        store.add(validEvent("user-2"));
        store.add(validEvent("user-3"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenThrow(new IllegalStateException("schema registry unreachable"));

        OutboxRelay.BatchResult result = relay.publishPendingBatch();

        assertThat(result.failed()).isTrue();
        verify(kafka, times(2)).send(any(ProducerRecord.class)); // the third row was never sent
        assertThat(store.published).containsExactly(first.id());
        assertThat(store.failures).hasSize(1);
        assertThat(store.pending()).hasSize(2);
    }

    @Test
    void marksOnlyRowsUpToTheFirstFailedAcknowledgement() {
        PendingEvent first = store.add(validEvent("user-1"));
        store.add(validEvent("user-2"));
        store.add(validEvent("user-3"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));

        OutboxRelay.BatchResult result = relay.publishPendingBatch();

        assertThat(result.failed()).isTrue();
        assertThat(store.published).as("row 3 is retried after row 2 to keep order").containsExactly(first.id());
        assertThat(store.failures.values()).anySatisfy(error -> assertThat(error).contains("broker unavailable"));
    }

    @Test
    void parksUndecodableRowsAndKeepsTheQueueMoving() {
        PendingEvent poison = store.add(new PendingEvent(UUID.randomUUID(), "user-0", "UserRegistered",
                "foodflow.user.events.v1", UserRegistered.class.getName(), UserRegistered.getClassSchema().toString(),
                new byte[]{1, 2, 3}, null, null));
        PendingEvent healthy = store.add(validEvent("user-1"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        OutboxRelay.BatchResult result = relay.publishPendingBatch();

        assertThat(result.parked()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(store.parked).containsKey(poison.id());
        assertThat(store.published).containsExactly(healthy.id());
        assertThat(store.countParked()).isEqualTo(1);
        assertThat(store.countPending()).isZero();
    }

    @Test
    void doesNothingWhenAnotherReplicaHoldsThePublisherLock() {
        store.add(validEvent("user-1"));
        store.lockAvailable = false;

        OutboxRelay.BatchResult result = relay.publishPendingBatch();

        assertThat(result.published()).isZero();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void purgesPublishedRowsOlderThanTheRetention() {
        relay.purgePublished();

        assertThat(store.purgeCutoff).isEqualTo(Instant.now(clock).minus(Duration.ofDays(7)));
    }

    private PendingEvent validEvent(String aggregateId) {
        UserRegistered event = AvroPayloadCodecTest.sampleEvent();
        return new PendingEvent(UUID.randomUUID(), aggregateId, "UserRegistered", "foodflow.user.events.v1",
                UserRegistered.class.getName(), event.getSchema().toString(), codec.encode(event), "corr-12345678", null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ProducerRecord.class);
    }

    /** Minimal in-memory {@link OutboxStore}. */
    static final class InMemoryStore implements OutboxStore {

        final Map<UUID, PendingEvent> rows = new LinkedHashMap<>();
        final List<UUID> published = new ArrayList<>();
        final Map<UUID, String> failures = new LinkedHashMap<>();
        final Map<UUID, String> parked = new LinkedHashMap<>();
        boolean lockAvailable = true;
        Instant purgeCutoff;

        PendingEvent add(PendingEvent event) {
            rows.put(event.id(), event);
            return event;
        }

        List<PendingEvent> pending() {
            return rows.values().stream()
                    .filter(e -> !published.contains(e.id()) && !parked.containsKey(e.id()))
                    .toList();
        }

        @Override
        public boolean tryAcquirePublisherLock() {
            return lockAvailable;
        }

        @Override
        public List<PendingEvent> loadBatch(int limit) {
            return pending().stream().limit(limit).toList();
        }

        @Override
        public void markPublished(List<UUID> ids, Instant publishedAt) {
            published.addAll(ids);
        }

        @Override
        public void recordFailure(UUID id, String error) {
            failures.put(id, error);
        }

        @Override
        public void park(UUID id, String error, Instant parkedAt) {
            parked.put(id, error);
        }

        @Override
        public long countPending() {
            return pending().size();
        }

        @Override
        public long countParked() {
            return parked.size();
        }

        @Override
        public int purgePublishedBefore(Instant cutoff) {
            purgeCutoff = cutoff;
            return 0;
        }
    }
}
