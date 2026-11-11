package com.foodflow.userservice;

import com.foodflow.platform.autoconfigure.FoodflowConfigGuardAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The {@code local} profile must pass the configuration guard with nothing but the secrets from
 * {@code infra/docker/.env}. Loads the real {@code application.yaml} + {@code application-local.yaml}
 * but starts only the guard: no database, Kafka or web server is needed.
 */
class LocalProfileConfigurationTest {

    /** Same relative path as {@code spring.config.import} in application-local.yaml (module directory = cwd). */
    private static final Path LOCAL_ENV_FILE = Path.of("../../infra/docker/.env");

    /** Obviously fake test value; never a real credential. */
    private static final String TEST_PASSWORD = "test-only-not-a-secret";

    @Test
    void localProfileResolvesEveryPlaceholderWhenTheDatabasePasswordIsProvided() {
        // USER_DB_PASSWORD normally comes from infra/docker/.env. The test value is added as the highest-precedence
        // property source, so it also wins when a developer's real .env exists (config data outranks default
        // properties); the real password is therefore never compared against or printed.
        try (ConfigurableApplicationContext context = startLocalProfile(Map.of("USER_DB_PASSWORD", TEST_PASSWORD))) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5432/user_db");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("user_svc");
            // Boolean comparison on purpose: a failure message must never contain a credential
            assertThat(TEST_PASSWORD.equals(env.getProperty("spring.datasource.password")))
                    .as("spring.datasource.password resolves from USER_DB_PASSWORD (value not printed)")
                    .isTrue();
            assertThat(env.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("localhost:9092");
            assertThat(env.getProperty("spring.kafka.producer.properties.apicurio.registry.url"))
                    .isEqualTo("http://localhost:8180/apis/registry/v3");
            assertThat(env.getProperty("spring.kafka.producer.properties.apicurio.registry.auto-register"))
                    .isEqualTo("true");
            // Must equal the gateway's local issuer: tokens are issued for the public entry point
            assertThat(env.getProperty("foodflow.security.jwt.issuer")).isEqualTo("http://localhost:8080");
            assertThat(env.getProperty("foodflow.auth.keys.allow-ephemeral")).isEqualTo("true");
            assertThat(env.getProperty("foodflow.auth.cookie.secure")).isEqualTo("false");
        }
    }

    @Test
    void localProfileFailsFastAndNamesOnlyTheMissingSecret() {
        assumeFalse(Files.exists(LOCAL_ENV_FILE),
                "infra/docker/.env exists on this machine and supplies the secret; covered in CI");

        assertThatThrownBy(LocalProfileConfigurationTest::startLocalProfile)
                .rootCause()
                .hasMessageContaining("USER_DB_PASSWORD")
                .hasMessageNotContaining("USER_DB_URL")
                .hasMessageNotContaining("USER_DB_USERNAME")
                .hasMessageNotContaining("KAFKA_BOOTSTRAP_SERVERS")
                .hasMessageNotContaining("SCHEMA_REGISTRY_URL")
                .hasMessageNotContaining("JWT_ISSUER");
    }

    private static ConfigurableApplicationContext startLocalProfile() {
        return startLocalProfile(Map.of());
    }

    /** {@code overrides} take precedence over every other property source, including infra/docker/.env. */
    private static ConfigurableApplicationContext startLocalProfile(Map<String, Object> overrides) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(GuardOnly.class)
                .web(WebApplicationType.NONE)
                .profiles("local")
                .properties("spring.main.banner-mode=off");
        if (!overrides.isEmpty()) {
            ApplicationContextInitializer<ConfigurableApplicationContext> testOverrides = context -> context
                    .getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
            builder.initializers(testOverrides);
        }
        return builder.run();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(FoodflowConfigGuardAutoConfiguration.class)
    static class GuardOnly {
    }
}
