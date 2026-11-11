package com.foodflow.events.user;

import com.foodflow.events.AvroClassTrust;
import com.foodflow.events.common.EventMetadata;
import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserRegisteredSchemaTest {

    @BeforeAll
    static void trustEventClasses() {
        AvroClassTrust.install();
    }

    @Test
    void roundTripsThroughAvroSingleObjectEncoding() throws IOException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UserRegistered event = UserRegistered.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId("0192f0c4-7d4e-7a2b-9c1d-0123456789ab")
                        .setEventType("UserRegistered")
                        .setSource("user-service")
                        .setOccurredAt(now)
                        .setCorrelationId("corr-1")
                        .build())
                .setUserId("0192f0c4-7d4e-7a2b-9c1d-0123456789ac")
                .setEmail("jane@example.com")
                .setFullName("Jane Doe")
                .setRoles(List.of("CUSTOMER"))
                .setRegisteredAt(now)
                .build();

        ByteBuffer encoded = event.toByteBuffer();
        UserRegistered decoded = UserRegistered.fromByteBuffer(encoded);

        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.getMetadata().getSchemaVersion()).isEqualTo(1);
        assertThat(decoded.getMetadata().getTraceparent()).isNull();
    }

    @Test
    void optionalEnvelopeFieldsDeclareDefaultsForBackwardCompatibility() {
        Schema metadata = EventMetadata.getClassSchema();

        assertThat(metadata.getField("correlationId").hasDefaultValue()).isTrue();
        assertThat(metadata.getField("traceparent").hasDefaultValue()).isTrue();
        assertThat(metadata.getField("schemaVersion").hasDefaultValue()).isTrue();
    }
}
