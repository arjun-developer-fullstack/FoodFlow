package com.foodflow.platform.id;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void producesVersion7IetfVariantIds() {
        UUID id = UuidV7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheUnixMillisecondTimestamp() {
        Instant instant = Instant.parse("2026-11-07T10:15:30.123Z");
        UUID id = UuidV7.generate(Clock.fixed(instant, ZoneOffset.UTC));

        long embeddedMillis = id.getMostSignificantBits() >>> 16;
        assertThat(embeddedMillis).isEqualTo(instant.toEpochMilli());
    }

    @Test
    void idsFromLaterMillisecondsSortAfterEarlierOnes() {
        UUID earlier = UuidV7.generate(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        UUID later = UuidV7.generate(Clock.fixed(Instant.ofEpochMilli(1_001), ZoneOffset.UTC));

        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void idsAreUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generate());
        }
        assertThat(ids).hasSize(10_000);
    }
}
