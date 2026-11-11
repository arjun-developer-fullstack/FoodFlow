package com.foodflow.userservice.user;

import com.foodflow.events.AvroClassTrust;
import com.foodflow.events.Topics;
import com.foodflow.events.user.UserRegistered;
import com.foodflow.platform.outbox.OutboxRelay;
import com.foodflow.userservice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Registration -> outbox row (same transaction) -> relay -> Kafka (Avro via Apicurio) -> consumer.
 * Verifies ADR-0004/0005 end to end, including headers used for idempotency and tracing.
 */
class UserRegisteredOutboxIT extends AbstractIntegrationTest {

    @Autowired
    private KafkaContainer kafka;
    @Autowired
    private JdbcTemplate jdbc;
    @Value("${SCHEMA_REGISTRY_URL}")
    private String schemaRegistryUrl;

    @Test
    void registrationPublishesUserRegisteredToKafka() throws Exception {
        AvroClassTrust.install();
        String email = uniqueEmail("event");
        Session session = register(email, "Event User");
        String userId = JsonPath.read(mvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, session.bearer())).andReturn().getResponse().getContentAsString(), "$.id");

        try (KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, AvroKafkaDeserializer.class.getName(),
                "apicurio.registry.url", schemaRegistryUrl,
                "apicurio.registry.use-specific-avro-reader", "true"))) {
            consumer.subscribe(List.of(Topics.USER_EVENTS));
            AtomicReference<ConsumerRecord<String, Object>> found = new AtomicReference<>();

            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (userId.equals(record.key())) {
                        found.set(record);
                    }
                });
                assertThat(found.get()).isNotNull();
            });

            ConsumerRecord<String, Object> record = found.get();
            assertThat(record.value()).isInstanceOf(UserRegistered.class);
            UserRegistered event = (UserRegistered) record.value();
            assertThat(event.getUserId()).isEqualTo(userId);
            assertThat(event.getEmail()).isEqualTo(email);
            assertThat(event.getRoles()).containsExactly("CUSTOMER");
            assertThat(event.getMetadata().getEventType()).isEqualTo("UserRegistered");
            assertThat(event.getMetadata().getSource()).isEqualTo("user-service");
            assertThat(event.getMetadata().getOccurredAt()).isBefore(Instant.now().plusSeconds(1));
            assertThat(header(record, OutboxRelay.HEADER_EVENT_ID)).isEqualTo(event.getMetadata().getEventId());
            assertThat(header(record, OutboxRelay.HEADER_EVENT_TYPE)).isEqualTo("UserRegistered");
            assertThat(header(record, OutboxRelay.HEADER_CORRELATION_ID)).isNotBlank();
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NOT NULL",
                Integer.class, userId)).isEqualTo(1));
    }

    private static String header(ConsumerRecord<String, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
