package com.foodflow.gateway;

import com.foodflow.platform.autoconfigure.FoodflowConfigGuardAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code local} profile must pass the configuration guard without any environment variables
 * (the gateway needs no local secrets). Loads the real {@code application.yaml} +
 * {@code application-local.yaml} but starts only the guard: no Redis or web server is needed.
 */
class LocalProfileConfigurationTest {

    @Test
    void localProfileResolvesEveryPlaceholderWithoutEnvironmentVariables() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(GuardOnly.class)
                .web(WebApplicationType.NONE)
                .profiles("local")
                .properties("spring.main.banner-mode=off")
                .run()) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("foodflow.gateway.routes.user-service")).isEqualTo("http://localhost:8081");
            assertThat(env.getProperty("foodflow.gateway.cors.allowed-origins")).isEqualTo("http://localhost:5173");
            assertThat(env.getProperty("foodflow.security.jwt.jwk-set-uri"))
                    .isEqualTo("http://localhost:8081/.well-known/jwks.json");
            // Must equal the user-service local issuer
            assertThat(env.getProperty("foodflow.security.jwt.issuer")).isEqualTo("http://localhost:8080");
            assertThat(env.getProperty("spring.data.redis.host")).isEqualTo("localhost");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(FoodflowConfigGuardAutoConfiguration.class)
    static class GuardOnly {
    }
}
