package com.foodflow.userservice.auth.application;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokensTest {

    @Test
    void generates256BitUrlSafeTokens() {
        String token = OpaqueTokens.generate();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(OpaqueTokens.looksValid(token)).isTrue();
    }

    @Test
    void tokensAreUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            tokens.add(OpaqueTokens.generate());
        }
        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        assertThat(OpaqueTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rejectsMalformedTokens() {
        assertThat(OpaqueTokens.looksValid(null)).isFalse();
        assertThat(OpaqueTokens.looksValid("short")).isFalse();
        assertThat(OpaqueTokens.looksValid("x".repeat(42) + "=")).isFalse();
    }
}
