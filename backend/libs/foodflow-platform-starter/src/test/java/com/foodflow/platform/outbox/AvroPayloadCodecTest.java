package com.foodflow.platform.outbox;

import com.foodflow.events.common.EventMetadata;
import com.foodflow.events.user.UserRegistered;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvroPayloadCodecTest {

    private final AvroPayloadCodec codec = new AvroPayloadCodec();

    @Test
    void roundTripsAGeneratedEvent() {
        UserRegistered event = sampleEvent();

        byte[] bytes = codec.encode(event);
        SpecificRecord decoded = codec.decode(UserRegistered.class.getName(), event.getSchema().toString(), bytes);

        assertThat(decoded).isInstanceOf(UserRegistered.class).isEqualTo(event);
    }

    /**
     * A row written by an older service version (before EventMetadata gained the optional
     * {@code traceparent} field) must still decode into the current class during a rolling deploy.
     */
    @Test
    void decodesRowsWrittenWithAnOlderCompatibleSchema() throws Exception {
        Schema oldMetadata = Schema.createRecord("EventMetadata", null, "com.foodflow.events.common", false,
                EventMetadata.getClassSchema().getFields().stream()
                        .filter(field -> !field.name().equals("traceparent"))
                        .map(field -> new Schema.Field(field, field.schema()))
                        .toList());
        Schema oldEvent = Schema.createRecord("UserRegistered", null, "com.foodflow.events.user", false,
                UserRegistered.getClassSchema().getFields().stream()
                        .map(field -> new Schema.Field(field, field.name().equals("metadata") ? oldMetadata : field.schema()))
                        .toList());

        long now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toEpochMilli();
        GenericRecord metadata = new GenericData.Record(oldMetadata);
        metadata.put("eventId", "0192f0c4-7d4e-7a2b-9c1d-0123456789ab");
        metadata.put("eventType", "UserRegistered");
        metadata.put("source", "user-service-v0");
        metadata.put("occurredAt", now);
        metadata.put("schemaVersion", 1);
        metadata.put("correlationId", null);
        GenericRecord event = new GenericData.Record(oldEvent);
        event.put("metadata", metadata);
        event.put("userId", "0192f0c4-7d4e-7a2b-9c1d-0123456789ac");
        event.put("email", "jane@example.com");
        event.put("fullName", "Jane Doe");
        event.put("roles", List.of("CUSTOMER"));
        event.put("registeredAt", now);
        ByteBuffer oldBytes = new BinaryMessageEncoder<GenericRecord>(GenericData.get(), oldEvent).encode(event);
        byte[] payload = new byte[oldBytes.remaining()];
        oldBytes.get(payload);

        SpecificRecord decoded = codec.decode(UserRegistered.class.getName(), oldEvent.toString(), payload);

        assertThat(decoded).isInstanceOf(UserRegistered.class);
        UserRegistered current = (UserRegistered) decoded;
        assertThat(current.getMetadata().getSource()).isEqualTo("user-service-v0");
        assertThat(current.getMetadata().getTraceparent()).as("new optional field takes its default").isNull();
        assertThat(current.getEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void refusesToLoadClassesOutsideTheEventsPackage() {
        assertThatThrownBy(() -> codec.decode("java.lang.Runtime", null, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Untrusted");
    }

    @Test
    void refusesUnknownEventTypes() {
        assertThatThrownBy(() -> codec.decode("com.foodflow.events.DoesNotExist", null, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    static UserRegistered sampleEvent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return UserRegistered.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId("0192f0c4-7d4e-7a2b-9c1d-0123456789ab")
                        .setEventType("UserRegistered")
                        .setSource("test")
                        .setOccurredAt(now)
                        .build())
                .setUserId("0192f0c4-7d4e-7a2b-9c1d-0123456789ac")
                .setEmail("jane@example.com")
                .setFullName("Jane Doe")
                .setRoles(List.of("CUSTOMER"))
                .setRegisteredAt(now)
                .build();
    }
}
