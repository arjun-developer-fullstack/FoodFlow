package com.foodflow.platform.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Transactional outbox settings ({@code foodflow.outbox.*}).
 *
 * @param enabled         opt-in switch; requires JDBC (PostgreSQL) and Kafka on the classpath
 * @param pollInterval    delay between relay polls
 * @param batchSize       max rows published per poll
 * @param sendTimeout     max wait for broker acknowledgements of one batch. Must exceed the
 *                        producer's {@code delivery.timeout.ms}; otherwise a record can still be
 *                        delivered after the relay gave up on it, causing avoidable duplicates
 * @param retention       published rows older than this are purged
 * @param cleanupInterval how often the purge runs
 */
@ConfigurationProperties(prefix = "foodflow.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("15s") Duration sendTimeout,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("1h") Duration cleanupInterval) {
}
