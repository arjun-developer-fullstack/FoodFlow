package com.foodflow.userservice.auth.infrastructure;

import com.foodflow.userservice.auth.application.AuthProperties;
import com.foodflow.userservice.auth.application.PasswordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Password hashing: Argon2id with OWASP Password Storage Cheat Sheet parameters
 * (m = 19 MiB, t = 2, p = 1). Hashes are prefixed with {@code {argon2}}; {@code {bcrypt}} is kept
 * readable so legacy or imported hashes still verify and are upgraded on the next sign-in.
 * All application code goes through the bounded {@link PasswordHasher}.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {

    static final String ENCODING_ID = "argon2";

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;

    @Bean
    PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = Map.of(
                ENCODING_ID, new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS),
                "bcrypt", new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder(ENCODING_ID, encoders);
    }

    @Bean
    PasswordHasher passwordHasher(PasswordEncoder passwordEncoder, AuthProperties properties) {
        AuthProperties.PasswordHashing hashing = properties.passwordHashing();
        return new PasswordHasher(passwordEncoder, hashing.effectiveMaxConcurrent(), hashing.acquireTimeout());
    }
}
