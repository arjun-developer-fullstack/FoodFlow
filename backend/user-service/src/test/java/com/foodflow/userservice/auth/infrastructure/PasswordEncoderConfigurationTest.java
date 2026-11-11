package com.foodflow.userservice.auth.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigurationTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfiguration().passwordEncoder();

    @Test
    void hashesWithSaltedArgon2id() {
        String first = encoder.encode("correct horse battery staple");
        String second = encoder.encode("correct horse battery staple");

        assertThat(first).startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches("correct horse battery staple", first)).isTrue();
        assertThat(encoder.matches("correct horse battery stapl", first)).isFalse();
        assertThat(encoder.upgradeEncoding(first)).isFalse();
    }

    @Test
    void verifiesLegacyBcryptHashesAndFlagsThemForUpgrade() {
        String legacy = "{bcrypt}" + new BCryptPasswordEncoder().encode("legacy password 123");

        assertThat(encoder.matches("legacy password 123", legacy)).isTrue();
        assertThat(encoder.upgradeEncoding(legacy)).isTrue();
    }
}
